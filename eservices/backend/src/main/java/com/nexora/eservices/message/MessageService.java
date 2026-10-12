package com.nexora.eservices.message;

import com.nexora.eservices.audit.Journal;
import com.nexora.eservices.deploiement.DeploiementService;
import com.nexora.eservices.deploiement.Paquet;
import com.nexora.eservices.dossier.Dossier;
import com.nexora.eservices.security.Habilitations;
import com.nexora.eservices.web.EservicesException;
import com.nexora.eservices.web.Json;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Messages échangés (CASE_MESSAGE §10.12) : chaque message est l'occurrence d'un message du catalogue publié, conforme à
 * son usage dans un flux (émetteur, destinataire, RG-TRX-02). Le contenu (JSON, XML, CSV ou TXT) est stocké à part avec
 * son empreinte SHA-256 ; seules les valeurs des champs indexables sont copiées dans payload_value (RG-TRX-04) ; l'état
 * suit les transitions autorisées et la clé d'idempotence élimine les doublons (AC-AG-03).
 */
@Service
public class MessageService {

    /** Traitement métier d'un message entrant (ex. confirmation de paiement) ; renvoie le dossier concerné. */
    public interface Traitement {
        boolean accepte(Paquet.MessageDef def, Map<String, List<String>> valeurs);

        UUID traiter(UUID messageId, Paquet.MessageDef def, Map<String, List<String>> valeurs);
    }

