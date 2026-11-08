package com.labo.anapath.dictee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuotaDeDicteeTest {

    private static final UUID AGENCE = UUID.randomUUID();

    private final ConsommationDicteeRepository consommations = mock(ConsommationDicteeRepository.class);
    private final QuotaDeDictee quota = new QuotaDeDictee(consommations, mock(JdbcTemplate.class));

    private long restantes(String secondesConsommees) {
        ReflectionTestUtils.setField(quota, "quotaMensuelMinutes", 1200L);
        when(consommations.secondesDepuis(eq(AGENCE), any())).thenReturn(new BigDecimal(secondesConsommees));
        return quota.minutesRestantes(AGENCE);
    }

    @Test
    @DisplayName("minutes entières restantes, arrondies vers le bas")
    void arrondiVersLeBas() {
        assertThat(restantes("600.50")).isEqualTo(1189);
        assertThat(restantes("0")).isEqualTo(1200);
    }

    @Test
    @DisplayName("quota dépassé (réservations en vol) → 0, jamais négatif")
    void jamaisNegatif() {
        assertThat(restantes("72100")).isZero();
    }

    @Test
    @DisplayName("seule la consommation depuis le 1er du mois à minuit compte")
    void fenetreDuMois() {
        restantes("0");
        ArgumentCaptor<LocalDateTime> depuis = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(consommations).secondesDepuis(eq(AGENCE), depuis.capture());
        assertThat(depuis.getValue()).isEqualTo(LocalDate.now().withDayOfMonth(1).atStartOfDay());
    }
}
