package com.labo.anapath.report;

import com.labo.anapath.testorder.TestOrder;
import com.labo.anapath.testorder.TestOrderAssignment;
import com.labo.anapath.testorder.TestOrderAssignmentDetail;
import com.labo.anapath.testorder.TestOrderAssignmentDetailRepository;
import com.labo.anapath.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La date d'un avis est le jour où on l'a demandé, pas la date du lot.
 *
 * <p>Constaté en production : un avis demandé le 9 novembre dans un lot daté
 * du 14 septembre s'affichait au 14 septembre, comme l'affectation d'origine.</p>
 */
class DateDeLAvisTest {

    @Test
    @DisplayName("un lot ancien recevant un avis aujourd'hui affiche la date d'aujourd'hui")
    @SuppressWarnings("unchecked")
    void dateDeLaDemandeDAvis() {
        UUID demandeId = UUID.randomUUID();
        TestOrder demande = new TestOrder();
        demande.setId(demandeId);
        Report report = new Report();
        report.setTestOrder(demande);

        User takin = new User();
        takin.setLastname("TAKIN");
        takin.setFirstname("Paul");
        TestOrderAssignment lotDeSeptembre = new TestOrderAssignment();
        lotDeSeptembre.setUser(takin);
        lotDeSeptembre.setDate(LocalDate.of(2026, 9, 14));
        TestOrderAssignmentDetail avis = new TestOrderAssignmentDetail();
        avis.setTestOrderAssignment(lotDeSeptembre);
        avis.setPourAvis(true);
        avis.setCreatedAt(LocalDateTime.of(2026, 11, 9, 8, 2));

        TestOrderAssignmentDetailRepository depot = mock(TestOrderAssignmentDetailRepository.class);
        when(depot.affectationsCourantes(demandeId)).thenReturn(List.of(avis));
        when(depot.findByTestOrderId(demandeId)).thenReturn(Optional.empty());
        ReportServiceImpl service = mock(ReportServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "assignmentDetailRepository", depot);

        List<AvisDto> lus = (List<AvisDto>) ReflectionTestUtils.invokeMethod(service, "avisDe", report);

        assertThat(lus).containsExactly(new AvisDto("TAKIN Paul", LocalDate.of(2026, 11, 9)));
    }
}
