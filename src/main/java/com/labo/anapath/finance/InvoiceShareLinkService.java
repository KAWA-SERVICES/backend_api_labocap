package com.labo.anapath.finance;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Liens publics de téléchargement des factures, envoyés par SMS au client.
 *
 * <p>Le destinataire du SMS n'a pas de compte : le lien doit s'ouvrir sans
 * authentification. L'autorisation tient donc entièrement dans un jeton tiré au
 * sort, porteur de sa propre expiration — voir {@link Invoice#getShareToken()}.</p>
 */
@Service
@RequiredArgsConstructor
public class InvoiceShareLinkService {

    /**
     * Nombre d'octets aléatoires du jeton. 9 octets font 12 caractères en
     * base64url, soit 72 bits : le SMS tient en une unité, et un tirage au hasard
     * reste hors de portée — d'autant que le lien expire et que la route est
     * limitée en débit. Les jetons plus longs déjà envoyés restent valables.
     */
    private static final int OCTETS_JETON = 9;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODEUR = Base64.getUrlEncoder().withoutPadding();

    private final InvoiceRepository invoiceRepository;

    /**
     * Adresse publique de l'API, telle que le client la voit depuis son téléphone.
     * Distincte de l'adresse interne : le lien du SMS doit être joignable depuis
     * l'extérieur, pas seulement depuis le réseau du laboratoire.
     */
    @Value("${app.public-base-url:https://api.caap.bj}")
    private String publicBaseUrl;

    /** Durée de vie du lien, en jours. */
    @Value("${app.invoice.share-link.ttl-days:90}")
    private int ttlJours;

    /**
     * Renvoie l'URL publique de téléchargement de la facture, en émettant un
     * jeton si besoin.
     *
     * <p>Un jeton encore valide est réutilisé tel quel : le régénérer casserait
     * le lien déjà reçu par le client dans un SMS précédent. Il n'est renouvelé
     * que s'il est absent ou expiré.</p>
     *
     * @param invoice facture à partager ; modifiée et enregistrée si un nouveau
     *                jeton doit être émis
     * @return l'URL absolue à envoyer au client
     */
    @Transactional
    public String urlDeTelechargement(Invoice invoice) {
        if (invoice.getShareToken() == null || estExpire(invoice)) {
            byte[] aleatoire = new byte[OCTETS_JETON];
            RANDOM.nextBytes(aleatoire);
            invoice.setShareToken(ENCODEUR.encodeToString(aleatoire));
            invoice.setShareTokenExpiresAt(LocalDateTime.now().plusDays(ttlJours));
            invoiceRepository.save(invoice);
        }
        return construireUrl(invoice.getShareToken());
    }

    /**
     * Retrouve la facture d'un jeton, si celui-ci est connu et non expiré.
     *
     * @param token jeton extrait de l'URL publique
     * @return la facture, ou {@link Optional#empty()} si le jeton est inconnu
     * @throws LienExpireException si le jeton est connu mais périmé — l'appelant
     *                             doit répondre 410 et non 404 : le lien a existé,
     *                             et le dire évite au client de croire à une erreur
     *                             de saisie
     */
    @Transactional(readOnly = true)
    public Optional<Invoice> resoudre(String token) {
        Optional<Invoice> facture = invoiceRepository.findByShareToken(token);
        facture.ifPresent(invoice -> {
            if (estExpire(invoice)) {
                throw new LienExpireException();
            }
        });
        return facture;
    }

    private boolean estExpire(Invoice invoice) {
        return invoice.getShareTokenExpiresAt() == null
                || invoice.getShareTokenExpiresAt().isBefore(LocalDateTime.now());
    }

    private String construireUrl(String token) {
        String base = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        // Chemin court : le lien occupe la moitié du SMS.
        return base + "/f/" + token;
    }

    /** Jeton connu mais périmé : le lien a existé, il ne sert plus. */
    public static class LienExpireException extends RuntimeException {
        public LienExpireException() {
            super("Ce lien de téléchargement a expiré.");
        }
    }
}
