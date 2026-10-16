package com.nexora.eservices.dossier;

import com.nexora.eservices.stockage.StockageObjet;
import com.nexora.eservices.web.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

/** Accès aux dossiers, à leurs critères, à leur historique de statuts et à leurs pièces jointes. */
@Repository
public class DossierRepository {

    public record Piece(long id, String docCode, String fileName, String mimeType, long sizeBytes, String sha256, String source,
                        String uploadedBy, OffsetDateTime uploadedAt) {}

    public record Statut(long id, String fromStatus, String toStatus, String activityId, String actor, String reason, OffsetDateTime occurredAt) {}

    static final String COLONNES = """
            dossier_id, dossier_no, deployment_id, procedure_code, version_no, request_type, request_nature, parent_dossier_no, applicant_niu,
            applicant_name, submitted_by, on_behalf, status, lifecycle::text, processing_location, admin_unit, assigned_agent, signatory,
            process_instance_id, form_data::text, previous_data::text, search_criteria::text, fees::text, total_amount, currency, last_observation,
            sla_due_at, created_at, submitted_at, updated_at, closed_at
            """;

    private final JdbcClient jdbc;
    private final Json json;
    private final StockageObjet stockage;

    public DossierRepository(JdbcClient jdbc, Json json, StockageObjet stockage) {
        this.jdbc = jdbc;
        this.json = json;
        this.stockage = stockage;
    }

