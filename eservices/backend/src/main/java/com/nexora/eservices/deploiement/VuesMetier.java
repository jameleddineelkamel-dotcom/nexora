package com.nexora.eservices.deploiement;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Vues métier d'une procédure déployée (schéma {@code proc_<code>}) : la demande (champs simples), une vue par groupe
 * répétitif (ex. produits), l'avis de paiement et le livrable — fidèles au modèle de données métier (ex. DM FIMEX v1.1)
 * tout en restant alimentées par les tables génériques du Runtime. Les noms de vues se règlent dans le paquet
 * (réglage « viewNames »), les colonnes sont les codes de champs en snake_case.
 */
@Component
public class VuesMetier {

    private final JdbcClient jdbc;

    public VuesMetier(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> creer(Paquet p) {
        String code = p.procedure().code().toLowerCase(Locale.ROOT);
        String schema = "proc_" + code;
        Map<String, String> noms = noms(p, code);
        verifierIdentifiant(schema);
        jdbc.sql("create schema if not exists " + schema).update();
        List<String> vues = new ArrayList<>();

        // Demande : champs non répétitifs
        StringBuilder s = new StringBuilder("create or replace view ").append(schema).append('.').append(noms.get("request")).append(" as select\n")
                .append("  d.dossier_id as request_id, d.dossier_no as request_number, d.request_type as request_type_code, d.request_nature,\n")
                .append("  d.parent_dossier_no as source_request_number, d.applicant_niu, d.status as status_code, d.lifecycle::text as lifecycle,\n")
                .append("  d.processing_location, d.admin_unit, d.assigned_agent, d.signatory, d.total_amount, d.currency, d.version_no as procedure_version,\n");
        Set<String> prises = new HashSet<>(Set.of("request_id", "request_number", "request_type_code", "request_nature", "source_request_number",
                "applicant_niu", "status_code", "lifecycle", "processing_location", "admin_unit", "assigned_agent", "signatory", "total_amount", "currency",
                "procedure_version", "active_indicator", "created_date", "updated_date", "created_by"));
        Set<String> sectionsRepetees = new HashSet<>();
        p.sections().stream().filter(Paquet.Section::repeating).forEach(x -> sectionsRepetees.add(x.code()));
        for (Paquet.Field f : p.fields())
            // un champ dont la colonne existe déjà (ex. processing_location) n'est pas dupliqué
            if (!sectionsRepetees.contains(f.sectionCode()) && prises.add(snake(f.code()))) s.append("  ").append(colonne("d.form_data", f)).append(",\n");
        s.append("  d.lifecycle <> 'CANCELLED' as active_indicator, d.created_at as created_date, d.updated_at as updated_date,\n")
                .append("  d.submitted_by as created_by\n  from wfe.dossier d where d.procedure_code = '").append(p.procedure().code()).append("' and d.dossier_no is not null");
        jdbc.sql("drop view if exists " + schema + "." + noms.get("request") + " cascade").update();
        jdbc.sql(s.toString()).update();
        vues.add(schema + "." + noms.get("request"));

        // Groupes répétitifs
        for (String section : sectionsRepetees) {
            String nom = noms.getOrDefault(section, code + "_" + section.toLowerCase(Locale.ROOT));
            verifierIdentifiant(nom);
            StringBuilder r = new StringBuilder("create or replace view ").append(schema).append('.').append(nom).append(" as select\n")
                    .append("  d.dossier_id as request_id, d.dossier_no as request_number, l.ord::int as line_no,\n");
            Set<String> prisesLigne = new HashSet<>(Set.of("request_id", "request_number", "line_no", "active_indicator", "created_date", "created_by"));
            for (Paquet.Field f : p.champsDe(section)) if (prisesLigne.add(snake(f.code()))) r.append("  ").append(colonne("l.valeur", f)).append(",\n");
            r.append("  d.lifecycle <> 'CANCELLED' as active_indicator, d.created_at as created_date, d.submitted_by as created_by\n")
                    .append("  from wfe.dossier d cross join lateral jsonb_array_elements(coalesce(d.form_data -> '").append(section)
                    .append("', '[]'::jsonb)) with ordinality as l(valeur, ord)\n  where d.procedure_code = '").append(p.procedure().code())
                    .append("' and d.dossier_no is not null");
            jdbc.sql("drop view if exists " + schema + "." + nom + " cascade").update();
            jdbc.sql(r.toString()).update();
            vues.add(schema + "." + nom);
        }

        // Avis de paiement
        String avis = noms.get("paymentNotice");
        jdbc.sql("drop view if exists " + schema + "." + avis + " cascade").update();
        jdbc.sql("create or replace view " + schema + "." + avis + " " + """
                 as select n.notice_id as payment_notice_id, d.dossier_id as request_id, d.dossier_no as request_number,
                  n.status as payment_notice_status_code, n.payee as payee_id, n.notice_no as payment_notice_number,
                  n.payment_reference as payment_reference_text, n.payer_name as payment_noticee_name, n.issued_at as payment_notice_datetime,
                  n.total_amount as total_payment_notice_amount, n.currency as payment_notice_currency_code, n.paid_at, n.bank_code,
                  n.status not in ('CANCELLED') as active_indicator
                  from wfe.payment_notice n join wfe.dossier d on d.dossier_id = n.dossier_id
                 where d.procedure_code = '""" + p.procedure().code() + "'").update();
        vues.add(schema + "." + avis);

        // Livrable (attestation)
        String livrable = noms.get("certificate");
        jdbc.sql("drop view if exists " + schema + "." + livrable + " cascade").update();
        jdbc.sql("create or replace view " + schema + "." + livrable + " " + """
                 as select c.document_id as certificate_id, d.dossier_id as request_id, d.dossier_no as request_number,
                  c.base_doc_no as document_reference_number, c.doc_no as document_number, c.version_no as document_version_id,
                  c.holder_niu as tax_identifier, c.holder_name as legal_name, d.form_data ->> 'activitySectorCode' as activity_sector_code,
                  d.form_data ->> 'cityName' as city_name, d.form_data ->> 'streetAndNumber' as street_and_number,
                  c.signed_at as signed_datetime, c.valid_from, c.valid_until as expiration_datetime, c.qr_text as qr_code_text,
                  c.seal_text as electronic_seal_text, '/api/v1/documents/' || c.doc_no || '/pdf' as file_uri_identifier,
                  c.signed_by, c.status = 'ACTIVE' as active_indicator
                  from wfe.issued_document c join wfe.dossier d on d.dossier_id = c.dossier_id
                 where d.procedure_code = '""" + p.procedure().code() + "'").update();
        vues.add(schema + "." + livrable);
        return vues;
    }

    private static Map<String, String> noms(Paquet p, String code) {
        Map<String, String> n = new HashMap<>(Map.of("request", code + "_request", "paymentNotice", code + "_payment_notice",
                "certificate", code + "_certificate"));
        Object reglage = p.settings() == null ? null : p.settings().get("viewNames");
        if (reglage instanceof Map<?, ?> m) m.forEach((k, v) -> n.put(String.valueOf(k), String.valueOf(v).toLowerCase(Locale.ROOT)));
        n.values().forEach(VuesMetier::verifierIdentifiant);
        return n;
    }

    /** Colonne typée d'un champ (texte, nombre, date, entier). */
    private static String colonne(String source, Paquet.Field f) {
        String brut = source + " ->> '" + f.code() + "'";
        String expr = switch (f.dataType()) {
            case "NUMBER", "DECIMAL" -> "wfe.en_nombre(" + brut + ")";
            case "DATE" -> "wfe.en_date(" + brut + ")";
            case "YEAR" -> "wfe.en_entier(" + brut + ")";
            case "BOOLEAN" -> "(" + brut + ")::boolean";
            default -> brut;
        };
        return expr + " as " + snake(f.code());
    }

    public static String snake(String code) {
        String s = code.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        verifierIdentifiant(s);
        return s;
    }

    private static void verifierIdentifiant(String s) {
        if (!s.matches("^[a-z][a-z0-9_]{0,62}$")) throw new IllegalArgumentException("Identifiant SQL invalide : " + s);
    }
}
