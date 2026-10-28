package com.labo.anapath.finance;

import com.labo.anapath.common.audit.AuditableEntity;
import com.labo.anapath.contract.Contrat;
import com.labo.anapath.patient.Patient;
import com.labo.anapath.testorder.TestOrder;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entité représentant une facture émise pour un bon d'examen (TestOrder).
 *
 * <p>Le code facture suit le format {@code FA260001} (préfixe «FA» + année sur 2 chiffres
 * + numéro séquentiel sur 4 chiffres).</p>
 *
 * <p>Une facture peut être liée à un {@link com.labo.anapath.contract.Contrat} lorsque
 * le patient est couvert par un accord tarifaire (assurance, hôpital partenaire).
 * Dans ce cas, les prix contractuels s'appliquent.</p>
 *
 * <p>La suppression est logique : le champ {@code deleted_at} est renseigné
 * sans effacer la ligne en base.</p>
 */
@Entity
@Table(name = "invoices")
@SQLDelete(sql = "UPDATE invoices SET deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
public class Invoice extends AuditableEntity {

    /** Code unique de la facture (ex. FA260001). Null avant génération du code définitif. */
    @Column(name = "code", length = 50)
    private String code;

    /** Bon d'examen associé à cette facture (relation 1-1). */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_order_id")
    private TestOrder testOrder;

    /** Patient facturé. Null pour les factures groupées de contrat (invoice_unique=true). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    /**
     * Contrat tarifaire éventuellement applicable.
     * Null si le patient est un particulier sans contrat.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contrat_id")
    private Contrat contrat;

    /** Date de la facture, saisie à la création. Distincte de {@code created_at}. */
    @Column(name = "date")
    private LocalDate date;

    /** Nom du client tel qu'il doit apparaître sur la facture imprimée. */
    @Column(name = "client_name", length = 100)
    private String clientName;

    /** Adresse du client pour la facture imprimée. */
    @Column(name = "client_address", columnDefinition = "TEXT")
    private String clientAddress;

    /**
     * IFU de l'acheteur, transmis à la DGI avec son nom et son adresse.
     *
     * <p>Il manquait à la déclaration : {@code acheteur()} n'envoyait que le
     * nom et l'adresse, y compris quand la vente allait à un client qui en
     * portait un en base. Une vente professionnelle partait sans dire à qui.</p>
     */
    @Column(name = "client_ifu", length = 50)
    private String clientIfu;

    /**
     * L'identité de facturation a été saisie à la main.
     *
     * <p>La validation d'une demande réécrit le nom et l'adresse depuis le
     * patient, à chaque fois. Sans cette marque, une adresse choisie à la
     * normalisation serait effacée au geste suivant — et personne ne verrait
     * que la facture a changé de destinataire.</p>
     */
    @Column(name = "facturation_figee", nullable = false)
    private boolean facturationFigee = false;

    /** Montant brut avant remise (somme des prix unitaires × quantités). */
    @Column(name = "subtotal")
    private Double subtotal;

    /** Remise globale appliquée sur le sous-total. */
    @Column(name = "discount")
    private Double discount;

    /** Montant total dû après remise. Valeur par défaut : zéro avant calcul. */
    @Column(name = "total", nullable = false, precision = 10, scale = 2)
    private BigDecimal total = BigDecimal.ZERO;

    /** Indique si la facture est intégralement réglée (dénormalisation pour requêtes rapides). */
    @Column(name = "paid", nullable = false)
    private Boolean paid = false;

    /** Statut de la facture dans son cycle de vie. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.PENDING;

    /** Date d'échéance de paiement (nullable : paiement immédiat si absent). */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /** Lignes de détail de la facture (analyses facturées). */
    @OneToMany(mappedBy = "invoice", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvoiceDetail> details = new ArrayList<>();

    /** Code MECeF attribué après normalisation fiscale (null avant normalisation). */
    @Column(name = "code_mecef", length = 100)
    private String codeMecef;

    /** Compteurs MECeF (JSON sérialisé). */
    @Column(name = "counters", columnDefinition = "TEXT")
    private String counters;

    /** Date de génération du code MECeF (format string pour compatibilité Laravel). */
    @Column(name = "date_generate", length = 50)
    private String dateGenerate;

    /** Numéro d'identification MECeF. */
    @Column(name = "nim", length = 100)
    private String nim;

    /** QR Code MECeF (base64 ou URL). */
    @Column(name = "qrcode", columnDefinition = "TEXT")
    private String qrcode;

    /** Mode de paiement en clair (ex. "ESPECES", "MOBILEMONEY"). Null tant que non payée. */
    @Column(name = "payment", length = 30)
    private String payment;

    /** Type de facture : 0 = vente (normale), 1 = avoir (remboursement). */
    @Column(name = "status_invoice", nullable = false)
    private int statusInvoice = 0;

    /** UUID de la facture de vente originale pour les avoirs (reference). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reference")
    private Invoice reference;

    /** Code de normalisation manuelle (saisi par l'agent quand MECeF est désactivé). */
    @Column(name = "code_normalise", length = 100)
    private String codeNormalise;

    /**
     * Lien FluidInvoice vers le document de la facture normalisée.
     *
     * <p>Sa présence vaut preuve de normalisation : c'est elle qui fait
     * disparaître le bouton « normaliser » au profit de « voir la facture
     * normalisée ». NULL tant que la facture n'a pas été normalisée.</p>
     */
    @Column(name = "normalized_url", columnDefinition = "TEXT")
    private String normalizedUrl;

    /** Identifiant interne FluidInvoice, référence d'origine des avoirs. */
    @Column(name = "fluidinvoice_id", length = 36)
    private String fluidinvoiceId;

    /**
     * Jeton du lien public de téléchargement envoyé par SMS au client.
     *
     * <p>Le client n'a pas de compte : c'est ce jeton, et non l'identifiant de la
     * facture, qui autorise l'accès au PDF sans authentification. Tiré au sort,
     * il n'est pas devinable depuis la facture. NULL tant qu'aucun lien n'a été
     * émis.</p>
     */
    @Column(name = "share_token", length = 64)
    private String shareToken;

    /**
     * Date au-delà de laquelle le lien public cesse de servir le document.
     *
     * <p>Un SMS reste indéfiniment dans le téléphone de son destinataire ; sans
     * expiration, le lien deviendrait un accès permanent à une pièce comptable.</p>
     */
    @Column(name = "share_token_expires_at")
    private LocalDateTime shareTokenExpiresAt;

    /**
     * Date d'envoi du SMS portant le lien de téléchargement.
     *
     * <p>Une facture est validée par plusieurs gestes successifs — encaissée,
     * puis normalisée — et chacun d'eux signale la facture comme disponible.
     * Cette date, une fois posée, interdit tout nouvel envoi automatique : sans
     * elle, le client recevrait un SMS par geste.</p>
     *
     * <p>Elle n'est écrite qu'après une réponse d'OurVoice, jamais avant : un
     * envoi échoué doit rester rattrapable par le geste suivant.</p>
     */
    @Column(name = "share_sms_sent_at")
    private LocalDateTime shareSmsSentAt;

    /**
     * Date du bip FluidChat qui a fait voir au patient le numéro du rappel de
     * résultat ; nulle tant qu'aucun bip n'est parti.
     *
     * <p>Écrite seulement par {@link InvoiceBipRepository#marquerBip} : le SMS et
     * le bip réagissent au même événement, en parallèle, et l'enregistrement
     * complet de la facture par l'un effacerait la marque posée par l'autre.</p>
     */
    @Column(name = "bip_sent_at", insertable = false, updatable = false)
    private LocalDateTime bipSentAt;
}
