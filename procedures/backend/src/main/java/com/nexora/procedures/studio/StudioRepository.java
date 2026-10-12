package com.nexora.procedures.studio;

import com.nexora.procedures.studio.Modele.*;
import com.nexora.procedures.web.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

/** Accès aux tables du schéma studio : lecture d'une version complète, remplacement d'une partie, copie de version. */
@Repository
public class StudioRepository {

    private static final TypeReference<List<CodeValue>> VALEURS = new TypeReference<>() {};
    private static final TypeReference<List<BusinessRule>> REGLES = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final Json json;

    public StudioRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ procédures et versions
    public List<ProcedureResume> procedures() {
        return jdbc.sql("""
                select p.code, p.label_fr, p.label_en, p.authority, c.code, c.label_fr, ph.code, ph.label_fr, tp.code, tp.label_fr, ph.bsp_area,
                       pub.id, pub.version_no, pub.published_at, tra.id, tra.version_no, tra.lifecycle_status::text,
                       (select count(*) from studio.procedure_version v where v.procedure_code = p.code)
                  from studio.procedure p
                  join studio.circuit c on c.code = p.circuit_code
                  join studio.process_phase ph on ph.code = c.phase_code
                  join studio.trade_process tp on tp.code = ph.process_code
                  left join studio.procedure_version pub on pub.procedure_code = p.code and pub.lifecycle_status = 'published'
                  left join studio.procedure_version tra on tra.procedure_code = p.code and tra.lifecycle_status in ('draft', 'in_review', 'approved')
                 order by p.code
                """).query((rs, i) -> new ProcedureResume(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
                (Long) rs.getObject(12), (Integer) rs.getObject(13), rs.getObject(14, OffsetDateTime.class), (Long) rs.getObject(15),
                (Integer) rs.getObject(16), rs.getString(17), rs.getInt(18))).list();
    }

    public Optional<Procedure> procedure(String code) {
        return jdbc.sql("select code, label_fr, label_en, circuit_code, authority, description, is_active, created_at, created_by from studio.procedure where code = :c")
                .param("c", code).query((rs, i) -> new Procedure(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getObject(8, OffsetDateTime.class), rs.getString(9))).optional();
    }

    public void creerProcedure(Procedure p, String auteur) {
        jdbc.sql("""
                insert into studio.procedure (code, label_fr, label_en, circuit_code, authority, description, created_by)
                values (:c, :f, :e, :ci, :a, :d, :u)
                """).param("c", p.code()).param("f", p.labelFr()).param("e", p.labelEn()).param("ci", p.circuitCode())
                .param("a", p.authority()).param("d", p.description()).param("u", auteur).update();
    }

    public void modifierProcedure(Procedure p) {
        jdbc.sql("""
                update studio.procedure set label_fr = :f, label_en = :e, circuit_code = :ci, authority = :a, description = :d, is_active = :ac
                 where code = :c
                """).param("c", p.code()).param("f", p.labelFr()).param("e", p.labelEn()).param("ci", p.circuitCode())
                .param("a", p.authority()).param("d", p.description()).param("ac", p.active()).update();
    }

    private static final String COLONNES_VERSION = """
            id, procedure_code, version_no, lifecycle_status::text, settings::text, change_note, created_at, created_by, updated_at, updated_by,
            submitted_at, submitted_by, approved_at, approved_by, published_at, published_by, retired_at, package_hash
            """;

