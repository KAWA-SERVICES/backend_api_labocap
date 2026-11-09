package com.labo.anapath.report;

import java.time.LocalDate;

/**
 * Un médecin consulté pour avis, et le jour où on le lui a demandé.
 *
 * <p>La fiche le lit comme l'affectation du titulaire — la date, puis le nom.
 * C'est la date de la demande d'avis, pas celle du lot qui l'a reçue : un lot
 * peut être bien plus ancien que l'avis qu'on y verse.</p>
 *
 * @param medecin son nom, nom de famille en tête
 * @param date    le jour où l'avis lui a été demandé
 */
public record AvisDto(String medecin, LocalDate date) {}
