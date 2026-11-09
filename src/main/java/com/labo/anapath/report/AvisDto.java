package com.labo.anapath.report;

import java.time.LocalDate;

/**
 * Un médecin consulté pour avis, et la date de son lot.
 *
 * <p>Même lecture que l'affectation du titulaire — la date, puis le nom : c'est
 * ainsi que la fiche les montre, l'un sous l'autre.</p>
 *
 * @param medecin son nom, nom de famille en tête
 * @param date    la date du lot qui lui a confié le dossier
 */
public record AvisDto(String medecin, LocalDate date) {}
