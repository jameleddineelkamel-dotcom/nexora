package com.nexora.eservices.livrable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.nexora.eservices.stockage.StockageObjet;
import com.nexora.eservices.deploiement.DeploiementService;
import com.nexora.eservices.deploiement.Paquet;
import com.nexora.eservices.dossier.Dossier;
import com.nexora.eservices.dossier.DossierService;
import com.nexora.eservices.web.EservicesException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfGState;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Livrables (ISSUED_DOCUMENT §10.14) : attestation PDF numérotée à partir du CUR du dossier (RG-CUR-02), portant un
 * code QR de vérification, le nom du signataire, un cachet électronique visible, la date de signature et la date
 * d'expiration (RG1 UC-05) ; aperçu en filigrane « BROUILLON » pour l'agent (RG1 UC-04) ; vérification publique.
 * Le cachet est un HMAC-SHA256 calculé avec la clé du service : il prouve l'intégrité, ce n'est pas une signature
 * électronique qualifiée.
 */
@Service
public class LivrableService {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public record Document(long id, UUID dossierId, String dossierNo, String deliverableCode, String docNo, String baseDocNo, int versionNo,
                           String holderNiu, String holderName, String requestType, LocalDate validFrom, LocalDate validUntil, OffsetDateTime signedAt,
                           String signedBy, String signerName, String qrText, String sealText, String fileName, String sha256, String status) {}

    private final JdbcClient jdbc;
    private final DeploiementService deploiements;
    private final DossierService dossiers;
    private final StockageObjet stockage;
    private final String urlPublique;
    private final byte[] cle;

