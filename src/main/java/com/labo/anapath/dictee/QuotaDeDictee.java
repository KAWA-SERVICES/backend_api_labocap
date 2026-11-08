package com.labo.anapath.dictee;

import com.labo.anapath.common.exception.InvalidOperationException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Quota mensuel de dictée par agence, tenu par réservation.
 *
 * <p>Avant l'appel au fournisseur, on réserve {@link #RESERVATION_SECONDES}
 * sous un verrou propre à l'agence : N requêtes simultanées ne lisent plus le
 * même total, chacune voit les réservations des précédentes. Le verrou ne dure
 * que la transaction courte de réservation, pas l'appel à ElevenLabs. Après
 * l'appel, la ligne prend la durée réelle — ou disparaît si l'appel a échoué
 * (rien n'est facturé).</p>
 */
@Service
@RequiredArgsConstructor
public class QuotaDeDictee {

    /** Un segment dure 60 s au plus côté front. */
    static final BigDecimal RESERVATION_SECONDES = BigDecimal.valueOf(60);

    private final ConsommationDicteeRepository consommations;
    private final JdbcTemplate jdbc;

    @Value("${app.dictee.quota-mensuel-minutes:1200}")
    private long quotaMensuelMinutes;

    /** Réserve un segment pour l'agence ; refuse (422) si le quota du mois est atteint. */
    @Transactional
    public UUID reserver(UUID branchId, UUID userId) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "dictee:" + branchId);
        if (consommeCeMois(branchId).add(RESERVATION_SECONDES).compareTo(quotaSecondes()) > 0) {
            throw new InvalidOperationException("DICTEE_QUOTA_ATTEINT");
        }
        return consommations.save(new ConsommationDictee(branchId, userId, RESERVATION_SECONDES)).getId();
    }

    /**
     * Remplace la réservation par la durée facturée.
     * ponytail: un fichier forgé à très bas débit peut durer plus de 60 s ; sa durée réelle est
     * comptée, et le quota bloque les suivants. Lire la durée du conteneur avant envoi si ça compte.
     */
    @Transactional
    public void ajuster(UUID reservation, BigDecimal secondes) {
        jdbc.update("UPDATE consommation_dictee SET secondes = ? WHERE id = ?", secondes, reservation);
    }

    /** Minutes entières qui restent à l'agence ce mois-ci (0 au plus bas) ; le front prévient sous un seuil. */
    public long minutesRestantes(UUID branchId) {
        long restantes = quotaSecondes().subtract(consommeCeMois(branchId))
                .divide(BigDecimal.valueOf(60), 0, RoundingMode.FLOOR).longValue();
        return Math.max(0, restantes);
    }

    /** Même fenêtre et même unité pour le refus et l'alerte : ils ne peuvent pas se contredire. */
    private BigDecimal consommeCeMois(UUID branchId) {
        return consommations.secondesDepuis(branchId, LocalDate.now().withDayOfMonth(1).atStartOfDay());
    }

    private BigDecimal quotaSecondes() {
        return BigDecimal.valueOf(quotaMensuelMinutes * 60);
    }

    @Transactional
    public void liberer(UUID reservation) {
        consommations.deleteById(reservation);
    }
}