    private Version version(ResultSet rs) throws SQLException {
        return new Version(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), json.map(rs.getString(5)), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class), rs.getString(8), rs.getObject(9, OffsetDateTime.class), rs.getString(10),
                rs.getObject(11, OffsetDateTime.class), rs.getString(12), rs.getObject(13, OffsetDateTime.class), rs.getString(14),
                rs.getObject(15, OffsetDateTime.class), rs.getString(16), rs.getObject(17, OffsetDateTime.class), rs.getString(18));
    }

    public Optional<Version> version(long id) {
        return jdbc.sql("select " + COLONNES_VERSION + " from studio.procedure_version where id = :id").param("id", id)
                .query((rs, i) -> version(rs)).optional();
    }

    public List<Version> versions(String procedure) {
        return jdbc.sql("select " + COLONNES_VERSION + " from studio.procedure_version where procedure_code = :p order by version_no desc")
                .param("p", procedure).query((rs, i) -> version(rs)).list();
    }

    public long creerVersion(String procedure, int numero, Map<String, Object> settings, String note, String auteur) {
        return jdbc.sql("""
                insert into studio.procedure_version (procedure_code, version_no, settings, change_note, created_by)
                values (:p, :n, cast(:s as jsonb), :c, :u) returning id
                """).param("p", procedure).param("n", numero).param("s", json.ecrire(settings)).param("c", note).param("u", auteur)
                .query(Long.class).single();
    }

    public void parametres(long id, Parametres p, String auteur) {
        jdbc.sql("update studio.procedure_version set settings = cast(:s as jsonb), change_note = :c, updated_at = now(), updated_by = :u where id = :id")
                .param("s", json.ecrire(p.settings())).param("c", p.changeNote()).param("u", auteur).param("id", id).update();
    }

    public void toucher(long id, String auteur) {
        jdbc.sql("update studio.procedure_version set updated_at = now(), updated_by = :u where id = :id").param("u", auteur).param("id", id).update();
    }

    public int statut(long id, String de, String vers, String colonnePar, String auteur) {
        String horodatage = colonnePar == null ? "" : ", " + colonnePar.replace("_by", "_at") + " = now(), " + colonnePar + " = :u";
        return jdbc.sql("update studio.procedure_version set lifecycle_status = cast(:vers as studio.lifecycle_status)" + horodatage
                        + " where id = :id and lifecycle_status = cast(:de as studio.lifecycle_status)")
                .param("vers", vers).param("de", de).param("u", auteur).param("id", id).update();
    }

    /** Publie la version ; l'empreinte SHA-256 porte sur le texte JSONB servi par l'API (vérifiable par le Runtime). */
    public String publier(long id, String paquet, String auteur) {
        jdbc.sql("""
                update studio.procedure_version set lifecycle_status = 'superseded'
                 where procedure_code = (select procedure_code from studio.procedure_version where id = :id) and lifecycle_status = 'published'
                """).param("id", id).update();
        jdbc.sql("""
                update studio.procedure_version set lifecycle_status = 'published', package = cast(:p as jsonb),
                       package_hash = encode(sha256(convert_to(cast(:p as jsonb)::text, 'UTF8')), 'hex'),
                       published_at = now(), published_by = :u where id = :id and lifecycle_status = 'approved'
                """).param("p", paquet).param("u", auteur).param("id", id).update();
        return jdbc.sql("select package_hash from studio.procedure_version where id = :id").param("id", id).query(String.class).single();
    }

    public Optional<String> paquet(long id) {
        return jdbc.sql("select package::text from studio.procedure_version where id = :id and package is not null").param("id", id)
                .query(String.class).optional();
    }

    public Optional<String> paquetPublie(String procedure) {
        return jdbc.sql("select package::text from studio.procedure_version where procedure_code = :p and lifecycle_status = 'published'")
                .param("p", procedure).query(String.class).optional();
    }

    // ------------------------------------------------------------------ lecture complète
    public VersionComplete complete(long id) {
        Version v = version(id).orElseThrow();
        Procedure p = procedure(v.procedureCode()).orElseThrow();
        return new VersionComplete(v, p, typesDemande(id), acteurs(id), statuts(id), sections(id), champs(id), pieces(id), frais(id),
                messages(id), flux(id), processus(id), regles(id), livrables(id));
    }

    public List<RequestType> typesDemande(long id) {
        return jdbc.sql("select code, label_fr, label_en, cur_code, natures, description, sort_order from studio.request_type where version_id = :v order by sort_order, code")
                .param("v", id).query((rs, i) -> new RequestType(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        tableau(rs.getArray(5)), rs.getString(6), rs.getInt(7))).list();
    }

    public List<Actor> acteurs(long id) {
        return jdbc.sql("select code, label_fr, label_en, kind, system_code, auth_role, responsibilities, sort_order from studio.procedure_actor where version_id = :v order by sort_order, code")
                .param("v", id).query((rs, i) -> new Actor(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getInt(8))).list();
    }

    public List<CaseStatus> statuts(long id) {
        return jdbc.sql("select code, label_fr, label_en, is_final, tone, sort_order from studio.case_status where version_id = :v order by sort_order, code")
                .param("v", id).query((rs, i) -> new CaseStatus(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getString(5), rs.getInt(6))).list();
    }

    public List<Section> sections(long id) {
        return jdbc.sql("select code, label_fr, label_en, repeating, min_rows, sort_order from studio.form_section where version_id = :v order by sort_order, code")
                .param("v", id).query((rs, i) -> new Section(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getInt(5), rs.getInt(6))).list();
    }

    public List<Field> champs(long id) {
        return jdbc.sql("""
                select f.code, f.section_code, f.label_fr, f.label_en, f.data_type, f.max_length, f.pattern, f.mandatory, f.condition_text,
                       f.refdata_table, f.code_values::text, f.untded_ref, f.wco_ref, f.ccl_den, f.ccl_uid, f.bie_path, f.searchable, f.prefill,
                       f.amendable, f.help_fr, f.help_en, f.sort_order
                  from studio.form_field f join studio.form_section s on s.version_id = f.version_id and s.code = f.section_code
                 where f.version_id = :v order by s.sort_order, f.sort_order, f.code
                """).param("v", id).query((rs, i) -> new Field(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), (Integer) rs.getObject(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11) == null ? null : json.mapper().readValue(rs.getString(11), VALEURS), rs.getString(12), rs.getString(13),
                rs.getString(14), rs.getString(15), rs.getString(16), rs.getBoolean(17), rs.getString(18), rs.getBoolean(19),
                rs.getString(20), rs.getString(21), rs.getInt(22))).list();
    }

    public List<RequiredDocument> pieces(long id) {
        return jdbc.sql("select code, label_fr, label_en, mandatory, condition_text, request_types, observation, sort_order from studio.required_document where version_id = :v order by sort_order, code")
                .param("v", id).query((rs, i) -> new RequiredDocument(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getString(5), tableau(rs.getArray(6)), rs.getString(7), rs.getInt(8))).list();
    }

    public List<FeeItem> frais(long id) {
        return jdbc.sql("select code, beneficiary, label_fr, label_en, amount, currency, request_types, natures, sort_order from studio.fee_item where version_id = :v order by sort_order, code")
                .param("v", id).query((rs, i) -> new FeeItem(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBigDecimal(5), rs.getString(6), tableau(rs.getArray(7)), tableau(rs.getArray(8)), rs.getInt(9))).list();
    }

    public List<MessageDef> messages(long id) {
        Map<String, List<MessageField>> champs = new HashMap<>();
        jdbc.sql("select message_code, seq, field_code, path, mandatory, indexed from studio.message_field where version_id = :v order by message_code, seq")
                .param("v", id).query((rs, i) -> champs.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                        .add(new MessageField(rs.getInt(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6)))).list();
        return jdbc.sql("""
                select code, label_fr, label_en, message_type, content_format, root_element, standard_ref, schema_text, sensitivity, description
                  from studio.message_definition where version_id = :v order by code
                """).param("v", id).query((rs, i) -> new MessageDef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                champs.getOrDefault(rs.getString(1), List.of()))).list();
    }

    public List<Flow> flux(long id) {
        Map<String, List<FlowMessage>> messages = new HashMap<>();
        jdbc.sql("""
                select flow_code, seq, message_code, sender_actor, receiver_actor, interaction_pattern, response_to_seq, timeout_hours
                  from studio.flow_message where version_id = :v order by flow_code, seq
                """).param("v", id).query((rs, i) -> messages.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                .add(new FlowMessage(rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        (Integer) rs.getObject(7), (Integer) rs.getObject(8)))).list();
        return jdbc.sql("select code, label_fr, label_en, sort_order from studio.procedure_flow where version_id = :v order by sort_order, code")
                .param("v", id).query((rs, i) -> new Flow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        messages.getOrDefault(rs.getString(1), List.of()))).list();
    }

    public ProcessModel processus(long id) {
        return jdbc.sql("select process_key, bpmn_xml, updated_at, updated_by from studio.process_model where version_id = :v").param("v", id)
                .query((rs, i) -> new ProcessModel(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class), rs.getString(4)))
                .optional().orElse(null);
    }

    public RuleSet regles(long id) {
        return jdbc.sql("select drl, business_rules::text, updated_at, updated_by from studio.rule_set where version_id = :v").param("v", id)
                .query((rs, i) -> new RuleSet(rs.getString(1), json.mapper().readValue(rs.getString(2), REGLES),
                        rs.getObject(3, OffsetDateTime.class), rs.getString(4))).optional().orElse(null);
    }

    public List<Deliverable> livrables(long id) {
        return jdbc.sql("select code, label_fr, label_en, doc_code, validity_rule, title_fr, body_fields, signature_note from studio.deliverable where version_id = :v order by code")
                .param("v", id).query((rs, i) -> new Deliverable(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), tableau(rs.getArray(7)), rs.getString(8))).list();
    }

    // ------------------------------------------------------------------ écriture par partie
    public void typesDemande(long id, List<RequestType> l) {
        jdbc.sql("delete from studio.request_type where version_id = :v").param("v", id).update();
        for (RequestType t : l)
            jdbc.sql("""
                    insert into studio.request_type (version_id, code, label_fr, label_en, cur_code, natures, description, sort_order)
                    values (:v, :c, :f, :e, :cur, cast(:n as text[]), :d, :o)
                    """).param("v", id).param("c", t.code()).param("f", t.labelFr()).param("e", t.labelEn()).param("cur", t.curCode())
                    .param("n", arr(t.natures())).param("d", t.description()).param("o", t.sortOrder()).update();
    }

    /** Acteurs : mise à jour sur place (les flux qui les référencent sont conservés) ; un acteur retiré supprime ses messages de flux. */
    public void acteurs(long id, List<Actor> l) {
        jdbc.sql("delete from studio.procedure_actor where version_id = :v and not (code = any(cast(:c as text[])))")
                .param("v", id).param("c", arr(l.stream().map(Actor::code).toList())).update();
        for (Actor a : l)
            jdbc.sql("""
                    insert into studio.procedure_actor (version_id, code, label_fr, label_en, kind, system_code, auth_role, responsibilities, sort_order)
                    values (:v, :c, :f, :e, :k, :s, :r, :resp, :o)
                    on conflict (version_id, code) do update set label_fr = excluded.label_fr, label_en = excluded.label_en, kind = excluded.kind,
                      system_code = excluded.system_code, auth_role = excluded.auth_role, responsibilities = excluded.responsibilities,
                      sort_order = excluded.sort_order
                    """).param("v", id).param("c", a.code()).param("f", a.labelFr()).param("e", a.labelEn()).param("k", a.kind())
                    .param("s", a.systemCode() == null ? "EGOV" : a.systemCode()).param("r", vide(a.authRole()))
                    .param("resp", a.responsibilities()).param("o", a.sortOrder()).update();
    }

    public void statuts(long id, List<CaseStatus> l) {
        jdbc.sql("delete from studio.case_status where version_id = :v").param("v", id).update();
        for (CaseStatus s : l)
            jdbc.sql("insert into studio.case_status (version_id, code, label_fr, label_en, is_final, tone, sort_order) values (:v, :c, :f, :e, :fin, :t, :o)")
                    .param("v", id).param("c", s.code()).param("f", s.labelFr()).param("e", s.labelEn()).param("fin", s.isFinal())
                    .param("t", s.tone() == null ? "neutral" : s.tone()).param("o", s.sortOrder()).update();
    }

    /** Formulaire : sections et champs remplacés ensemble (les champs dépendent de leur section). */
    public void formulaire(long id, List<Section> sections, List<Field> champs) {
        jdbc.sql("delete from studio.form_field where version_id = :v").param("v", id).update();
        jdbc.sql("delete from studio.form_section where version_id = :v").param("v", id).update();
        for (Section s : sections)
            jdbc.sql("insert into studio.form_section (version_id, code, label_fr, label_en, repeating, min_rows, sort_order) values (:v, :c, :f, :e, :r, :m, :o)")
                    .param("v", id).param("c", s.code()).param("f", s.labelFr()).param("e", s.labelEn()).param("r", s.repeating())
                    .param("m", s.minRows()).param("o", s.sortOrder()).update();
        for (Field f : champs) insererChamp(id, f);
    }

    private void insererChamp(long id, Field f) {
        jdbc.sql("""
                insert into studio.form_field (version_id, code, section_code, label_fr, label_en, data_type, max_length, pattern, mandatory,
                  condition_text, refdata_table, code_values, untded_ref, wco_ref, ccl_den, ccl_uid, bie_path, searchable, prefill, amendable,
                  help_fr, help_en, sort_order)
                values (:v, :c, :s, :f, :e, :t, :ml, :p, :m, :ct, :rt, cast(:cv as jsonb), :u, :w, :cd, :cu, :b, :se, :pf, :am, :hf, :he, :o)
                """).param("v", id).param("c", f.code()).param("s", f.sectionCode()).param("f", f.labelFr()).param("e", f.labelEn())
                .param("t", f.dataType()).param("ml", f.maxLength()).param("p", vide(f.pattern())).param("m", f.mandatory() == null ? "OUI" : f.mandatory())
                .param("ct", vide(f.conditionText())).param("rt", vide(f.refdataTable()))
                .param("cv", f.codeValues() == null || f.codeValues().isEmpty() ? null : json.mapper().writeValueAsString(f.codeValues()))
                .param("u", vide(f.untdedRef())).param("w", vide(f.wcoRef())).param("cd", vide(f.cclDen())).param("cu", vide(f.cclUid()))
                .param("b", vide(f.biePath())).param("se", f.searchable()).param("pf", vide(f.prefill())).param("am", f.amendable())
                .param("hf", vide(f.helpFr())).param("he", vide(f.helpEn())).param("o", f.sortOrder()).update();
    }

    public void pieces(long id, List<RequiredDocument> l) {
        jdbc.sql("delete from studio.required_document where version_id = :v").param("v", id).update();
        for (RequiredDocument d : l)
            jdbc.sql("""
                    insert into studio.required_document (version_id, code, label_fr, label_en, mandatory, condition_text, request_types, observation, sort_order)
                    values (:v, :c, :f, :e, :m, :ct, cast(:rt as text[]), :ob, :o)
                    """).param("v", id).param("c", d.code()).param("f", d.labelFr()).param("e", d.labelEn()).param("m", d.mandatory())
                    .param("ct", vide(d.conditionText())).param("rt", arr(d.requestTypes())).param("ob", vide(d.observation()))
                    .param("o", d.sortOrder()).update();
    }

    public void frais(long id, List<FeeItem> l) {
        jdbc.sql("delete from studio.fee_item where version_id = :v").param("v", id).update();
        for (FeeItem f : l)
            jdbc.sql("""
                    insert into studio.fee_item (version_id, code, beneficiary, label_fr, label_en, amount, currency, request_types, natures, sort_order)
                    values (:v, :c, :b, :f, :e, :a, :cur, cast(:rt as text[]), cast(:n as text[]), :o)
                    """).param("v", id).param("c", f.code()).param("b", f.beneficiary()).param("f", f.labelFr()).param("e", f.labelEn())
                    .param("a", f.amount()).param("cur", f.currency() == null ? "XAF" : f.currency()).param("rt", arr(f.requestTypes()))
                    .param("n", arr(f.natures())).param("o", f.sortOrder()).update();
    }

    /** Catalogue de messages : mise à jour sur place (les flux qui les utilisent sont conservés), champs remplacés. */
    public void messages(long id, List<MessageDef> l) {
        jdbc.sql("delete from studio.message_definition where version_id = :v and not (code = any(cast(:c as text[])))")
                .param("v", id).param("c", arr(l.stream().map(MessageDef::code).toList())).update();
        jdbc.sql("delete from studio.message_field where version_id = :v").param("v", id).update();
        for (MessageDef m : l) {
            jdbc.sql("""
                    insert into studio.message_definition (version_id, code, label_fr, label_en, message_type, content_format, root_element,
                      standard_ref, schema_text, sensitivity, description)
                    values (:v, :c, :f, :e, :t, :fmt, :r, :s, :sch, :sen, :d)
                    on conflict (version_id, code) do update set label_fr = excluded.label_fr, label_en = excluded.label_en,
                      message_type = excluded.message_type, content_format = excluded.content_format, root_element = excluded.root_element,
                      standard_ref = excluded.standard_ref, schema_text = excluded.schema_text, sensitivity = excluded.sensitivity,
                      description = excluded.description
                    """).param("v", id).param("c", m.code()).param("f", m.labelFr()).param("e", m.labelEn()).param("t", m.messageType())
                    .param("fmt", m.contentFormat()).param("r", vide(m.rootElement())).param("s", vide(m.standardRef()))
                    .param("sch", vide(m.schemaText())).param("sen", m.sensitivity() == null ? "internal" : m.sensitivity())
                    .param("d", vide(m.description())).update();
            int seq = 0;
            for (MessageField c : m.fields() == null ? List.<MessageField>of() : m.fields())
                jdbc.sql("insert into studio.message_field (version_id, message_code, seq, field_code, path, mandatory, indexed) values (:v, :m, :s, :f, :p, :ob, :i)")
                        .param("v", id).param("m", m.code()).param("s", ++seq).param("f", vide(c.fieldCode())).param("p", c.path())
                        .param("ob", c.mandatory()).param("i", c.indexed()).update();
        }
    }

    public void flux(long id, List<Flow> l) {
        jdbc.sql("delete from studio.flow_message where version_id = :v").param("v", id).update();
        jdbc.sql("delete from studio.procedure_flow where version_id = :v").param("v", id).update();
        for (Flow f : l) {
            jdbc.sql("insert into studio.procedure_flow (version_id, code, label_fr, label_en, sort_order) values (:v, :c, :f, :e, :o)")
                    .param("v", id).param("c", f.code()).param("f", f.labelFr()).param("e", f.labelEn()).param("o", f.sortOrder()).update();
            for (FlowMessage m : f.messages() == null ? List.<FlowMessage>of() : f.messages().stream().sorted(Comparator.comparingInt(FlowMessage::seq)).toList())
                jdbc.sql("""
                        insert into studio.flow_message (version_id, flow_code, seq, message_code, sender_actor, receiver_actor, interaction_pattern,
                          response_to_seq, timeout_hours)
                        values (:v, :f, :s, :m, :se, :re, :ip, :rt, :to)
                        """).param("v", id).param("f", f.code()).param("s", m.seq()).param("m", m.messageCode()).param("se", m.senderActor())
                        .param("re", m.receiverActor()).param("ip", m.interactionPattern() == null ? "ASYNC" : m.interactionPattern())
                        .param("rt", m.responseToSeq()).param("to", m.timeoutHours()).update();
        }
    }

    public void processus(long id, ProcessModel p, String auteur) {
        jdbc.sql("""
                insert into studio.process_model (version_id, process_key, bpmn_xml, updated_by) values (:v, :k, :x, :u)
                on conflict (version_id) do update set process_key = excluded.process_key, bpmn_xml = excluded.bpmn_xml, updated_at = now(), updated_by = excluded.updated_by
                """).param("v", id).param("k", p.processKey()).param("x", p.bpmnXml()).param("u", auteur).update();
    }

    public void regles(long id, RuleSet r, String auteur) {
        jdbc.sql("""
                insert into studio.rule_set (version_id, drl, business_rules, updated_by) values (:v, :d, cast(:b as jsonb), :u)
                on conflict (version_id) do update set drl = excluded.drl, business_rules = excluded.business_rules, updated_at = now(), updated_by = excluded.updated_by
                """).param("v", id).param("d", r.drl()).param("b", json.mapper().writeValueAsString(r.businessRules() == null ? List.of() : r.businessRules()))
                .param("u", auteur).update();
    }

    public void livrables(long id, List<Deliverable> l) {
        jdbc.sql("delete from studio.deliverable where version_id = :v").param("v", id).update();
        for (Deliverable d : l)
            jdbc.sql("""
                    insert into studio.deliverable (version_id, code, label_fr, label_en, doc_code, validity_rule, title_fr, body_fields, signature_note)
                    values (:v, :c, :f, :e, :dc, :vr, :t, cast(:b as text[]), :s)
                    """).param("v", id).param("c", d.code()).param("f", d.labelFr()).param("e", d.labelEn()).param("dc", d.docCode())
                    .param("vr", d.validityRule() == null ? "FIN_ANNEE" : d.validityRule()).param("t", vide(d.titleFr()))
                    .param("b", arr(d.bodyFields())).param("s", vide(d.signatureNote())).update();
    }

    /** Copie intégrale d'une version (nouvelle version de travail). */
    public void copier(VersionComplete source, long cible, String auteur) {
        typesDemande(cible, source.requestTypes());
        acteurs(cible, source.actors());
        statuts(cible, source.statuses());
        formulaire(cible, source.sections(), source.fields());
        pieces(cible, source.documents());
        frais(cible, source.fees());
        messages(cible, source.messages());
        flux(cible, source.flows());
        if (source.process() != null) processus(cible, source.process(), auteur);
        if (source.rules() != null) regles(cible, source.rules(), auteur);
        livrables(cible, source.deliverables());
    }

    // ------------------------------------------------------------------ utilitaires
    private static List<String> tableau(Array a) throws SQLException {
        if (a == null) return List.of();
        return Arrays.asList((String[]) a.getArray());
    }

    private static String[] arr(List<String> l) {
        return l == null ? new String[0] : l.stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
