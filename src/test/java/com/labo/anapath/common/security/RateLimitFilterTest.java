package com.labo.anapath.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    private final RateLimitFilter filtre = new RateLimitFilter();

    private int statut(String chemin, String adresse, String xForwardedFor) throws Exception {
        MockHttpServletRequest requete = new MockHttpServletRequest("POST", chemin);
        requete.setRequestURI(chemin);
        requete.setRemoteAddr(adresse);
        if (xForwardedFor != null) {
            requete.addHeader("X-Forwarded-For", xForwardedFor);
        }
        MockHttpServletResponse reponse = new MockHttpServletResponse();
        filtre.doFilter(requete, reponse, new MockFilterChain());
        return reponse.getStatus();
    }

    @Test
    void xForwardedForEcritParLeClientNeChangePasLAdresseComptee() throws Exception {
        // Six essais depuis 8.8.8.8, chacun avec une adresse inventée dans
        // l'en-tête : c'est 8.8.8.8 qui est compté, et le 6ᵉ est refusé.
        for (int i = 1; i <= 5; i++) {
            assertThat(statut("/api/v1/auth/2fa/challenge", "8.8.8.8", "1.2.3." + i)).isEqualTo(200);
        }
        assertThat(statut("/api/v1/auth/2fa/challenge", "8.8.8.8", "1.2.3.6")).isEqualTo(429);

        // L'adresse inventée, elle, n'a rien consommé.
        assertThat(statut("/api/v1/auth/2fa/challenge", "1.2.3.1", null)).isEqualTo(200);
    }

    @Test
    void chaqueRouteASonPropreCompteur() throws Exception {
        for (int i = 1; i <= 5; i++) {
            statut("/api/v1/auth/login", "9.9.9.9", null);
        }
        assertThat(statut("/api/v1/auth/login", "9.9.9.9", null)).isEqualTo(429);
        assertThat(statut("/api/v1/auth/forgot-password", "9.9.9.9", null)).isEqualTo(200);
        assertThat(statut("/api/v1/mobile/login", "9.9.9.9", null)).isEqualTo(200);
    }

    @Test
    void lienCourtDeFactureEstLimiteCommeLAncien() throws Exception {
        // Au-delà de 30 ouvertures par minute depuis une même adresse, refus.
        int dernier = 200;
        for (int i = 0; i < 31; i++) {
            MockHttpServletRequest requete = new MockHttpServletRequest("GET", "/f/jeton" + i);
            requete.setRemoteAddr("7.7.7.7");
            MockHttpServletResponse reponse = new MockHttpServletResponse();
            filtre.doFilter(requete, reponse, new MockFilterChain());
            dernier = reponse.getStatus();
        }
        assertThat(dernier).isEqualTo(429);
    }
}
