package com.labo.anapath.assistant;

import org.springframework.http.HttpStatus;

/**
 * Un refus de l'assistant avant toute réponse en flux : budget atteint,
 * conversation occupée, trop d'envois, assistant pas configuré…
 *
 * <p>Levée avant que le premier octet du flux soit écrit, elle redevient une
 * réponse JSON ordinaire par le {@code GlobalExceptionHandler}.</p>
 */
public class AssistantException extends RuntimeException {

    private final HttpStatus statut;
    private final String code;

    public AssistantException(HttpStatus statut, String code, String message) {
        super(message);
        this.statut = statut;
        this.code = code;
    }

    public HttpStatus statut() {
        return statut;
    }

    public String code() {
        return code;
    }
}
