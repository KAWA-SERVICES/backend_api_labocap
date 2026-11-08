package com.labo.anapath.testorder;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class AssignmentDetailRequestDto {
    @NotNull(message = "L'identifiant du bon d'examen est obligatoire")
    private UUID testOrderId;
    /**
     * Étiquettes des prélèvements qui partent — « L1 », « L2 »…
     *
     * <p>Facultatives : une demande à prélèvement unique n'en a pas besoin, et
     * les exiger ferait inventer une étiquette là où il n'y en a pas.</p>
     */
    private List<String> labels;

    private String note;
    private LocalDate date;

    /**
     * L'accord explicite pour retirer la demande au médecin qui l'a.
     *
     * <p>Faux par défaut, et le serveur refuse sans lui : la réaffectation est
     * permise, elle ne doit simplement pas se produire par inadvertance. Poser
     * la question côté écran seulement laisserait passer tout appel qui ne
     * passe pas par cet écran.</p>
     *
     * <p>Sans effet quand la demande n'est affectée à personne, ou qu'elle est
     * déjà dans ce lot.</p>
     */
    private boolean confirmerReaffectation;

    /**
     * Confier la demande au médecin du lot pour avis, sans la retirer à ceux
     * qui l'ont.
     *
     * <p>Vaut accord : c'est l'autre réponse à la même question que
     * {@link #confirmerReaffectation}. Sans effet sur une demande affectée à
     * personne, qui va alors au médecin du lot comme titulaire. L'emporte sur
     * {@link #confirmerReaffectation} si les deux sont envoyés.</p>
     */
    private boolean pourAvis;
}
