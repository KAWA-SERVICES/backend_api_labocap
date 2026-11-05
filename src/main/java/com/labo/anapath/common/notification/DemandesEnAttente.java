package com.labo.anapath.common.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Ce que FluidChat n'a pas reçu ne se perd plus : la demande est gardée avec sa
 * clé et renvoyée à l'identique jusqu'à ce qu'elle passe.
 *
 * <p>Chaque envoi suit le même pas : {@link #cle} (celle de la demande en
 * attente, sinon une neuve), l'envoi, puis {@link #resoudre} s'il aboutit ou
 * {@link #enregistrer} sur {@link FluidChatIndisponibleException}. Reprendre la
 * clé en attente vaut aussi pour un clic du secrétariat pendant la panne : le
 * patient n'est appelé qu'une fois quand FluidChat revient. Une relance décidée
 * par LabSys après un appel abouti trouve la file vide et prend une clé neuve.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DemandesEnAttente {

    /** Au-delà, la demande est abandonnée. */
    static final int DUREE_JOURS = 3;
    /** Demandes rejouées par type et par passage. */
    private static final int LOT_MAX = 100;

    private final DemandeFluidChatRepository repository;
    private final TransactionTemplate transactions;

    /** Clé de la demande en attente pour cet envoi, sinon {@code nouvelle}. */
    public String cle(DemandeFluidChat.Type type, UUID cible, Supplier<String> nouvelle) {
        return repository.findByTypeAndCibleIdAndAbandonneeLeIsNull(type, cible)
                .map(DemandeFluidChat::getCle)
                .orElseGet(nouvelle);
    }

    /** Clé neuve d'un appel ou d'un bip : {@code <préfixe>-<32 hex>}, sous les 64 caractères de FluidChat. */
    public static String nouvelleCle(String prefixe) {
        return prefixe + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Garde la demande pour un renvoi.
     *
     * <p>Transaction propre : l'appelant relance l'erreur, et sa transaction
     * annulée emporterait la demande avec elle.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enregistrer(DemandeFluidChat.Type type, UUID cible, UUID branchId, String cle, String erreur) {
        DemandeFluidChat demande = repository.findByTypeAndCibleIdAndAbandonneeLeIsNull(type, cible)
                .orElseGet(() -> {
                    DemandeFluidChat d = new DemandeFluidChat();
                    d.setType(type);
                    d.setCibleId(cible);
                    d.setBranchId(branchId);
                    d.setCle(cle);
                    return d;
                });
        demande.setTentatives(demande.getTentatives() + 1);
        demande.setDerniereErreur(court(erreur));
        repository.save(demande);
        log.info("FluidChat injoignable : {} {} gardé pour renvoi (tentative {})", type, cible, demande.getTentatives());
    }

    /** L'envoi a abouti : plus rien à renvoyer. Dans la transaction de l'appelant. */
    @Transactional
    public void resoudre(DemandeFluidChat.Type type, UUID cible) {
        repository.findByTypeAndCibleIdAndAbandonneeLeIsNull(type, cible).ifPresent(repository::delete);
    }

    /**
     * Rejoue les demandes en attente d'un type ; l'appelant vérifie la plage d'appel.
     *
     * <p>{@code envoi} est l'action métier d'origine : elle reprend la clé gardée
     * d'elle-même, et {@link #resoudre} ou {@link #enregistrer} selon l'issue. Tout
     * autre échec — refus 4xx, envoi devenu sans objet — arrête la demande : il se
     * répéterait à chaque passage.</p>
     *
     * <p>Une transaction par demande : une panne qui dure fait attendre chaque
     * envoi jusqu'au délai, et ne doit pas tenir de verrou pendant tout le
     * passage. FluidChat encore injoignable arrête le passage : les suivantes
     * attendraient pour rien. La moins récemment tentée passe en premier, pour
     * qu'une demande refusée en boucle ne bloque pas les autres. Une demande
     * encore là après l'envoi est toujours en panne : l'envoi peut absorber
     * l'erreur pour valider sa transaction (jeton du lien de facture).</p>
     */
    public void rejouer(DemandeFluidChat.Type type, Consumer<UUID> envoi) {
        LocalDateTime limite = LocalDateTime.now().minusDays(DUREE_JOURS);
        for (DemandeFluidChat demande : repository.findByTypeAndAbandonneeLeIsNullOrderByUpdatedAtAsc(
                type, PageRequest.of(0, LOT_MAX))) {
            if (demande.getCreatedAt().isBefore(limite)) {
                // Relue : un clic a pu la résoudre (supprimer) depuis la lecture du lot.
                repository.findById(demande.getId()).ifPresent(d ->
                        abandonner(d, "FluidChat injoignable depuis " + DUREE_JOURS + " jours"));
                continue;
            }
            try {
                transactions.executeWithoutResult(statut -> envoi.accept(demande.getCibleId()));
                if (repository.findById(demande.getId()).filter(d -> d.getAbandonneeLe() == null).isPresent()) {
                    return;
                }
                log.info("Renvoi FluidChat : {} {} parti", type, demande.getCibleId());
            } catch (FluidChatIndisponibleException e) {
                // Toujours en panne : enregistrer() a compté la tentative, on repasse dans 10 minutes.
                return;
            } catch (RuntimeException e) {
                repository.findById(demande.getId()).ifPresent(d -> abandonner(d, e.getMessage()));
            }
        }
    }

    private void abandonner(DemandeFluidChat demande, String raison) {
        demande.setAbandonneeLe(LocalDateTime.now());
        demande.setDerniereErreur(court(raison));
        repository.save(demande);
        log.warn("Renvoi FluidChat abandonné : {} {} après {} tentative(s) — {}",
                demande.getType(), demande.getCibleId(), demande.getTentatives(), raison);
    }

    private static String court(String texte) {
        return texte == null || texte.length() <= 255 ? texte : texte.substring(0, 255);
    }
}