    public LivrableService(JdbcClient jdbc, DeploiementService deploiements, DossierService dossiers, StockageObjet stockage,
                           @Value("${nexora.eservices.public-url}") String urlPublique, @Value("${nexora.eservices.seal-secret:}") String secret) {
        this.jdbc = jdbc;
        this.stockage = stockage;
        this.deploiements = deploiements;
        this.dossiers = dossiers;
        this.urlPublique = urlPublique.replaceAll("/$", "");
        this.cle = (secret == null || secret.isBlank() ? "nexora-cle-de-developpement" : secret).getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ délivrance
    public Document delivrer(Dossier d, String code, String signataire, String nomSignataire) {
        Paquet p = deploiements.paquet(d.deploymentId());
        Paquet.Deliverable l = p.livrable(code).orElseThrow(() -> new IllegalStateException("Livrable inconnu du paquet : " + code));
        String base;
        int version;
        LocalDate du, au;
        ZoneId fuseau = ZoneId.of(p.reglage("timezone", "Africa/Douala"));
        LocalDate aujourdhui = LocalDate.now(fuseau);
        if ("AMENDMENT".equals(d.requestNature()) && d.parentDossierNo() != null) {
            // L'amendement produit une nouvelle version de l'attestation en cours, même numéro d'inscription, même échéance
            Document precedent = actifPour(d.parentDossierNo()).orElseThrow(() -> new IllegalStateException(
                    "Aucune attestation active pour le dossier source " + d.parentDossierNo()));
            base = precedent.baseDocNo();
            version = jdbc.sql("select max(version_no) from wfe.issued_document where base_doc_no = :b").param("b", base).query(Integer.class).single() + 1;
            du = aujourdhui;
            au = precedent.validUntil();
            jdbc.sql("update wfe.issued_document set status = 'SUPERSEDED' where base_doc_no = :b and status = 'ACTIVE'").param("b", base).update();
        } else {
            base = d.dossierNo() + l.docCode();
            version = 1;
            int annee = aujourdhui.getYear();
            Object renouvellement = d.formData().get(p.reglage("renewalYearField", "renewYear"));
            if ("RENEWAL".equals(d.requestNature()) && renouvellement != null && String.valueOf(renouvellement).matches("\\d{4}")) {
                annee = Integer.parseInt(String.valueOf(renouvellement));
                du = LocalDate.of(annee, 1, 1).isAfter(aujourdhui) ? LocalDate.of(annee, 1, 1) : aujourdhui;   // RG06 UC-02
            } else du = aujourdhui;
            au = switch (l.validityRule()) {
                case "UN_AN" -> du.plusYears(1).minusDays(1);
                case "AUCUNE" -> null;
                default -> LocalDate.of(annee, 12, 31);   // année budgétaire (janvier – décembre)
            };
        }
        String numero = version == 1 ? base : base + String.format("%02d", version - 1);
        String qr = urlPublique + "/verifier/" + numero;
        OffsetDateTime signeLe = OffsetDateTime.now(fuseau);
        String cachet = cachet(numero, d.applicantNiu(), au, signataire);
        byte[] pdf = pdf(p, l, d, numero, version, du, au, nomSignataire, signeLe, qr, cachet, false);
        String nomFichier = numero + ".pdf";   // nom de fichier = numéro du document (§10.14)
        // MinIO configuré : le PDF signé va dans le compartiment, la ligne n'en garde que la clé
        String cleObjet = stockage.actif() ? stockage.deposer(cleLivrable(numero), pdf, "application/pdf") : null;
        long id = jdbc.sql("""
                insert into wfe.issued_document (dossier_id, deliverable_code, doc_no, base_doc_no, version_no, holder_niu, holder_name, request_type,
                  valid_from, valid_until, signed_at, signed_by, signer_name, qr_text, seal_text, file_name, content, storage_key, sha256)
                values (:d, :c, :n, :b, :v, :niu, :nom, :t, :du, :au, :s, :sb, :sn, :qr, :ca, :f, :pdf, :k, :h) returning document_id
                """).param("d", d.id()).param("c", code).param("n", numero).param("b", base).param("v", version).param("niu", d.applicantNiu())
                .param("nom", d.applicantName()).param("t", d.requestType()).param("du", du).param("au", au).param("s", signeLe).param("sb", signataire)
                .param("sn", nomSignataire).param("qr", qr).param("ca", cachet).param("f", nomFichier).param("pdf", cleObjet == null ? pdf : null).param("k", cleObjet).param("h", sha256(pdf))
                .query(Long.class).single();
        return document(id);
    }

    /** Clé d'un livrable dans le compartiment : livrables/{numéro}.pdf. */
    public static String cleLivrable(String numero) {
        return "livrables/" + numero + ".pdf";
    }

    /** Aperçu en mode brouillon avec filigrane (RG1 UC-04) : rien n'est enregistré. */
    public byte[] apercu(UUID dossierId) {
        Dossier d = dossiers.lire(dossierId);
        Paquet p = deploiements.paquet(d.deploymentId());
        Paquet.Deliverable l = p.deliverables().stream().findFirst().orElseThrow(() -> EservicesException.introuvable("Cette procédure ne délivre aucun livrable."));
        LocalDate aujourdhui = LocalDate.now(ZoneId.of(p.reglage("timezone", "Africa/Douala")));
        String numero = Objects.toString(d.dossierNo(), "PROVISOIRE") + l.docCode();
        return pdf(p, l, d, numero, 1, aujourdhui, LocalDate.of(aujourdhui.getYear(), 12, 31), "(signataire)", OffsetDateTime.now(),
                urlPublique + "/verifier/" + numero, "BROUILLON — NON SIGNÉ", true);
    }

    // ------------------------------------------------------------------ consultation et vérification
    private static final String COLONNES = """
            c.document_id, c.dossier_id, d.dossier_no, c.deliverable_code, c.doc_no, c.base_doc_no, c.version_no, c.holder_niu, c.holder_name,
            c.request_type, c.valid_from, c.valid_until, c.signed_at, c.signed_by, c.signer_name, c.qr_text, c.seal_text, c.file_name, c.sha256, c.status
            """;

    private Document ligne(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Document(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getObject(11, LocalDate.class), rs.getObject(12, LocalDate.class),
                rs.getObject(13, OffsetDateTime.class), rs.getString(14), rs.getString(15), rs.getString(16), rs.getString(17), rs.getString(18),
                rs.getString(19), rs.getString(20));
    }

    public Document document(long id) {
        return jdbc.sql("select " + COLONNES + " from wfe.issued_document c join wfe.dossier d on d.dossier_id = c.dossier_id where c.document_id = :i")
                .param("i", id).query((rs, i) -> ligne(rs)).single();
    }

    public Optional<Document> parNumero(String numero) {
        return jdbc.sql("select " + COLONNES + " from wfe.issued_document c join wfe.dossier d on d.dossier_id = c.dossier_id where c.doc_no = :n")
                .param("n", numero).query((rs, i) -> ligne(rs)).optional();
    }

    public List<Document> duDossier(UUID dossier) {
        return jdbc.sql("select " + COLONNES + " from wfe.issued_document c join wfe.dossier d on d.dossier_id = c.dossier_id where c.dossier_id = :i order by c.version_no")
                .param("i", dossier).query((rs, i) -> ligne(rs)).list();
    }

    /** Attestation active d'un dossier, ou de l'amendement le plus récent qui l'a remplacée. */
    private Optional<Document> actifPour(String dossierNo) {
        return jdbc.sql("select " + COLONNES + """
                 from wfe.issued_document c join wfe.dossier d on d.dossier_id = c.dossier_id
                 where c.status = 'ACTIVE' and c.base_doc_no in (select base_doc_no from wfe.issued_document x join wfe.dossier y on y.dossier_id = x.dossier_id where y.dossier_no = :n)
                 order by c.version_no desc limit 1
                """).param("n", dossierNo).query((rs, i) -> ligne(rs)).optional();
    }

    public Map.Entry<Document, byte[]> fichier(String numero) {
        Document doc = parNumero(numero).orElseThrow(() -> EservicesException.introuvable("Document introuvable : " + numero));
        dossiers.lire(doc.dossierId());
        byte[] contenu = jdbc.sql("select content, storage_key from wfe.issued_document where doc_no = :n").param("n", numero)
                .query((rs, i) -> rs.getBytes(1) != null ? rs.getBytes(1) : stockage.lire(rs.getString(2))).single();
        return Map.entry(doc, contenu);
    }

    /** Vérification publique (code QR) : authenticité du cachet, statut et validité, sans données personnelles au-delà du titulaire. */
    public Map<String, Object> verifier(String numero) {
        Optional<Document> d = parNumero(numero);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("docNo", numero);
        if (d.isEmpty()) {
            r.put("found", false);
            return r;
        }
        Document x = d.get();
        LocalDate aujourdhui = LocalDate.now();
        boolean cachetOk = cachet(x.docNo(), x.holderNiu(), x.validUntil(), x.signedBy()).equals(x.sealText());
        r.put("found", true);
        r.put("holderName", x.holderName());
        r.put("holderNiu", x.holderNiu());
        r.put("requestType", x.requestType());
        r.put("baseDocNo", x.baseDocNo());
        r.put("versionNo", x.versionNo());
        r.put("validFrom", x.validFrom());
        r.put("validUntil", x.validUntil());
        r.put("signedAt", x.signedAt());
        r.put("signerName", x.signerName());
        r.put("status", x.status());
        r.put("sealValid", cachetOk);
        r.put("valid", cachetOk && "ACTIVE".equals(x.status()) && (x.validUntil() == null || !x.validUntil().isBefore(aujourdhui)));
        return r;
    }

    private String cachet(String numero, String niu, LocalDate au, String signataire) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cle, "HmacSHA256"));
            byte[] h = mac.doFinal((numero + "|" + niu + "|" + au + "|" + signataire).getBytes(StandardCharsets.UTF_8));
            return "CEV1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ PDF
    private byte[] pdf(Paquet p, Paquet.Deliverable l, Dossier d, String numero, int version, LocalDate du, LocalDate au, String signataire,
                       OffsetDateTime signeLe, String qr, String cachet, boolean brouillon) {
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        org.openpdf.text.Document doc = new org.openpdf.text.Document(PageSize.A4, 48, 48, 42, 42);
        PdfWriter w = PdfWriter.getInstance(doc, sortie);
        doc.addTitle(Objects.toString(l.titleFr(), l.labelFr()) + " — " + numero);
        doc.addAuthor(Objects.toString(p.procedure().authority(), "NEXORA"));
        doc.addCreator("NEXORA e-Services");
        doc.open();
        Font petit = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, new Color(90, 100, 120));
        Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10.5f);
        Font gras = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10.5f);
        Font titre = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15f, new Color(11, 42, 111));
        Font marine = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11f, new Color(11, 42, 111));

        PdfPTable entete = new PdfPTable(new float[]{1.3f, 1f});
        entete.setWidthPercentage(100);
        // En-têtes réglables par procédure (réglages « documentHeaderFr » et « documentHeaderEn » du paquet ; « | » = retour à la ligne)
        entete.addCell(sansBord(new Phrase(p.reglage("documentHeaderFr", "RÉPUBLIQUE DU CAMEROUN|Paix – Travail – Patrie").replace("|", "\n") + "\n"
                + Objects.toString(p.procedure().authority(), ""), petit), Element.ALIGN_LEFT));
        entete.addCell(sansBord(new Phrase(p.reglage("documentHeaderEn", "REPUBLIC OF CAMEROON|Peace – Work – Fatherland").replace("|", "\n")
                + "\nGuichet unique — NEXORA e-Services", petit), Element.ALIGN_RIGHT));
        doc.add(entete);
        Paragraph t = new Paragraph(Objects.toString(l.titleFr(), l.labelFr()), titre);
        t.setAlignment(Element.ALIGN_CENTER);
        t.setSpacingBefore(26);
        t.setSpacingAfter(6);
        doc.add(t);
        Paragraph n = new Paragraph("N° " + numero + (version > 1 ? "  (version " + version + ", amendement)" : ""), marine);
        n.setAlignment(Element.ALIGN_CENTER);
        n.setSpacingAfter(18);
        doc.add(n);

        PdfPTable corps = new PdfPTable(new float[]{1f, 1.6f});
        corps.setWidthPercentage(100);
        String type = p.typeDemande(d.requestType()).map(Paquet.RequestType::labelFr).orElse(d.requestType());
        ligne(corps, "Objet", type, gras, normal);
        ligne(corps, "Dossier (CUR)", Objects.toString(d.dossierNo(), "—"), gras, normal);
        for (String champ : l.bodyFields()) {
            String libelle = p.champ(champ).map(Paquet.Field::labelFr).orElse(champ);
            ligne(corps, libelle, valeur(p, champ, d.formData().get(champ)), gras, normal);
        }
        ligne(corps, "Validité", "du " + du.format(JOUR) + (au == null ? "" : " au " + au.format(JOUR)), gras, normal);
        doc.add(corps);

        // Mention réglable par procédure (réglage « documentMention »), sinon mention générique
        Paragraph mention = new Paragraph(p.reglage("documentMention", "Document délivré par " + Objects.toString(p.procedure().authority(), "l'administration")
                + " au titre de la procédure « " + p.procedure().labelFr() + " », dans les conditions et pour la durée de validité indiquées ci-dessus."), normal);
        mention.setSpacingBefore(16);
        doc.add(mention);

        PdfPTable pied = new PdfPTable(new float[]{1.6f, 1f});
        pied.setWidthPercentage(100);
        pied.setSpacingBefore(26);
        PdfPCell signature = sansBord(new Phrase(Objects.toString(l.signatureNote(), "Le signataire") + "\n\n" + signataire + "\nSigné électroniquement le "
                + signeLe.format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm")) + "\n\nCachet électronique visible :\n" + cachet, normal), Element.ALIGN_LEFT);
        pied.addCell(signature);
        PdfPCell code = new PdfPCell(image(qr), false);
        code.setBorder(Rectangle.NO_BORDER);
        code.setHorizontalAlignment(Element.ALIGN_RIGHT);
        pied.addCell(code);
        doc.add(pied);
        Paragraph v = new Paragraph("Vérification : " + qr, petit);
        v.setAlignment(Element.ALIGN_RIGHT);
        doc.add(v);

        if (brouillon) {
            PdfContentByte fond = w.getDirectContentUnder();
            PdfGState g = new PdfGState();
            g.setFillOpacity(0.18f);
            fond.saveState();
            fond.setGState(g);
            ColumnText.showTextAligned(fond, Element.ALIGN_CENTER, new Phrase("BROUILLON — DRAFT", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 64f, new Color(210, 40, 40))),
                    297, 421, 45);
            fond.restoreState();
        }
        doc.close();
        return sortie.toByteArray();
    }

    private static String valeur(Paquet p, String champ, Object v) {
        if (v == null) return "—";
        String s = String.valueOf(v);
        return p.champ(champ).flatMap(f -> f.codeValues() == null ? Optional.empty() : f.codeValues().stream().filter(c -> c.code().equals(s)).findFirst())
                .map(c -> c.labelFr() + " (" + s + ")").orElse(s);
    }

    private static void ligne(PdfPTable t, String libelle, String valeur, Font fl, Font fv) {
        PdfPCell a = new PdfPCell(new Phrase(libelle, fl));
        PdfPCell b = new PdfPCell(new Phrase(valeur, fv));
        for (PdfPCell c : List.of(a, b)) {
            c.setPadding(7);
            c.setBorderColor(new Color(226, 232, 241));
        }
        a.setBackgroundColor(new Color(243, 246, 251));
        t.addCell(a);
        t.addCell(b);
    }

    private static PdfPCell sansBord(Phrase p, int alignement) {
        PdfPCell c = new PdfPCell(p);
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(alignement);
        return c;
    }

    private static Image image(String texte) {
        try {
            BitMatrix m = new QRCodeWriter().encode(texte, BarcodeFormat.QR_CODE, 220, 220, Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 1));
            BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < m.getWidth(); x++)
                for (int y = 0; y < m.getHeight(); y++) img.setRGB(x, y, m.get(x, y) ? 0x0B2A6F : 0xFFFFFF);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(img, "png", png);
            Image i = Image.getInstance(png.toByteArray());
            i.scaleAbsolute(110, 110);
            return i;
        } catch (com.google.zxing.WriterException | IOException e) {
            throw new IllegalStateException("Code QR : " + e.getMessage(), e);
        }
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
