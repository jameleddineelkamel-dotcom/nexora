package com.nexora.registration.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Jeu de démonstration (registre vide, REGISTRATION_DEMO=true) : trois opérateurs aux NIU fictifs, leurs pièces
 * d'enrôlement (documents factices marqués « DÉMONSTRATION ») et le rattachement des comptes de test
 * (test.operateur titulaire, test.cda mandataire). Certaines données sont volontairement absentes pour illustrer la
 * complétion du registre à la validation d'une procédure (RG2 UC-05 FIMEX).
 */
@Component
public class Demonstration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Demonstration.class);

    private final JdbcClient jdbc;
    private final boolean actif;

    public Demonstration(JdbcClient jdbc, @Value("${nexora.registration.demo:true}") boolean actif) {
        this.jdbc = jdbc;
        this.actif = actif;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!actif || jdbc.sql("select count(*) from registre.party").query(Long.class).single() > 0) return;
        jdbc.sql("select set_config('nexora.user', 'systeme', true), set_config('nexora.channel', 'SEED', true), set_config('nexora.reason', 'Jeu de démonstration', true)")
                .query((rs, i) -> 1).single();
        jdbc.sql("""
                insert into registre.party (niu, legal_name, party_type_code, nationality, national_identifier, contact_person_name, birth_country,
                  street, po_box, city, country_code, email, phone, mobile, tax_card_from, tax_card_to, registration_number, activity_sector_code,
                  tax_regime_code, rccm, rccm_date, rccm_place, updated_by) values
                ('M051200012345K', 'SOCIETE CAMEROUNAISE D''IMPORT-EXPORT (SCIE) SA', 'PM', 'C', '112233445', 'Paul MBARGA', 'Cameroun',
                 'Rue Joss, Bonanjo', NULL, 'Douala', 'CM', 'contact@scie-demo.cm', '+237 233 42 00 00', '+237 699 00 00 01', '2026-01-02', '2026-12-31',
                 'PAT-DLA-2026-0457', '4690', 'REE', 'RC/DLA/2015/B/1234', '2015-03-12', 'Douala', 'systeme'),
                ('P078500098765A', 'NDJOCK Jean-Paul', 'PP', 'C', '998877665', 'Jean-Paul NDJOCK', 'Cameroun',
                 'Avenue Kennedy', 'BP 1520', 'Yaoundé', 'CM', 'jp.ndjock@exemple-demo.cm', '+237 222 20 00 00', '+237 677 00 00 02', '2026-01-05', '2026-12-31',
                 'PAT-YDE-2026-1189', '4630', 'SIM', 'RC/YAO/2019/A/567', '2019-09-30', 'Yaoundé', 'systeme'),
                ('M110900055501Z', 'AFRICA TRADING SARL', 'SUC', 'E', 'P-CI-778899', 'Awa KONE', 'Côte d''Ivoire',
                 'Boulevard de la Liberté, Akwa', 'BP 4410', 'Douala', 'CM', 'direction@africa-trading-demo.cm', '+237 233 43 00 00', '+237 690 00 00 03', '2026-02-01', '2027-01-31',
                 'PAT-DLA-2026-0901', '4610', 'REE', 'RC/DLA/2022/B/9876', '2022-06-15', 'Douala', 'systeme')
                """).update();
        record Piece(String niu, String code, String libelle) {}
        List<Piece> pieces = new ArrayList<>();
        for (String niu : List.of("M051200012345K", "P078500098765A", "M110900055501Z")) {
            pieces.add(new Piece(niu, "P2", "Copie certifiée conforme de la patente"));
            pieces.add(new Piece(niu, "P3", "Attestation d'immatriculation au RCCM"));
            pieces.add(new Piece(niu, "P4", "Attestation de numéro d'identification unique"));
            pieces.add(new Piece(niu, "P5", niu.startsWith("M11") ? "Carte de séjour du gérant" : "Carte nationale d'identité du dirigeant"));
            pieces.add(new Piece(niu, "P6", "Attestation de soumission CNPS"));
        }
        pieces.add(new Piece("M110900055501Z", "P7", "Agrément à l'exercice de l'activité commerciale (étranger)"));
        pieces.add(new Piece("M110900055501Z", "P8", "Arrêté de dispense (succursale)"));
        for (Piece p : pieces) {
            byte[] pdf = pdf(p.libelle() + " — NIU " + p.niu(), "DOCUMENT DE DEMONSTRATION - SANS VALEUR LEGALE");
            jdbc.sql("""
                    insert into registre.party_document (niu, doc_code, label, file_name, mime_type, size_bytes, sha256, content, uploaded_by)
                    values (:n, :c, :l, :f, 'application/pdf', :s, :h, :b, 'systeme')
                    """).param("n", p.niu()).param("c", p.code()).param("l", p.libelle()).param("f", p.code() + "-" + p.niu() + ".pdf")
                    .param("s", pdf.length).param("h", sha256(pdf)).param("b", pdf).update();
        }
        jdbc.sql("""
                insert into registre.party_user (niu, username, relation, created_by) values
                ('M051200012345K', 'test.operateur', 'TITULAIRE', 'systeme'),
                ('P078500098765A', 'test.cda', 'MANDATAIRE', 'systeme'),
                ('M110900055501Z', 'test.cda', 'MANDATAIRE', 'systeme')
                """).update();
        log.info("Registre : jeu de démonstration chargé (3 opérateurs fictifs, {} pièces).", pieces.size());
    }

    /** PDF minimal d'une page (texte Helvetica), écrit à la main pour éviter toute dépendance. */
    static byte[] pdf(String ligne1, String ligne2) {
        String texte = "BT /F1 14 Tf 60 760 Td (" + echapper(ligne1) + ") Tj 0 -28 Td /F1 11 Tf (" + echapper(ligne2) + ") Tj ET";
        List<String> objets = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
                "<< /Length " + texte.getBytes(StandardCharsets.ISO_8859_1).length + " >>\nstream\n" + texte + "\nendstream");
        StringBuilder s = new StringBuilder("%PDF-1.4\n");
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < objets.size(); i++) {
            positions.add(s.toString().getBytes(StandardCharsets.ISO_8859_1).length);
            s.append(i + 1).append(" 0 obj\n").append(objets.get(i)).append("\nendobj\n");
        }
        int xref = s.toString().getBytes(StandardCharsets.ISO_8859_1).length;
        s.append("xref\n0 ").append(objets.size() + 1).append("\n0000000000 65535 f \n");
        for (int p : positions) s.append(String.format("%010d 00000 n \n", p));
        s.append("trailer\n<< /Size ").append(objets.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return s.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String echapper(String s) {
        return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
