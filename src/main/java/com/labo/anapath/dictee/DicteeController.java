package com.labo.anapath.dictee;

import com.labo.anapath.common.dto.ApiResponse;
import com.labo.anapath.common.exception.InvalidOperationException;
import com.labo.anapath.common.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Dictée vocale des comptes rendus ({@code POST /api/v1/dictees}) : le front
 * envoie une phrase enregistrée (un segment), reçoit le texte à insérer.
 *
 * <p>L'audio ne fait que traverser : ni stocké ni journalisé. Seule la durée
 * facturée est gardée, pour le quota mensuel de l'agence ({@link QuotaDeDictee},
 * réservé avant l'envoi au fournisseur).</p>
 */
@RestController
@RequestMapping("/api/v1/dictees")
@RequiredArgsConstructor
@Slf4j
public class DicteeController {

    /** 60 s font ~180 Ko en opus 24 kbit/s, ~500 Ko en AAC (Safari) : 768 Ko laisse de la marge, pas plus. */
    static final long TAILLE_MAX_OCTETS = 768L * 1024;

    private final ElevenLabsClient elevenLabs;
    private final QuotaDeDictee quota;

    public record DicteeResponse(String texte, long minutesRestantes) {}

    @PostMapping(consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('edit-reports')")
    public ResponseEntity<ApiResponse<DicteeResponse>> transcrire(
            @RequestParam("audio") MultipartFile audio,
            @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        if (audio.isEmpty() || audio.getSize() > TAILLE_MAX_OCTETS) {
            throw new InvalidOperationException("Segment audio vide ou trop long (60 secondes au plus)");
        }
        String type = audio.getContentType();
        if (type == null || !type.startsWith("audio/")) {
            throw new InvalidOperationException("Le fichier envoyé n'est pas un enregistrement audio");
        }

        byte[] octets = audio.getBytes();
        UUID reservation = quota.reserver(principal.getBranchId(), principal.getId());
        ElevenLabsClient.Transcription t;
        try {
            t = elevenLabs.transcrire(octets, "segment" + extension(type));
        } catch (RuntimeException e) {
            quota.liberer(reservation);
            throw e;
        }
        quota.ajuster(reservation, secondes(t, octets.length));
        // Le texte est déjà facturé : un incident sur ce simple indicateur ne doit pas le faire perdre.
        long restantes;
        try {
            restantes = quota.minutesRestantes(principal.getBranchId());
        } catch (RuntimeException e) {
            log.warn("Dictée : reste du quota illisible pour l'agence {}", principal.getBranchId(), e);
            restantes = -1;
        }
        return ResponseEntity.ok(ApiResponse.success(new DicteeResponse(t.text().strip(), restantes)));
    }

    /**
     * Durée renvoyée par ElevenLabs ; à défaut, estimée par excès depuis la taille
     * (16 kbit/s, en dessous de l'opus du front) pour que le quota ne soit jamais contourné.
     */
    static BigDecimal secondes(ElevenLabsClient.Transcription t, long octets) {
        double s = t.audioDurationSecs() != null ? t.audioDurationSecs() : Math.max(1, octets / 2000.0);
        return BigDecimal.valueOf(s).setScale(2, RoundingMode.UP);
    }

    private static String extension(String type) {
        if (type.contains("webm")) return ".webm";
        if (type.contains("ogg")) return ".ogg";
        if (type.contains("mp4") || type.contains("m4a") || type.contains("aac")) return ".m4a";
        if (type.contains("wav")) return ".wav";
        if (type.contains("mpeg")) return ".mp3";
        return ".audio";
    }
}