    public record Entete(UUID id, UUID dossierId, String dossierNo, String flowCode, Integer flowSeq, String code, String type, String direction,
                         String sender, String recipient, String format, String contentType, String state, UUID correlationId, UUID refTo,
                         String idempotencyKey, Integer length, String sha256, Map<String, Object> validation, String observation, String createdBy,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    private final JdbcClient jdbc;
    private final Json json;
    private final Journal journal;
    private final DeploiementService deploiements;
    private final ObjectProvider<List<Traitement>> traitements;
    private final String tenant;

    public MessageService(JdbcClient jdbc, Json json, Journal journal, DeploiementService deploiements, ObjectProvider<List<Traitement>> traitements,
                          @Value("${nexora.tenant}") String tenant) {
        this.jdbc = jdbc;
        this.json = json;
        this.journal = journal;
        this.deploiements = deploiements;
        this.traitements = traitements;
        this.tenant = tenant;
    }

    // ------------------------------------------------------------------ émission
    /**
     * Émet le message {@code code} du catalogue pour un dossier. Valeurs : champs du formulaire (fieldCode) ou, pour les
     * éléments d'enveloppe, la clé du chemin dans {@code contexte} (valeur simple ou liste).
     */
    public UUID emettre(Dossier d, String code, Map<String, Object> contexte, String observation, UUID refTo, List<Long> pieces) {
        return emettre(deploiements.paquet(d.deploymentId()), d, code, contexte, observation, refTo, pieces);
    }

    /** Message sans dossier (relevé périodique, rapport) : les valeurs viennent toutes du contexte. */
    public UUID emettreRapport(Paquet p, String code, Map<String, Object> contexte, String observation) {
        return emettre(p, null, code, contexte, observation, null, null);
    }

    private UUID emettre(Paquet p, Dossier d, String code, Map<String, Object> contexte, String observation, UUID refTo, List<Long> pieces) {
        Paquet.MessageDef def = p.message(code).orElseThrow(() -> new IllegalStateException("Message absent du catalogue publié : " + code));
        Paquet.Usage usage = p.usage(code).orElseThrow(() -> new IllegalStateException("Message utilisé par aucun flux (RG-TRX-02) : " + code));
        Map<String, List<String>> valeurs = valeurs(p, def, d, contexte);
        Map<String, String> libelles = new LinkedHashMap<>();
        libelles.put("CUR", d == null ? "" : Objects.toString(d.dossierNo(), ""));
        libelles.put("Procédure", p.procedure().code() + " v" + p.versionNo());
        libelles.put("Émetteur", usage.message().senderActor());
        libelles.put("Destinataire", usage.message().receiverActor());
        byte[] contenu = Formats.ecrire(def, valeurs, libelles, def.labelFr() + (d == null ? "" : " — " + Objects.toString(d.dossierNo(), "")), json.mapper());
        List<String> erreurs = new ArrayList<>();
        for (Paquet.MessageField f : def.fields())
            if (f.mandatory() && valeurs.getOrDefault(f.path(), List.of()).stream().allMatch(v -> v == null || v.isBlank()))
                erreurs.add("Champ obligatoire vide : " + f.path());
        Long flux = d == null ? null : flux(d.id(), usage.flow());
        Long emetteur = partenaire(usage.message().senderActor(), p, d), destinataire = partenaire(usage.message().receiverActor(), p, d);
        UUID id = jdbc.sql("""
                insert into wfe.message (tenant_id, flux_id, dossier_id, dossier_no, message_code, message_type, flow_seq, direction, sender_actor,
                  recipient_actor, sender_partner_id, recipient_partner_id, content_format, content_type, state_code, ref_to_message_id,
                  idempotency_key, content_length, content_sha256, validation, observation, created_by, expires_at)
                values (:t, :f, :d, :n, :c, :ty, :s, 'OUT', :se, :re, :sp, :rp, :fmt, :ct, 'OUT_CREATED', :ref, :ik, :len, :h, cast(:v as jsonb), :o, :u, :exp)
                returning message_id
                """).param("t", tenant).param("f", flux).param("d", d == null ? null : d.id()).param("n", d == null ? null : d.dossierNo()).param("c", code).param("ty", def.messageType())
                .param("s", usage.message().seq()).param("se", usage.message().senderActor()).param("re", usage.message().receiverActor())
                .param("sp", emetteur).param("rp", destinataire).param("fmt", def.contentFormat()).param("ct", Formats.typeMime(def.contentFormat()))
                .param("ref", refTo).param("ik", (d == null ? p.procedure().code() : Objects.toString(d.dossierNo(), d.id().toString())) + ":" + code + ":" + UUID.randomUUID())
                .param("len", contenu.length).param("h", sha256(contenu)).param("v", json.ecrire(Map.of("valid", erreurs.isEmpty(), "errors", erreurs)))
                .param("o", observation == null ? null : observation.length() > 1500 ? observation.substring(0, 1500) : observation)
                .param("u", Habilitations.acteur())
                .param("exp", usage.message().timeoutHours() == null ? null : OffsetDateTime.now().plusHours(usage.message().timeoutHours()))
                .query(UUID.class).single();
        contenu(id, contenu);
        indexer(id, p, def, valeurs);
        if (pieces != null) pieces.forEach(a -> jdbc.sql("insert into wfe.message_attachment values (:m, :a) on conflict do nothing").param("m", id).param("a", a).update());
        // Transport interne : remise immédiate au destinataire (eFORCE, eGOV, e-PAYMENT) ; contenu invalide → erreur d'émission
        etat(id, erreurs.isEmpty() ? "OUT_SENT" : "OUT_ERROR", erreurs.isEmpty() ? "Remis au destinataire " + usage.message().receiverActor() : String.join(" ; ", erreurs));
        journal.tracer("MESSAGE_EMIS", d == null ? null : d.id(), d == null ? null : d.dossierNo(), Map.of("message", code, "format", def.contentFormat(), "id", id.toString(),
                "flow", usage.flow().code(), "valid", erreurs.isEmpty()));
        return id;
    }

    /** Valeurs de chaque chemin du message : formulaire (y compris groupes répétitifs) ou contexte d'enveloppe. */
    private static Map<String, List<String>> valeurs(Paquet p, Paquet.MessageDef def, Dossier d, Map<String, Object> contexte) {
        Map<String, List<String>> r = new LinkedHashMap<>();
        for (Paquet.MessageField f : def.fields()) {
            List<String> v = new ArrayList<>();
            if (d != null && f.fieldCode() != null && !f.fieldCode().isBlank()) {
                Optional<Paquet.Field> champ = p.champ(f.fieldCode());
                Optional<Paquet.Section> s = champ.flatMap(c -> p.section(c.sectionCode()));
                if (s.isPresent() && s.get().repeating()) {
                    if (d.formData().get(s.get().code()) instanceof List<?> l)
                        for (Object o : l) v.add(o instanceof Map<?, ?> m && m.get(f.fieldCode()) != null ? String.valueOf(m.get(f.fieldCode())) : "");
                } else if (d.formData().get(f.fieldCode()) != null) v.add(String.valueOf(d.formData().get(f.fieldCode())));
            } else {
                Object x = contexte == null ? null : contexte.get(Formats.cle(f.path(), def.rootElement()));
                if (x instanceof Collection<?> c) c.forEach(o -> v.add(o == null ? "" : String.valueOf(o)));
                else if (x != null) v.add(String.valueOf(x));
            }
            r.put(f.path(), v);
        }
        return r;
    }

    // ------------------------------------------------------------------ réception
    /** Message entrant (banque, administration partenaire) : contrôle, stockage, extraction, traitement métier. */
    @Transactional
    public Entete recevoir(String code, byte[] contenu, String cleIdempotence, String partenaire) {
        journal.contexte();
        if (cleIdempotence != null && !cleIdempotence.isBlank()) {
            Optional<UUID> deja = jdbc.sql("select message_id from wfe.message where tenant_id = :t and idempotency_key = :k")
                    .param("t", tenant).param("k", cleIdempotence).query(UUID.class).optional();
            if (deja.isPresent()) return entete(deja.get());   // élimination des doublons (eb:DuplicateElimination)
        }
        Paquet p = null;
        for (DeploiementService.Deploiement dep : deploiements.actifs()) {
            Paquet x = deploiements.paquet(dep.id());
            if (x.message(code).isPresent()) { p = x; break; }
        }
        if (p == null) throw EservicesException.introuvable("Message inconnu de toutes les procédures déployées : " + code);
        Paquet.MessageDef def = p.message(code).orElseThrow();
        Paquet.Usage usage = p.usage(code).orElseThrow(() -> EservicesException.invalide("Message utilisé par aucun flux : " + code));
        Map<String, List<String>> valeurs;
        List<String> erreurs = new ArrayList<>();
        try {
            valeurs = Formats.lire(def, contenu, json.mapper());
        } catch (RuntimeException e) {
            valeurs = Map.of();
            erreurs.add("Contenu " + def.contentFormat() + " illisible : " + e.getMessage());
        }
        for (Paquet.MessageField f : def.fields())
            if (f.mandatory() && valeurs.getOrDefault(f.path(), List.of()).stream().allMatch(v -> v == null || v.isBlank()))
                erreurs.add("Champ obligatoire absent : " + f.path());
        Long emetteur = partenaireExterne(partenaire, usage.message().senderActor(), p);
        UUID id = jdbc.sql("""
                insert into wfe.message (tenant_id, message_code, message_type, flow_seq, direction, sender_actor, recipient_actor, sender_partner_id,
                  content_format, content_type, state_code, idempotency_key, content_length, content_sha256, validation, created_by)
                values (:t, :c, :ty, :s, 'IN', :se, :re, :sp, :fmt, :ct, 'IN_RECEIVED', :ik, :len, :h, cast(:v as jsonb), :u) returning message_id
                """).param("t", tenant).param("c", code).param("ty", def.messageType()).param("s", usage.message().seq())
                .param("se", usage.message().senderActor()).param("re", usage.message().receiverActor()).param("sp", emetteur)
                .param("fmt", def.contentFormat()).param("ct", Formats.typeMime(def.contentFormat()))
                .param("ik", cleIdempotence == null || cleIdempotence.isBlank() ? null : cleIdempotence).param("len", contenu.length)
                .param("h", sha256(contenu)).param("v", json.ecrire(Map.of("valid", erreurs.isEmpty(), "errors", erreurs)))
                .param("u", Habilitations.acteur()).query(UUID.class).single();
        contenu(id, contenu);
        if (!erreurs.isEmpty()) {
            etat(id, "IN_REJECTED", String.join(" ; ", erreurs));
            return entete(id);
        }
        etat(id, "IN_VALID", null);
        indexer(id, p, def, valeurs);
        UUID dossier = null;
        String motifRejet = null;
        for (Traitement t : traitements.getIfAvailable(List::of)) {
            if (!t.accepte(def, valeurs)) continue;
            try {
                dossier = t.traiter(id, def, valeurs);
            } catch (EservicesException e) {
                motifRejet = String.join(" ", e.getErreurs());
            }
            break;
        }
        if (dossier != null) {
            String no = jdbc.sql("select dossier_no from wfe.dossier where dossier_id = :d").param("d", dossier).query(String.class).single();
            long flux = flux(dossier, usage.flow());
            jdbc.sql("update wfe.message set dossier_id = :d, dossier_no = :n, flux_id = :f where message_id = :m")
                    .param("d", dossier).param("n", no).param("f", flux).param("m", id).update();
            etat(id, "IN_PROCESSED", "Traité");
            journal.tracer("MESSAGE_RECU", dossier, no, Map.of("message", code, "format", def.contentFormat(), "id", id.toString()));
        } else etat(id, "IN_REJECTED", motifRejet == null ? "Aucun dossier ne correspond à ce message." : motifRejet);
        return entete(id);
    }

    // ------------------------------------------------------------------ consultation
    private static final String COLONNES = """
            m.message_id, m.dossier_id, m.dossier_no, f.flow_code, m.flow_seq, m.message_code, m.message_type, m.direction::text, m.sender_actor,
            m.recipient_actor, m.content_format, m.content_type, m.state_code, m.correlation_id, m.ref_to_message_id, m.idempotency_key,
            m.content_length, m.content_sha256, m.validation::text, m.observation, m.created_by, m.created_at, m.updated_at
            """;

    private Entete ligne(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entete(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), (Integer) rs.getObject(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getString(13), rs.getObject(14, UUID.class), rs.getObject(15, UUID.class), rs.getString(16), (Integer) rs.getObject(17),
                rs.getString(18), json.map(rs.getString(19)), rs.getString(20), rs.getString(21), rs.getObject(22, OffsetDateTime.class),
                rs.getObject(23, OffsetDateTime.class));
    }

    public Entete entete(UUID id) {
        return jdbc.sql("select " + COLONNES + " from wfe.message m left join wfe.flux f on f.flux_id = m.flux_id where m.message_id = :i")
                .param("i", id).query((rs, i) -> ligne(rs)).optional().orElseThrow(() -> EservicesException.introuvable("Message introuvable."));
    }

    public List<Entete> duDossier(UUID dossier) {
        return jdbc.sql("select " + COLONNES + " from wfe.message m left join wfe.flux f on f.flux_id = m.flux_id where m.dossier_id = :d order by m.created_at")
                .param("d", dossier).query((rs, i) -> ligne(rs)).list();
    }

    public List<Entete> recents(String code, String format, String etat, int limite) {
        return jdbc.sql("select " + COLONNES + """
                 from wfe.message m left join wfe.flux f on f.flux_id = m.flux_id
                 where (cast(:c as text) is null or m.message_code = :c) and (cast(:fm as text) is null or m.content_format = :fm)
                   and (cast(:e as text) is null or m.state_code = :e)
                 order by m.created_at desc limit :l
                """).param("c", vide(code)).param("fm", vide(format)).param("e", vide(etat)).param("l", Math.min(Math.max(limite, 1), 1000))
                .query((rs, i) -> ligne(rs)).list();
    }

    public byte[] contenu(UUID id) {
        return jdbc.sql("select payload from wfe.message_content where message_id = :i").param("i", id).query(byte[].class).optional()
                .orElseThrow(() -> EservicesException.introuvable("Contenu introuvable."));
    }

    public List<Map<String, Object>> valeursIndexees(UUID id) {
        return jdbc.sql("select path, occurrence, field_code, value_text, value_num from wfe.payload_value where message_id = :i order by path, occurrence")
                .param("i", id).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", rs.getString(1));
                    m.put("occurrence", rs.getInt(2));
                    m.put("fieldCode", rs.getString(3));
                    m.put("valueText", rs.getString(4));
                    m.put("valueNum", rs.getBigDecimal(5));
                    return m;
                }).list();
    }

    public List<Map<String, Object>> historiqueEtats(UUID id) {
        return jdbc.sql("select from_state, to_state, changed_at, changed_by, reason from wfe.message_state_history where message_id = :i order by history_id")
                .param("i", id).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("from", rs.getString(1));
                    m.put("to", rs.getString(2));
                    m.put("at", rs.getObject(3, OffsetDateTime.class));
                    m.put("by", rs.getString(4));
                    m.put("reason", rs.getString(5));
                    return m;
                }).list();
    }

    // ------------------------------------------------------------------ utilitaires
    private long flux(UUID dossier, Paquet.Flow f) {
        return jdbc.sql("""
                insert into wfe.flux (dossier_id, flow_code, label) values (:d, :c, :l)
                on conflict (dossier_id, flow_code) do update set label = excluded.label returning flux_id
                """).param("d", dossier).param("c", f.code()).param("l", f.labelFr()).query(Long.class).single();
    }

    private void contenu(UUID id, byte[] contenu) {
        jdbc.sql("insert into wfe.message_content (message_id, created_at, payload) values (:i, now(), :p)").param("i", id).param("p", contenu).update();
    }

    private void indexer(UUID id, Paquet p, Paquet.MessageDef def, Map<String, List<String>> valeurs) {
        for (Paquet.MessageField f : def.fields()) {
            if (!f.indexed()) continue;
            int o = 0;
            for (String v : valeurs.getOrDefault(f.path(), List.of())) {
                o++;
                if (v == null || v.isBlank()) continue;
                BigDecimal n = null;
                try { if (v.matches("^-?\\d+(\\.\\d+)?$")) n = new BigDecimal(v); } catch (NumberFormatException ignore) { /* texte */ }
                jdbc.sql("insert into wfe.payload_value (message_id, path, occurrence, field_code, value_text, value_num) values (:m, :p, :o, :f, :v, :n) on conflict do nothing")
                        .param("m", id).param("p", f.path()).param("o", o).param("f", f.fieldCode()).param("v", v).param("n", n).update();
            }
        }
    }

    public void etat(UUID id, String etat, String motif) {
        jdbc.sql("select set_config('nexora.reason', :r, true)").param("r", motif == null ? "" : motif).query((rs, i) -> 1).single();
        jdbc.sql("update wfe.message set state_code = :s where message_id = :i").param("s", etat).param("i", id).update();
    }

    /** Partenaire d'échange d'un rôle : l'opérateur demandeur (par NIU) ou l'organisme du même code. */
    private Long partenaire(String acteur, Paquet p, Dossier d) {
        String type = p.acteur(acteur).map(Paquet.Actor::kind).orElse("SYSTEME");
        if ("DEMANDEUR".equals(type) && d != null && d.applicantNiu() != null)
            return jdbc.sql("""
                    insert into wfe.partner (tenant_id, edi_id, edi_id_qualifier, name, partner_type_code) values (:t, :e, 'NIU', :n, 'DEMANDEUR')
                    on conflict (tenant_id, edi_id_qualifier, edi_id) do update set name = excluded.name returning partner_id
                    """).param("t", tenant).param("e", d.applicantNiu()).param("n", Objects.toString(d.applicantName(), d.applicantNiu()))
                    .query(Long.class).single();
        String typePartenaire = List.of("ADMINISTRATION", "PARTENAIRE", "BANQUE", "SYSTEME").contains(type) ? type : "SYSTEME";
        String nom = p.acteur(acteur).map(Paquet.Actor::labelFr).orElse(acteur);
        return jdbc.sql("""
                insert into wfe.partner (tenant_id, edi_id, name, partner_type_code) values (:t, :e, :n, :ty)
                on conflict (tenant_id, edi_id_qualifier, edi_id) do update set name = wfe.partner.name returning partner_id
                """).param("t", tenant).param("e", acteur).param("n", nom).param("ty", typePartenaire).query(Long.class).single();
    }

    private Long partenaireExterne(String code, String acteur, Paquet p) {
        if (code == null || code.isBlank()) return null;
        String type = p.acteur(acteur).map(Paquet.Actor::kind).filter(t -> List.of("ADMINISTRATION", "PARTENAIRE", "BANQUE", "SYSTEME").contains(t)).orElse("PARTENAIRE");
        return jdbc.sql("""
                insert into wfe.partner (tenant_id, edi_id, name, partner_type_code) values (:t, :e, :e, :ty)
                on conflict (tenant_id, edi_id_qualifier, edi_id) do update set is_active = true returning partner_id
                """).param("t", tenant).param("e", code.strip().toUpperCase(Locale.ROOT)).param("ty", type).query(Long.class).single();
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String texte(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
