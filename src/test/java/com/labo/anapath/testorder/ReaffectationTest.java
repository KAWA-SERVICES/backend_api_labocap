package com.labo.anapath.testorder;

import com.labo.anapath.branch.BranchRepository;
import com.labo.anapath.common.exception.ReaffectationNonConfirmeeException;
import com.labo.anapath.report.TestPathologyMacro;
import com.labo.anapath.report.TestPathologyMacroRepository;
import com.labo.anapath.user.User;
import com.labo.anapath.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Confier à un second médecin un dossier qu'un premier détient.
 *
 * <h2>Ce que le code faisait avant</h2>
 *
 * <p>Rien. Une demande déjà affectée était détectée, et la seconde affectation
 * était abandonnée en silence : la ligne restait attachée au premier médecin,
 * pendant que l'écran annonçait l'ajout. L'utilisateur croyait avoir transféré
 * le dossier ; le dossier n'avait pas bougé, et rien nulle part ne le disait.</p>
 *
 * <h2>Ce qu'on exige maintenant</h2>
 *
 * <p>Le transfert a lieu, mais seulement s'il est confirmé — un dossier ne
 * change pas de mains par inadvertance. Et la ligne précédente survit, datée :
 * c'est elle qui répond plus tard à « à qui l'avait-on confié d'abord ».</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReaffectationTest {

    @Mock private TestOrderAssignmentRepository assignmentRepository;
    @Mock private TestOrderAssignmentDetailRepository detailRepository;
    @Mock private TestOrderRepository testOrderRepository;
    @Mock private UserRepository userRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private TestPathologyMacroRepository macroRepository;

    @InjectMocks private TestOrderAssignmentServiceImpl service;

    private final UUID BRANCHE = UUID.randomUUID();
    private final UUID DEMANDE = UUID.randomUUID();
    private final UUID LOT_INITIAL = UUID.randomUUID();
    private final UUID LOT_NOUVEAU = UUID.randomUUID();

    private User medecin(String nom) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setFirstname("Florence");
        u.setLastname(nom);
        return u;
    }

    private TestOrderAssignment lot(UUID id, String code, User titulaire) {
        TestOrderAssignment a = new TestOrderAssignment();
        a.setId(id);
        a.setBranchId(BRANCHE);
        a.setCode(code);
        a.setDate(LocalDate.now());
        a.setUser(titulaire);
        a.setDetails(new ArrayList<>());
        return a;
    }

    private TestOrder demande() {
        TestOrder o = new TestOrder();
        o.setId(DEMANDE);
        o.setBranchId(BRANCHE);
        o.setCode("26-0155");
        o.setStatus(TestOrderStatus.VALIDATED);
        return o;
    }

    private TestOrderAssignmentDetail ligne(TestOrderAssignment lot, TestOrder demande) {
        TestOrderAssignmentDetail d = new TestOrderAssignmentDetail();
        d.setId(UUID.randomUUID());
        d.setBranchId(BRANCHE);
        d.setTestOrderAssignment(lot);
        d.setTestOrder(demande);
        d.setTestOrderCode(demande.getCode());
        return d;
    }

    private AssignmentDetailRequestDto requete(boolean confirmee) {
        AssignmentDetailRequestDto dto = new AssignmentDetailRequestDto();
        dto.setTestOrderId(DEMANDE);
        dto.setConfirmerReaffectation(confirmee);
        return dto;
    }

    private void monterLeDecor(TestOrderAssignment nouveauLot,
                               List<TestOrderAssignmentDetail> courantes) {
        when(assignmentRepository.findByIdAndBranchId(eq(nouveauLot.getId()), any()))
                .thenReturn(Optional.of(nouveauLot));
        when(testOrderRepository.findByIdAndBranchId(eq(DEMANDE), any())).thenReturn(Optional.of(demande()));
        when(detailRepository.affectationsCourantes(DEMANDE)).thenReturn(courantes);
        when(detailRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(macroRepository.findByTestOrderId(any())).thenReturn(Optional.empty());
        when(macroRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("sans confirmation, le transfert est refusé et nomme le médecin en place")
    void refuseSansConfirmation() {
        TestOrder d = demande();
        TestOrderAssignment initial = lot(LOT_INITIAL, "AF26-0001", medecin("HOUNNOU"));
        TestOrderAssignment nouveau = lot(LOT_NOUVEAU, "AF26-0002", medecin("ADECHINA"));
        monterLeDecor(nouveau, List.of(ligne(initial, d)));

        assertThatThrownBy(() -> service.addDetail(LOT_NOUVEAU, requete(false)))
                .isInstanceOf(ReaffectationNonConfirmeeException.class)
                // Le message doit porter les deux repères sur lesquels on décide :
                // quel dossier, et chez qui il est. Un « conflit » nu ferait
                // confirmer sans savoir ce qu'on retire à qui.
                .hasMessageContaining("26-0155")
                .hasMessageContaining("HOUNNOU");

        verify(detailRepository, never()).save(any());
    }

    @Test
    @DisplayName("confirmé, le dossier change de mains et l'ancienne ligne est datée")
    void transfereEtDateLAncienne() {
        TestOrder d = demande();
        TestOrderAssignment initial = lot(LOT_INITIAL, "AF26-0001", medecin("HOUNNOU"));
        TestOrderAssignment nouveau = lot(LOT_NOUVEAU, "AF26-0002", medecin("ADECHINA"));
        TestOrderAssignmentDetail ancienne = ligne(initial, d);
        monterLeDecor(nouveau, List.of(ancienne));

        service.addDetail(LOT_NOUVEAU, requete(true));

        assertThat(ancienne.getRemplaceeLe()).isNotNull();
        assertThat(ancienne.estCourante()).isFalse();

        var enregistrees = org.mockito.ArgumentCaptor
                .forClass(TestOrderAssignmentDetail.class);
        verify(detailRepository, org.mockito.Mockito.times(2)).save(enregistrees.capture());
        TestOrderAssignmentDetail nouvelle = enregistrees.getAllValues().get(1);
        assertThat(nouvelle.getTestOrderAssignment()).isSameAs(nouveau);
        // Le nouveau médecin reprend à zéro : hériter du « pris en charge » de
        // son prédécesseur lui ferait croire qu'il a déjà ouvert le dossier.
        assertThat(nouvelle.statutDuMedecin()).isEqualTo(DocteurStatus.A_TRAITER);
        assertThat(nouvelle.estCourante()).isTrue();
    }

    @Test
    @DisplayName("une demande libre s'affecte sans rien demander")
    void premiereAffectationSansConfirmation() {
        TestOrderAssignment nouveau = lot(LOT_NOUVEAU, "AF26-0002", medecin("ADECHINA"));
        monterLeDecor(nouveau, List.of());

        service.addDetail(LOT_NOUVEAU, requete(false));

        verify(detailRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    @DisplayName("l'historique rend les prises en charge dans l'ordre, la courante en dernier")
    void historiqueOrdonne() {
        TestOrder d = demande();
        TestOrderAssignment initial = lot(LOT_INITIAL, "AF26-0001", medecin("HOUNNOU"));
        TestOrderAssignment nouveau = lot(LOT_NOUVEAU, "AF26-0002", medecin("ADECHINA"));
        TestOrderAssignmentDetail premiere = ligne(initial, d);
        premiere.setRemplaceeLe(java.time.LocalDateTime.now());
        TestOrderAssignmentDetail seconde = ligne(nouveau, d);

        when(testOrderRepository.findByIdAndBranchId(DEMANDE, BRANCHE))
                .thenReturn(Optional.of(d));
        when(detailRepository.historiqueDe(DEMANDE))
                .thenReturn(List.of(premiere, seconde));
        when(userRepository.findById(any())).thenReturn(Optional.empty());

        HistoriqueAffectationDto historique = service.historiqueDe(DEMANDE, BRANCHE);

        assertThat(historique.code()).isEqualTo("26-0155");
        assertThat(historique.etapes()).hasSize(2);
        assertThat(historique.etapes().get(0).medecin()).isEqualTo("HOUNNOU Florence");
        assertThat(historique.etapes().get(0).courante()).isFalse();
        assertThat(historique.etapes().get(1).medecin()).isEqualTo("ADECHINA Florence");
        assertThat(historique.etapes().get(1).courante()).isTrue();
    }

    @Test
    @DisplayName("une demande jamais affectée a un historique vide, pas une erreur")
    void historiqueVide() {
        TestOrder d = demande();
        when(testOrderRepository.findByIdAndBranchId(DEMANDE, BRANCHE))
                .thenReturn(Optional.of(d));
        when(detailRepository.historiqueDe(DEMANDE)).thenReturn(List.of());

        assertThat(service.historiqueDe(DEMANDE, BRANCHE).etapes()).isEmpty();
    }

    // ── Affectation pour avis ─────────────────────────────────────────────

    private AssignmentDetailRequestDto pourAvis() {
        AssignmentDetailRequestDto dto = requete(false);
        dto.setPourAvis(true);
        return dto;
    }

    /** La ligne créée par l'appel : la dernière enregistrée. */
    private TestOrderAssignmentDetail derniereEnregistree(int enregistrements) {
        var captees = org.mockito.ArgumentCaptor.forClass(TestOrderAssignmentDetail.class);
        verify(detailRepository, org.mockito.Mockito.times(enregistrements)).save(captees.capture());
        return captees.getAllValues().get(enregistrements - 1);
    }

    @Test
    @DisplayName("pour avis, le titulaire garde le dossier et le second médecin le reçoit aussi")
    void pourAvisGardeLeTitulaire() {
        TestOrder d = demande();
        TestOrderAssignmentDetail titulaire = ligne(lot(LOT_INITIAL, "AF26-0001", medecin("HOUNNOU")), d);
        TestOrderAssignment nouveau = lot(LOT_NOUVEAU, "AF26-0002", medecin("ADECHINA"));
        monterLeDecor(nouveau, List.of(titulaire));

        var reponse = service.addDetail(LOT_NOUVEAU, pourAvis());

        assertThat(titulaire.estCourante()).isTrue();
        TestOrderAssignmentDetail avis = derniereEnregistree(1);
        assertThat(avis.isPourAvis()).isTrue();
        assertThat(avis.getTestOrderAssignment()).isSameAs(nouveau);
        assertThat(reponse.pourAvis()).isTrue();
    }

    @Test
    @DisplayName("on ne demande pas l'avis d'un médecin qui a déjà le dossier")
    void pourAvisRefuseLeMedecinEnPlace() {
        TestOrder d = demande();
        User hounnou = medecin("HOUNNOU");
        TestOrderAssignmentDetail titulaire = ligne(lot(LOT_INITIAL, "AF26-0001", hounnou), d);
        // Un autre lot du même médecin : la demande y serait en double.
        monterLeDecor(lot(LOT_NOUVEAU, "AF26-0002", hounnou), List.of(titulaire));

        assertThatThrownBy(() -> service.addDetail(LOT_NOUVEAU, pourAvis()))
                .isInstanceOf(com.labo.anapath.common.exception.BusinessException.class)
                .hasMessageContaining("HOUNNOU")
                .hasMessageContaining("26-0155");
        verify(detailRepository, never()).save(any());
    }

    @Test
    @DisplayName("réaffecter retire le titulaire, pas les médecins consultés pour avis")
    void reaffecterGardeLesAvis() {
        TestOrder d = demande();
        TestOrderAssignmentDetail titulaire = ligne(lot(LOT_INITIAL, "AF26-0001", medecin("HOUNNOU")), d);
        TestOrderAssignmentDetail avis = ligne(lot(UUID.randomUUID(), "AF26-0003", medecin("MENSAH")), d);
        avis.setPourAvis(true);
        monterLeDecor(lot(LOT_NOUVEAU, "AF26-0002", medecin("ADECHINA")), List.of(titulaire, avis));

        service.addDetail(LOT_NOUVEAU, requete(true));

        assertThat(titulaire.estCourante()).isFalse();
        assertThat(avis.estCourante()).isTrue();
        assertThat(derniereEnregistree(2).isPourAvis()).isFalse();
    }

    @Test
    @DisplayName("réaffecter au médecin consulté pour avis en fait le titulaire, sans double ligne")
    void reaffecterAuMedecinDAvis() {
        TestOrder d = demande();
        User mensah = medecin("MENSAH");
        TestOrderAssignmentDetail titulaire = ligne(lot(LOT_INITIAL, "AF26-0001", medecin("HOUNNOU")), d);
        TestOrderAssignmentDetail avis = ligne(lot(UUID.randomUUID(), "AF26-0003", mensah), d);
        avis.setPourAvis(true);
        monterLeDecor(lot(LOT_NOUVEAU, "AF26-0002", mensah), List.of(titulaire, avis));

        service.addDetail(LOT_NOUVEAU, requete(true));

        assertThat(titulaire.estCourante()).isFalse();
        assertThat(avis.estCourante()).isFalse();
        assertThat(derniereEnregistree(3).isPourAvis()).isFalse();
    }

    @Test
    @DisplayName("reverser la demande dans le lot du médecin d'avis pose la question, au lieu de répondre « déjà là »")
    void promotionDansLeLotDAvisDemandeConfirmation() {
        TestOrder d = demande();
        User mensah = medecin("MENSAH");
        TestOrderAssignment lotDAvis = lot(LOT_NOUVEAU, "AF26-0002", mensah);
        TestOrderAssignmentDetail titulaire = ligne(lot(LOT_INITIAL, "AF26-0001", medecin("HOUNNOU")), d);
        TestOrderAssignmentDetail avis = ligne(lotDAvis, d);
        avis.setPourAvis(true);
        monterLeDecor(lotDAvis, List.of(avis, titulaire));

        assertThatThrownBy(() -> service.addDetail(LOT_NOUVEAU, requete(false)))
                .isInstanceOf(ReaffectationNonConfirmeeException.class)
                .hasMessageContaining("HOUNNOU");
    }

    @Test
    @DisplayName("confirmée dans le lot du médecin d'avis, la réaffectation en fait le titulaire")
    void promotionDansLeLotDAvis() {
        TestOrder d = demande();
        User mensah = medecin("MENSAH");
        TestOrderAssignment lotDAvis = lot(LOT_NOUVEAU, "AF26-0002", mensah);
        TestOrderAssignmentDetail titulaire = ligne(lot(LOT_INITIAL, "AF26-0001", medecin("HOUNNOU")), d);
        TestOrderAssignmentDetail avis = ligne(lotDAvis, d);
        avis.setPourAvis(true);
        monterLeDecor(lotDAvis, List.of(avis, titulaire));

        service.addDetail(LOT_NOUVEAU, requete(true));

        assertThat(titulaire.estCourante()).isFalse();
        assertThat(avis.estCourante()).isFalse();
        TestOrderAssignmentDetail nouvelle = derniereEnregistree(3);
        assertThat(nouvelle.isPourAvis()).isFalse();
        assertThat(nouvelle.getTestOrderAssignment()).isSameAs(lotDAvis);
    }

    @Test
    @DisplayName("sans réponse, le refus nomme le titulaire et non le médecin consulté")
    void refusNommeLeTitulaire() {
        TestOrder d = demande();
        TestOrderAssignmentDetail avis = ligne(lot(UUID.randomUUID(), "AF26-0003", medecin("MENSAH")), d);
        avis.setPourAvis(true);
        TestOrderAssignmentDetail titulaire = ligne(lot(LOT_INITIAL, "AF26-0001", medecin("HOUNNOU")), d);
        // La requête rend la plus récente d'abord : l'avis passe devant.
        monterLeDecor(lot(LOT_NOUVEAU, "AF26-0002", medecin("ADECHINA")), List.of(avis, titulaire));

        assertThatThrownBy(() -> service.addDetail(LOT_NOUVEAU, requete(false)))
                .isInstanceOf(ReaffectationNonConfirmeeException.class)
                .hasMessageContaining("HOUNNOU")
                .hasMessageNotContaining("MENSAH");
    }

    @Test
    @DisplayName("pour avis sur une demande libre : le médecin du lot en devient titulaire")
    void pourAvisSurDemandeLibre() {
        monterLeDecor(lot(LOT_NOUVEAU, "AF26-0002", medecin("ADECHINA")), List.of());

        service.addDetail(LOT_NOUVEAU, pourAvis());

        assertThat(derniereEnregistree(1).isPourAvis()).isFalse();
    }

    @Test
    @DisplayName("l'affectation qui vaut est celle du titulaire, même quand un avis est plus récent")
    void lAffectationQuiVautEstCelleDuTitulaire() {
        TestOrder d = demande();
        TestOrderAssignmentDetail avis = ligne(lot(UUID.randomUUID(), "AF26-0003", medecin("MENSAH")), d);
        avis.setPourAvis(true);
        TestOrderAssignmentDetail titulaire = ligne(lot(LOT_INITIAL, "AF26-0001", medecin("HOUNNOU")), d);
        TestOrderAssignmentDetailRepository depot =
                org.mockito.Mockito.mock(TestOrderAssignmentDetailRepository.class);
        when(depot.affectationsCourantes(DEMANDE)).thenReturn(List.of(avis, titulaire));
        when(depot.findByTestOrderId(DEMANDE)).thenCallRealMethod();

        assertThat(depot.findByTestOrderId(DEMANDE)).containsSame(titulaire);
    }

    /** Une macro est créée à chaque affectation ; le test ne s'y intéresse pas. */
    @SuppressWarnings("unused")
    private TestPathologyMacro ignoree;
}
