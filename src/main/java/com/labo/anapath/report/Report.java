package com.labo.anapath.report;

import com.labo.anapath.common.Discipline;
import com.labo.anapath.common.audit.AuditableEntity;
import com.labo.anapath.testorder.TestOrder;
import com.labo.anapath.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Entité représentant un compte-rendu anatomopathologique (CR) lié à un bon d'examen.
 *
 * <p>Un Report est automatiquement créé au statut {@link ReportStatus#DRAFT} lors de
 * la validation d'un {@link TestOrder}. Son code est préfixé par {@code "CO"} suivi
 * du code du bon d'examen (ex. {@code COEX26-0001}).
 *
 * <p>Cycle de vie du compte-rendu :
 * <ul>
 *   <li>{@link ReportStatus#DRAFT} — rédigé en cours, modifiable</li>
 *   <li>{@link ReportStatus#PENDING_REVIEW} — soumis pour relecture</li>
 *   <li>{@link ReportStatus#VALIDATED} — validé par le pathologiste</li>
 *   <li>{@link ReportStatus#DELIVERED} — remis au patient ou prescripteur</li>
 * </ul>
 *
 * <p>La traçabilité de toutes les modifications est assurée via {@link LogReport}.
 */
@Entity
@Table(name = "reports")
@SQLDelete(sql = "UPDATE reports SET deleted_at = NOW() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
public class Report extends AuditableEntity {

    /** Code unique du compte-rendu (format : {@code "CO" + code du TestOrder}). */
    @Column(name = "code", length = 100)
    private String code;

    /**
     * Description initiale du compte-rendu, pré-remplie avec le placeholder
     * configuré dans les réglages de la branche lors de la création automatique.
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Bon d'examen auquel ce compte-rendu est rattaché (relation 1-1). */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "test_order_id", nullable = false)
    private TestOrder testOrder;

    /** Titre de section prédéfini sélectionné pour ce compte-rendu. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "title_id")
    private TitleReport titleReport;

    /** Contenu textuel principal du compte-rendu anatomopathologique. */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** Commentaire additionnel du pathologiste (observations complémentaires). */
    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    /** Statut courant du compte-rendu dans son cycle de vie. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ReportStatus status = ReportStatus.DRAFT;

    /**
     * Discipline du compte-rendu, recopiée depuis le bon. Fixée à la création :
     * plusieurs requêtes filtrent sur reports sans joindre test_orders.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "discipline", nullable = false, length = 20, updatable = false)
    private Discipline discipline = Discipline.PATHOLOGY;

    /** Utilisateur ayant relu et approuvé le compte-rendu. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id")
    private User reviewedBy;

    /**
     * Utilisateur ayant posé l'acte de validation.
     *
     * <p>Distinct des signataires, qui nomment les pathologistes dont la
     * signature figure au document, et du relecteur. Tant que seuls des médecins
     * validaient, ces notions se confondaient ; depuis qu'un secrétaire peut
     * valider certains types d'examen, le compte-rendu doit porter le nom de qui
     * l'a réellement validé — sans quoi le document affirmerait une validation
     * médicale qui n'a pas eu lieu.</p>
     *
     * <p>Nul sur les comptes-rendus validés avant cette colonne : leur trace vit
     * dans le journal, et inventer un validateur ici écrirait une information
     * que personne n'a constatée.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validated_by_user_id")
    private User validatedBy;

    /** Premier signataire du compte-rendu (pathologiste principal). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signatory1")
    private User signatory1;

    /** Deuxième signataire optionnel (co-signataire). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signatory2")
    private User signatory2;

    /** Troisième signataire optionnel. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "signatory3")
    private User signatory3;

    /** Indique si le compte-rendu a été physiquement remis au patient ou à son représentant. */
    @Column(name = "is_delivered", nullable = false)
    private boolean isDelivered = false;

    /** Nom de la personne ayant récupéré le compte-rendu lors de la livraison. */
    @Column(name = "receiver_name", length = 200)
    private String receiverName;

    /** Signature (base64 ou référence) du destinataire lors de la remise du compte-rendu. */
    @Column(name = "receiver_signature", columnDefinition = "TEXT")
    private String receiverSignature;

    /** Date et heure de signature du compte-rendu (posée lors du passage au statut VALIDATED). */
    @Column(name = "signature_date")
    private LocalDateTime signatureDate;

    /**
     * Appareil enrôlé ayant signé la validation, {@code null} depuis le web.
     *
     * <p>Une validation faite au poste de travail ne repose que sur une session
     * ouverte ; celle-ci s'adosse à une clé privée qui ne quitte jamais l'enclave
     * sécurisée d'un téléphone identifié. Les deux doivent se distinguer dans la
     * trace — c'est tout l'objet de la manœuvre.</p>
     */
    @Column(name = "signing_device_id")
    private UUID signingDeviceId;

    /** Signature de l'appareil sur le condensé de l'acte, vérifiée à la réception. */
    @Column(name = "device_signature", columnDefinition = "TEXT")
    private String deviceSignature;

    /** Instant signé par l'appareil — celui du geste, non celui de l'enregistrement. */
    @Column(name = "device_signed_at")
    private LocalDateTime deviceSignedAt;

    /** Date et heure de livraison du compte-rendu (posée lors du passage au statut DELIVERED). */
    @Column(name = "delivery_date")
    private LocalDateTime deliveryDate;

    /** Indique si le patient a été appelé pour récupérer son résultat. */
    @Column(name = "is_called", nullable = false)
    private boolean isCalled = false;

    /** Date et heure de l'appel au patient. */
    @Column(name = "call_date")
    private LocalDateTime callDate;

    /**
     * Date d'envoi de l'avis automatique de disponibilité au patient (appel
     * FluidChat ou SMS), posée par la tâche de rappel.
     *
     * <p>{@code null} signifie qu'aucun avis n'est parti — l'état d'un
     * compte-rendu validé depuis moins de X heures, ou hors de la plage d'appel.
     * C'est sur ce seul critère que la tâche de rappel choisit qui prévenir ; sans lui, elle
     * rappellerait des patients déjà prévenus.</p>
     *
     * <p><b>À ne pas confondre avec {@link #isCalled}</b>, que l'agent coche à la
     * main depuis l'écran de suivi et que la remise du compte-rendu pose aussi :
     * celle-là compte des gestes humains, celle-ci des envois automatiques.</p>
     */
    @Column(name = "patient_notified_at")
    private LocalDateTime patientNotifiedAt;

    /** Contenu microscopique du compte-rendu (description des lames). */
    @Column(name = "content_micro", columnDefinition = "TEXT")
    private String contentMicro;

    /** Commentaire supplémentaire du pathologiste. */
    @Column(name = "comment_sup", columnDefinition = "TEXT")
    private String commentSup;

    /** Description supplémentaire macroscopique. */
    @Column(name = "description_supplementaire", columnDefinition = "TEXT")
    private String descriptionSupplementaire;

    /** Description supplémentaire microscopique. */
    @Column(name = "description_supplementaire_micro", columnDefinition = "TEXT")
    private String descriptionSupplementaireMicro;

    /** Nom du récupérateur physique du compte-rendu (différent de receiverName — usage spécifique). */
    @Column(name = "retriever_name", length = 200)
    private String retrieverName;

    /** Signature base64 du récupérateur lors de la remise physique du compte-rendu. */
    /**
     * À quel titre la personne est repartie avec le compte rendu.
     *
     * <p>Le nom seul dit qui a emporté le dossier, pas si la remise était
     * légitime. Ces deux champs, avec la signature, forment la preuve de
     * remise. Nul pour les retraits antérieurs à cette colonne.</p>
     */
    @Column(name = "retriever_relation", length = 120)
    private String retrieverRelation;

    @Column(name = "retriever_signature", columnDefinition = "TEXT")
    private String retrieverSignature;

    /** Template PDF sélectionné pour ce rapport. */
    @Column(name = "template_id")
    private UUID templateId;

    /** Mots-clés (tags) associés au compte-rendu pour classification et recherche. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "report_tags",
            joinColumns = @JoinColumn(name = "report_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    private List<Tag> tags = new ArrayList<>();
}
