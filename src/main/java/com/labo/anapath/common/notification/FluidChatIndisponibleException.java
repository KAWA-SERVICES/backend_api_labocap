package com.labo.anapath.common.notification;

import com.labo.anapath.common.exception.ExternalApiException;

/**
 * FluidChat n'a pas répondu : erreur réseau, délai dépassé, 502, 503 ou 504.
 *
 * <p>Seul cas où une demande est gardée et renvoyée plus tard
 * ({@link DemandesEnAttente}) : FluidChat a peut-être reçu la demande, et la
 * clé d'idempotence renvoyée à l'identique écarte le doublon. Un refus 4xx
 * (crédit, numéro invalide) n'en est jamais une : le renvoyer le répéterait.</p>
 */
public class FluidChatIndisponibleException extends ExternalApiException {

    public FluidChatIndisponibleException(String message, Throwable cause) {
        super(message, cause);
    }
}
