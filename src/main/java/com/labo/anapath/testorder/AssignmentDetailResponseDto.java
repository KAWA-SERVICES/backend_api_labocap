package com.labo.anapath.testorder;

import java.util.List;
import java.util.UUID;

public record AssignmentDetailResponseDto(
        UUID id,
        UUID testOrderId,
        String testOrderCode,

        /**
         * L'état de la demande — PENDING, VALIDATED, DELIVERED, CANCELLED.
         *
         * <p>C'est ce qu'on vient chercher en rouvrant un lot : savoir lesquels
         * de ses dossiers ont avancé. Sans lui, l'affectation ne dit que ce
         * qu'elle contient, jamais où en est chacun — et il fallait ouvrir les
         * demandes une à une pour le découvrir.</p>
         */
        String statutDemande,
        /**
         * Étiquettes physiques des prélèvements affectés — « L1 », « L2 »…
         * Vide pour les affectations antérieures à leur enregistrement.
         */
        List<String> labels,
        String note,

        /**
         * Le moment où la demande a quitté ce lot pour un autre médecin.
         *
         * <p>{@code null} tant qu'elle y est. Une ligne transférée reste dans
         * le lot — c'est ce qu'on a remis ce jour-là, et le bordereau imprimé
         * ne doit pas changer après coup. À l'écran, en revanche, elle doit se
         * distinguer : sans cela le lot présenterait comme à traiter un dossier
         * qu'on a confié à quelqu'un d'autre.</p>
         */
        java.time.LocalDateTime remplaceeLe,

        /** Vraie quand la demande est dans ce lot pour avis. */
        boolean pourAvis) {}
