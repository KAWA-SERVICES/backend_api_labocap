package com.labo.anapath.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Le résumé d'un dossier recopie la lecture complète, champ pour champ.
 *
 * <p>C'est par ce résumé que l'application mobile ouvre la fiche d'une
 * demande. Un champ ajouté à {@link ReportDetailDto} mais oublié ici ne se voit
 * pas sur le téléphone — c'est arrivé avec les médecins consultés pour avis.</p>
 */
class DossierResumeFideleTest {

    @Test
    @DisplayName("chaque champ du résumé porte la valeur du champ de même nom de la lecture complète")
    void resumeFidele() throws Exception {
        ReportDetailDto detail = detailDeReference();
        // Seule la recopie est éprouvée : la lecture complète est simulée.
        ReportServiceImpl service = mock(ReportServiceImpl.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        doReturn(detail).when(service).findDetailByTestOrderCode(any(), any());

        DossierResumeDto resume = service.findResumeByTestOrderCode("26-3379", UUID.randomUUID());

        for (RecordComponent c : DossierResumeDto.class.getRecordComponents()) {
            Object attendu = ReportDetailDto.class.getMethod(c.getName()).invoke(detail);
            assertThat(c.getAccessor().invoke(resume)).as(c.getName()).isEqualTo(attendu);
        }
        assertThat(resume.medecinsPourAvis()).containsExactly("TAKIN Paul");
    }

    /** Une lecture complète où chaque champ a une valeur reconnaissable. */
    private static ReportDetailDto detailDeReference() throws Exception {
        RecordComponent[] champs = ReportDetailDto.class.getRecordComponents();
        Object[] valeurs = Arrays.stream(champs).map(c -> valeurPour(c)).toArray();
        Class<?>[] types = Arrays.stream(champs).map(RecordComponent::getType).toArray(Class<?>[]::new);
        return ReportDetailDto.class.getDeclaredConstructor(types).newInstance(valeurs);
    }

    private static Object valeurPour(RecordComponent c) {
        Class<?> t = c.getType();
        if (c.getName().equals("medecinsPourAvis")) return List.of("TAKIN Paul");
        if (t == boolean.class) return true;
        if (t == String.class) return c.getName();
        if (t == UUID.class) return UUID.randomUUID();
        if (t == List.class) return List.of(c.getName());
        if (t == java.time.LocalDateTime.class) return java.time.LocalDateTime.of(2026, 11, 9, 6, 0);
        if (t == java.time.LocalDate.class) return java.time.LocalDate.of(2026, 11, 9);
        if (t.isEnum()) return t.getEnumConstants()[0];
        return null;
    }
}