    Dossier ligne(ResultSet rs) throws SQLException {
        return new Dossier(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getInt(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getBoolean(12), rs.getString(13),
                rs.getString(14), rs.getString(15), rs.getString(16), rs.getString(17), rs.getString(18), rs.getString(19), json.map(rs.getString(20)),
                rs.getString(21) == null ? null : json.map(rs.getString(21)), json.map(rs.getString(22)),
                json.mapper().readValue(rs.getString(23), new TypeReference<List<Map<String, Object>>>() {}), rs.getBigDecimal(24), rs.getString(25),
                rs.getString(26), rs.getObject(27, OffsetDateTime.class), rs.getObject(28, OffsetDateTime.class), rs.getObject(29, OffsetDateTime.class),
                rs.getObject(30, OffsetDateTime.class), rs.getObject(31, OffsetDateTime.class));
    }

    public Optional<Dossier> parId(UUID id) {
        return jdbc.sql("select " + COLONNES + " from wfe.dossier where dossier_id = :i").param("i", id).query((rs, i) -> ligne(rs)).optional();
    }

    public Optional<Dossier> parNumero(String no) {
        return jdbc.sql("select " + COLONNES + " from wfe.dossier where dossier_no = :n").param("n", no).query((rs, i) -> ligne(rs)).optional();
    }

    /** Dossiers déposés du même demandeur pour la même procédure (antécédents des règles). */
    public List<Dossier> duDemandeur(String niu, String procedure, UUID sauf) {
        if (niu == null) return List.of();
        return jdbc.sql("select " + COLONNES + " from wfe.dossier where applicant_niu = :n and procedure_code = :p and dossier_no is not null and dossier_id <> :s order by submitted_at")
                .param("n", niu).param("p", procedure).param("s", sauf).query((rs, i) -> ligne(rs)).list();
    }

    public UUID creer(long deploiement, String procedure, int version, String type, String nature, String parent, String niu, String nom,
                      String auteur, boolean pourTiers, Map<String, Object> donnees, Map<String, Object> precedentes, String tenant) {
        return jdbc.sql("""
                insert into wfe.dossier (tenant_id, deployment_id, procedure_code, version_no, request_type, request_nature, parent_dossier_no,
                  applicant_niu, applicant_name, submitted_by, on_behalf, form_data, previous_data)
                values (:t, :d, :p, :v, :ty, :n, :par, :niu, :nom, :u, :ob, cast(:f as jsonb), cast(:pr as jsonb)) returning dossier_id
                """).param("t", tenant).param("d", deploiement).param("p", procedure).param("v", version).param("ty", type).param("n", nature)
                .param("par", parent).param("niu", niu).param("nom", nom).param("u", auteur).param("ob", pourTiers)
                .param("f", json.ecrire(donnees)).param("pr", precedentes == null ? null : json.ecrire(precedentes)).query(UUID.class).single();
    }

    public void donnees(UUID id, Map<String, Object> donnees, String niu, String nom) {
        jdbc.sql("update wfe.dossier set form_data = cast(:f as jsonb), applicant_niu = coalesce(:n, applicant_niu), applicant_name = coalesce(:nom, applicant_name), updated_at = now() where dossier_id = :i")
                .param("f", json.ecrire(donnees)).param("n", niu).param("nom", nom).param("i", id).update();
    }

    public void deposer(UUID id, String cur, String lieu, List<Map<String, Object>> frais, java.math.BigDecimal total, String devise, String procInstance) {
        jdbc.sql("""
                update wfe.dossier set dossier_no = :c, lifecycle = 'ACTIVE', submitted_at = now(), processing_location = :l, fees = cast(:f as jsonb),
                       total_amount = :t, currency = :d, process_instance_id = :pi, updated_at = now() where dossier_id = :i
                """).param("c", cur).param("l", lieu).param("f", json.ecrire(frais)).param("t", total).param("d", devise).param("pi", procInstance)
                .param("i", id).update();
    }

    public void instance(UUID id, String procInstance) {
        jdbc.sql("update wfe.dossier set process_instance_id = :pi where dossier_id = :i").param("pi", procInstance).param("i", id).update();
    }

    /** Changement de statut historisé ; un statut final clôt le dossier (CANCELLED pour une annulation). */
    public void statut(UUID id, String statut, boolean fin, String activite, String acteur, String motif) {
        String precedent = jdbc.sql("select status from wfe.dossier where dossier_id = :i for update").param("i", id).query(String.class).single();
        if (statut.equals(precedent)) return;
        String cycle = !fin ? "ACTIVE" : statut.equals("ANNULE") ? "CANCELLED" : "CLOSED";
        jdbc.sql("""
                update wfe.dossier set status = :s, lifecycle = cast(:c as wfe.lifecycle), updated_at = now(),
                       closed_at = case when :fin then now() else null end where dossier_id = :i
                """).param("s", statut).param("c", cycle).param("fin", fin).param("i", id).update();
        jdbc.sql("insert into wfe.dossier_status_history (dossier_id, from_status, to_status, activity_id, actor, reason) values (:i, :f, :t, :a, :u, :m)")
                .param("i", id).param("f", precedent).param("t", statut).param("a", activite).param("u", acteur).param("m", motif).update();
    }

    public List<Statut> historique(UUID id) {
        return jdbc.sql("select history_id, from_status, to_status, activity_id, actor, reason, occurred_at from wfe.dossier_status_history where dossier_id = :i order by history_id")
                .param("i", id).query((rs, i) -> new Statut(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getObject(7, OffsetDateTime.class))).list();
    }

    public void criteres(UUID id, Map<String, List<String>> valeurs) {
        jdbc.sql("delete from wfe.dossier_criterion_value where dossier_id = :i").param("i", id).update();
        valeurs.forEach((code, liste) -> {
            int o = 0;
            for (String v : liste)
                if (v != null && !v.isBlank())
                    jdbc.sql("insert into wfe.dossier_criterion_value (dossier_id, criterion_code, occurrence, value_text) values (:i, :c, :o, :v) on conflict do nothing")
                            .param("i", id).param("c", code).param("o", ++o).param("v", v.length() > 1000 ? v.substring(0, 1000) : v).update();
        });
    }

    // ------------------------------------------------------------------ pièces jointes
    public List<Piece> pieces(UUID id) {
        return jdbc.sql("select attachment_id, doc_code, file_name, mime_type, size_bytes, sha256, source, uploaded_by, uploaded_at from wfe.attachment where dossier_id = :i and is_active order by doc_code, attachment_id")
                .param("i", id).query((rs, i) -> new Piece(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getObject(9, OffsetDateTime.class))).list();
    }

    public long ajouterPiece(UUID id, String code, String nom, String type, byte[] contenu, String empreinte, String source, String auteur) {
        // Une pièce remplace la précédente du même code (l'ancienne est conservée, inactive)
        jdbc.sql("update wfe.attachment set is_active = false where dossier_id = :i and doc_code = :c and is_active").param("i", id).param("c", code).update();
        // MinIO configuré : le fichier va dans le compartiment, la ligne n'en garde que la clé
        String cle = stockage.actif() ? stockage.deposer(clePiece(id, code, empreinte), contenu, type) : null;
        return jdbc.sql("""
                insert into wfe.attachment (dossier_id, doc_code, file_name, mime_type, size_bytes, sha256, content, storage_key, source, uploaded_by)
                values (:i, :c, :n, :t, :s, :h, :b, :k, :src, :u) returning attachment_id
                """).param("i", id).param("c", code).param("n", nom).param("t", type).param("s", contenu.length).param("h", empreinte)
                .param("b", cle == null ? contenu : null).param("k", cle).param("src", source).param("u", auteur).query(Long.class).single();
    }

    public void retirerPiece(UUID id, long piece) {
        jdbc.sql("update wfe.attachment set is_active = false where dossier_id = :i and attachment_id = :p").param("i", id).param("p", piece).update();
    }

    public Optional<Map.Entry<Piece, byte[]>> contenuPiece(UUID id, long piece) {
        return jdbc.sql("select attachment_id, doc_code, file_name, mime_type, size_bytes, sha256, source, uploaded_by, uploaded_at, content, storage_key from wfe.attachment where dossier_id = :i and attachment_id = :p")
                .param("i", id).param("p", piece).query((rs, i) -> Map.entry(new Piece(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getObject(9, OffsetDateTime.class)),
                        contenu(rs.getBytes(10), rs.getString(11)))).optional();
    }

    /** Contenu d'un fichier : en base, ou dans MinIO sous sa clé. */
    private byte[] contenu(byte[] enBase, String cle) {
        return enBase != null ? enBase : stockage.lire(cle);
    }

    /** Clé d'une pièce dans le compartiment : pieces/{dossier}/{code}-{empreinte}. */
    public static String clePiece(UUID dossier, String code, String empreinte) {
        return "pieces/" + dossier + "/" + code + "-" + empreinte;
    }
}
