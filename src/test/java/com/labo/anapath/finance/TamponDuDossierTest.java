package com.labo.anapath.finance;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le QR de la demande sur la facture normalisée : lisible, et jamais posé sur
 * du texte. La page de test reprend la disposition de FluidInvoice — Émetteur
 * à gauche sur dix lignes, Destinataire d'un particulier à droite sur deux.
 */
@DisplayName("Tampon du dossier sur la facture normalisée")
class TamponDuDossierTest {

    private final TamponDuDossier tampon = new TamponDuDossier();

    @Test
    @DisplayName("zone libre → le QR se lit et porte le code de la demande, tiret compris")
    void zoneLibre_qrLisible() throws Exception {
        byte[] resultat = tampon.apposer(factureFluidInvoice(List.of()), "EX26-0001");

        try (PDDocument document = Loader.loadPDF(resultat)) {
            // Lu dans la zone réservée seulement : le QR est bien là où on l'attend.
            float pxParMm = 200f / 25.4f;
            var image = new PDFRenderer(document).renderImageWithDPI(0, 200).getSubimage(
                    Math.round(108 * pxParMm), Math.round(84 * pxParMm),
                    Math.round(87 * pxParMm), Math.round(28 * pxParMm));
            var lu = new MultiFormatReader().decode(
                    new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))));
            assertThat(lu.getText()).isEqualTo("EX26-0001");
            assertThat(new PDFTextStripper().getText(document))
                    .contains("Demande d'examen", "À présenter au retrait du résultat");
        }
    }

    @Test
    @DisplayName("une ligne de texte dans la zone → document rendu tel quel, rien n'est recouvert")
    void texteDansLaZone_documentIntact() throws Exception {
        // Un destinataire à adresse longue, dont la cinquième ligne descend à 88 mm.
        byte[] facture = factureFluidInvoice(List.of(new Ligne(110, 88, "quartier Zongo, derrière la pharmacie")));

        assertThat(tampon.apposer(facture, "EX26-0001")).isSameAs(facture);
    }

    @Test
    @DisplayName("un cadre dessiné dans la zone, sans texte → document rendu tel quel")
    void cadreDansLaZone_documentIntact() throws Exception {
        byte[] facture = factureFluidInvoice(List.of(), true, 0);

        assertThat(tampon.apposer(facture, "EX26-0001")).isSameAs(facture);
    }

    @Test
    @DisplayName("page tournée → document rendu tel quel")
    void pageTournee_documentIntact() throws Exception {
        byte[] facture = factureFluidInvoice(List.of(), false, 90);

        assertThat(tampon.apposer(facture, "EX26-0001")).isSameAs(facture);
    }

    @Test
    @DisplayName("code trop long pour la légende → document rendu tel quel")
    void codeTropLong_documentIntact() throws Exception {
        byte[] facture = factureFluidInvoice(List.of());

        assertThat(tampon.apposer(facture, "EX26-0001-0001-0001-0001-0001")).isSameAs(facture);
    }

    @Test
    @DisplayName("document illisible → rendu tel quel, la facture reste remise")
    void documentIllisible_documentIntact() {
        byte[] pasUnPdf = {1, 2, 3};

        assertThat(tampon.apposer(pasUnPdf, "EX26-0001")).isSameAs(pasUnPdf);
    }

    private record Ligne(float xMm, float hautMm, String texte) {}

    /** Une page 1 disposée comme celle de FluidInvoice, plus les lignes données. */
    private static byte[] factureFluidInvoice(List<Ligne> enPlus) throws IOException {
        return factureFluidInvoice(enPlus, false, 0);
    }

    private static byte[] factureFluidInvoice(List<Ligne> enPlus, boolean cadreDansLaZone, int rotation)
            throws IOException {
        List<Ligne> lignes = new ArrayList<>(List.of(
                new Ligne(15, 15, "Facture de vente"),
                new Ligne(15, 50, "Émetteur"),
                new Ligne(110, 50, "Destinataire"),
                new Ligne(110, 57, "Nom : Patient Test"),
                new Ligne(110, 62.5f, "Adresse : Cotonou"),
                new Ligne(15, 122, "Détails")));
        for (int i = 0; i < 10; i++) {
            lignes.add(new Ligne(15, 57 + i * 5.5f, "Ligne de l'émetteur " + (i + 1)));
        }
        lignes.addAll(enPlus);

        try (PDDocument document = new PDDocument(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            page.setRotation(rotation);
            document.addPage(page);
            float hauteur = page.getMediaBox().getHeight();
            float ptParMm = 72f / 25.4f;
            try (PDPageContentStream dessin = new PDPageContentStream(document, page)) {
                for (Ligne l : lignes) {
                    dessin.beginText();
                    dessin.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 9);
                    // Ligne de 5,5 mm, ligne de base ~4 mm sous son haut.
                    dessin.newLineAtOffset(l.xMm() * ptParMm, hauteur - (l.hautMm() + 4) * ptParMm);
                    dessin.showText(l.texte());
                    dessin.endText();
                }
                if (cadreDansLaZone) {
                    dessin.addRect(150 * ptParMm, hauteur - 100 * ptParMm, 30 * ptParMm, 10 * ptParMm);
                    dessin.stroke();
                }
            }
            document.save(sortie);
            return sortie.toByteArray();
        }
    }
}
