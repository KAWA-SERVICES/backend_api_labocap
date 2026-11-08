package com.labo.anapath.common.exception;

/**
 * Exception levée quand on confie à un médecin une demande qu'un autre a déjà.
 *
 * <p>Ce n'est pas une erreur de l'appelant : la réaffectation est permise, elle
 * doit seulement être voulue. Le refus n'existe que pour forcer la question à
 * être posée — un dossier qui change de mains sans que personne ne s'en rende
 * compte est exactement ce qu'on veut empêcher.</p>
 *
 * <p>Le {@link GlobalExceptionHandler} la traduit en {@code 409 Conflict}. Le
 * message nomme le médecin en place : c'est ce qu'il faut afficher pour que la
 * confirmation ait un sens, et un message vague ferait confirmer à l'aveugle.</p>
 */
public class ReaffectationNonConfirmeeException extends RuntimeException {

    public ReaffectationNonConfirmeeException(String codeDemande, String medecinActuel) {
        super("La demande " + codeDemande + " est actuellement affectée à "
                + medecinActuel + ".");
    }
}
