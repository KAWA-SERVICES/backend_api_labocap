package com.labo.anapath.finance;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Pose le QR de la demande d'examen sur la facture normalisée de FluidInvoice.
 *
 * <p>Le patient présente ce document au comptoir pour retirer son compte rendu ;
 * le QR évite à l'agent de recopier la référence. Il se place sur la page 1, dans
 * la colonne « Destinataire », sous le nom et l'adresse : un particulier n'y a
 * que deux à quatre lignes, quand l'émetteur en a dix en face. Le bloc de
 * sécurité MECeF, en bas de la dernière page, n'est jamais approché.</p>
 *
 * <p><b>Jamais par-dessus quoi que ce soit.</b> Avant de poser le QR, on relève
 * sur la page chaque caractère, trait, aplat et image : si l'un d'eux touche la
 * zone, le document est rendu tel quel. Même chose pour une page tournée ou
 * recadrée, où relevé et dessin ne parleraient plus des mêmes coordonnées, et
 * pour tout échec. Un QR manquant ne doit jamais empêcher de remettre une
 * facture — le code reste lisible sur la facture de LabSys.</p>
 */
// ponytail: zone recopiée de la mise en page de FluidInvoice (invoice_pdf.go :
// colonne droite à 110 mm, bloc Émetteur/Destinataire à 50 mm). Si FluidInvoice
// la change, le relevé retire le QR plutôt que de recouvrir — il faudra alors
// déplacer la zone ici.
@Component
@Slf4j
public class TamponDuDossier {

    private static final float PT_PAR_MM = 72f / 25.4f;

    /** Zone réservée, en mm depuis le coin haut gauche : QR + légende + marge. */
    private static final float ZONE_X = 108f, ZONE_Y = 84f, ZONE_W = 87f, ZONE_H = 28f;

    private static final float QR_X = 110f, QR_Y = 86f, QR_COTE = 22f;
    private static final float LEGENDE_X = 135f;

    private static final PDType1Font POLICE_CODE = new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD);
    private static final float TAILLE_CODE = 11f;

    /**
     * Le PDF avec le QR de {@code codeDemande}, ou le PDF d'origine si la zone
     * n'est pas libre ou si quoi que ce soit échoue.
     */
    public byte[] apposer(byte[] pdf, String codeDemande) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDPage page = document.getPage(0);
            String refus = refus(document, page, codeDemande);
            if (refus != null) {
                log.info("QR de dossier {} non posé : {}", codeDemande, refus);
                return pdf;
            }
            float hauteur = page.getMediaBox().getHeight();
            PDImageXObject qr = LosslessFactory.createFromImage(document, qr(codeDemande));
            try (PDPageContentStream dessin = new PDPageContentStream(
                    document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                dessin.drawImage(qr, pt(QR_X), hauteur - pt(QR_Y + QR_COTE), pt(QR_COTE), pt(QR_COTE));
                ecrire(dessin, new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8, 128, 128, 128,
                        pt(LEGENDE_X), hauteur - pt(QR_Y + 6), "Demande d'examen");
                ecrire(dessin, POLICE_CODE, TAILLE_CODE, 22, 36, 42,
                        pt(LEGENDE_X), hauteur - pt(QR_Y + 12), codeDemande);
                ecrire(dessin, new PDType1Font(Standard14Fonts.FontName.HELVETICA), 7, 138, 150, 155,
                        pt(LEGENDE_X), hauteur - pt(QR_Y + 17), "À présenter au retrait du résultat");
            }
            ByteArrayOutputStream sortie = new ByteArrayOutputStream();
            document.save(sortie);
            return sortie.toByteArray();
        } catch (Exception e) {
            log.warn("QR de dossier {} non posé sur la facture normalisée : {}", codeDemande, e.getMessage());
            return pdf;
        }
    }

    /** Pourquoi ne rien poser, ou nul si la place est libre. */
    private static String refus(PDDocument document, PDPage page, String codeDemande) throws IOException {
        PDRectangle media = page.getMediaBox();
        if (page.getRotation() != 0 || media.getLowerLeftX() != 0 || media.getLowerLeftY() != 0
                || !media.toGeneralPath().getBounds2D().equals(page.getCropBox().toGeneralPath().getBounds2D())) {
            return "page tournée ou recadrée";
        }
        if (POLICE_CODE.getStringWidth(codeDemande) / 1000 * TAILLE_CODE > pt(ZONE_X + ZONE_W - LEGENDE_X)) {
            return "code trop long pour la zone";
        }
        if (texteDansLaZone(document)) {
            return "du texte occupe la zone";
        }
        if (dessinDansLaZone(page)) {
            return "un trait, un aplat ou une image occupe la zone";
        }
        return null;
    }

    /** La zone en points, origine en haut à gauche (repère du texte extrait). */
    private static Rectangle2D zoneDepuisLeHaut() {
        return new Rectangle2D.Float(pt(ZONE_X), pt(ZONE_Y), pt(ZONE_W), pt(ZONE_H));
    }

    /** La zone en points, origine en bas à gauche (repère du dessin PDF). */
    private static Rectangle2D zoneDepuisLeBas(PDPage page) {
        float hauteur = page.getMediaBox().getHeight();
        return new Rectangle2D.Float(pt(ZONE_X), hauteur - pt(ZONE_Y + ZONE_H), pt(ZONE_W), pt(ZONE_H));
    }

    private static boolean texteDansLaZone(PDDocument document) throws IOException {
        Rectangle2D zone = zoneDepuisLeHaut();
        boolean[] occupee = {false};
        PDFTextStripper lecteur = new PDFTextStripper() {
            @Override
            protected void writeString(String texte, List<TextPosition> positions) {
                for (TextPosition c : positions) {
                    if (zone.intersects(c.getXDirAdj(), c.getYDirAdj() - c.getHeightDir(),
                            c.getWidthDirAdj(), c.getHeightDir())) {
                        occupee[0] = true;
                    }
                }
            }
        };
        lecteur.setStartPage(1);
        lecteur.setEndPage(1);
        lecteur.getText(document);
        return occupee[0];
    }

    /**
     * Relève les traits, aplats et images de la page : leur emprise, dans le
     * repère de la page, une fois toutes les transformations appliquées.
     */
    private static boolean dessinDansLaZone(PDPage page) throws IOException {
        Rectangle2D zone = zoneDepuisLeBas(page);
        boolean[] occupee = {false};
        new PDFGraphicsStreamEngine(page) {
            private Rectangle2D trace;
            private final Point2D.Float courant = new Point2D.Float();

            private void inclure(Point2D p) {
                if (trace == null) {
                    trace = new Rectangle2D.Double(p.getX(), p.getY(), 0, 0);
                } else {
                    trace.add(p);
                }
                courant.setLocation(p);
            }

            private void peindre() {
                if (trace != null && trace.intersects(zone)) {
                    occupee[0] = true;
                }
                trace = null;
            }

            @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
                inclure(p0); inclure(p1); inclure(p2); inclure(p3);
            }
            @Override public void moveTo(float x, float y) { inclure(new Point2D.Float(x, y)); }
            @Override public void lineTo(float x, float y) { inclure(new Point2D.Float(x, y)); }
            @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
                inclure(new Point2D.Float(x1, y1)); inclure(new Point2D.Float(x2, y2)); inclure(new Point2D.Float(x3, y3));
            }
            @Override public Point2D getCurrentPoint() { return courant; }
            @Override public void closePath() { }
            @Override public void endPath() { trace = null; }
            @Override public void clip(int windingRule) { }
            @Override public void strokePath() { peindre(); }
            @Override public void fillPath(int windingRule) { peindre(); }
            @Override public void fillAndStrokePath(int windingRule) { peindre(); }
            @Override public void shadingFill(org.apache.pdfbox.cos.COSName shadingName) { }
            @Override public void drawImage(PDImage image) {
                var m = getGraphicsState().getCurrentTransformationMatrix();
                Rectangle2D emprise = m.createAffineTransform()
                        .createTransformedShape(new Rectangle2D.Float(0, 0, 1, 1)).getBounds2D();
                if (emprise.intersects(zone)) {
                    occupee[0] = true;
                }
            }

            void parcourir() throws IOException {
                processPage(getPage());
            }
        }.parcourir();
        return occupee[0];
    }

    private static java.awt.image.BufferedImage qr(String contenu) throws Exception {
        // Correction H, comme le QR de la facture de LabSys : il doit se lire sur
        // une feuille froissée ou mal imprimée.
        var matrice = new QRCodeWriter().encode(contenu, BarcodeFormat.QR_CODE, 300, 300,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H, EncodeHintType.MARGIN, 0));
        return MatrixToImageWriter.toBufferedImage(matrice);
    }

    private static void ecrire(PDPageContentStream dessin, PDType1Font police, float taille,
                               int r, int v, int b, float x, float y, String texte) throws IOException {
        dessin.beginText();
        dessin.setFont(police, taille);
        dessin.setNonStrokingColor(r / 255f, v / 255f, b / 255f);
        dessin.newLineAtOffset(x, y);
        dessin.showText(texte);
        dessin.endText();
    }

    private static float pt(float mm) {
        return mm * PT_PAR_MM;
    }
}
