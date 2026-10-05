package com.nexora.refdata.entry;

import com.nexora.refdata.entry.Entry.LookupItem;
import com.nexora.refdata.entry.Entry.Page;
import com.nexora.refdata.search.Recherche;
import com.nexora.refdata.web.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/** Accès SQL au contenu des tables (ref_entry). */
@Repository
public class EntryRepository {

    /** Filtres de consultation d'une table. */
    public record Filtre(String q, String statut, LocalDate valideLe, String parent, boolean racines, String attribut, String valeur) {}

    /** Expression indexée (trigrammes) sur laquelle porte la recherche plein texte. */
    public static final String TEXTE = "ref_texte_recherche(e.code, coalesce(e.label_fr, ''), coalesce(e.label_en, ''))";

    private static final Map<String, String> TRIS =Map.of("code", "e.code", "labelFr", "e.label_fr", "labelEn", "e.label_en",
            "validFrom", "e.valid_from", "validTo", "e.valid_to", "status", "e.status", "updatedAt", "e.updated_at");

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final Json json;

    public EntryRepository(JdbcClient jdbc, JdbcTemplate template, Json json) {
        this.jdbc = jdbc;
        this.template = template;
        this.json = json;
    }

    public Page<Entry> page(long tableId, Filtre f, int page, int taille, String tri, boolean desc) {
        StringBuilder where = new StringBuilder(" where e.table_id = :t");
        Map<String, Object> p = new HashMap<>();
        p.put("t", tableId);
        if (f.q() != null && !f.q().isBlank()) where.append(Recherche.clause(TEXTE, f.q(), p, "q"));
        if (f.statut() != null && !"ALL".equals(f.statut())) { where.append(" and e.status = :s"); p.put("s", f.statut()); }
        if (f.valideLe() != null) {
            where.append(" and e.status = 'ACTIVE' and (e.valid_from is null or e.valid_from <= :d) and (e.valid_to is null or e.valid_to >= :d)");
            p.put("d", Date.valueOf(f.valideLe()));
        }
        if (f.parent() != null) { where.append(" and e.parent_code = :p"); p.put("p", f.parent()); }
        else if (f.racines()) where.append(" and e.parent_code is null");
        if (f.attribut() != null && f.valeur() != null) {
            where.append(" and e.attributes ->> cast(:ak as text) = :av");
            p.put("ak", f.attribut());
            p.put("av", f.valeur());
        }
        long total = jdbc.sql("select count(*) from ref_entry e" + where).params(p).query(Long.class).single();
        String ordre = TRIS.getOrDefault(tri == null ? "code" : tri, "e.code") + (desc ? " desc" : " asc");
        p.put("lim", taille);
        p.put("off", (long) page * taille);
        List<Entry> items = jdbc.sql("""
                select e.*, (select count(*) from ref_entry c where c.table_id = e.table_id and c.parent_code = e.code) as child_count
                  from ref_entry e""" + where + " order by " + ordre + ", e.id limit :lim offset :off")
                .params(p).query(this::entree).list();
        return new Page<>(items, total, page, taille);
    }

    public Optional<Entry> lire(long tableId, String code) {
        return jdbc.sql("select e.*, null as child_count from ref_entry e where e.table_id = :t and e.code = :c")
                .param("t", tableId).param("c", code).query(this::entree).optional();
    }

    public boolean existe(String tableCode, String code) {
        return jdbc.sql("select exists(select 1 from ref_entry e join ref_table t on t.id = e.table_id where t.code = :t and e.code = :c)")
                .param("t", tableCode).param("c", code).query(Boolean.class).single();
    }

    public Set<String> codes(long tableId) {
        Set<String> res = new HashSet<>();
        jdbc.sql("select code from ref_entry where table_id = :t").param("t", tableId).query(rs -> { res.add(rs.getString(1)); });
        return res;
    }

    public Map<String, Entry> toutes(long tableId) {
        Map<String, Entry> res = new HashMap<>();
        jdbc.sql("select e.*, null as child_count from ref_entry e where e.table_id = :t").param("t", tableId)
                .query(rs -> { Entry e = entree(rs, 0); res.put(e.code(), e); });
        return res;
    }

    /** Parcours en flux (export de grandes tables comme UN/LOCODE). */
    public void parcourir(long tableId, String statut, java.util.function.Consumer<Entry> consommateur) {
        String filtre = statut == null || "ALL".equals(statut) ? "" : " and e.status = :s";
        var spec = jdbc.sql("select e.*, null as child_count from ref_entry e where e.table_id = :t" + filtre + " order by e.code")
                .param("t", tableId);
        if (!filtre.isEmpty()) spec = spec.param("s", statut);
        spec.query((RowCallbackHandler) rs -> consommateur.accept(entree(rs, 0)));
    }

    public void inserer(long tableId, Entry e) {
        jdbc.sql("""
                insert into ref_entry (table_id, code, label_fr, label_en, parent_code, attributes, valid_from, valid_to, status)
                values (:t, :code, :fr, :en, :parent, cast(:attr as jsonb), :du, :au, :statut)
                """)
                .param("t", tableId).param("code", e.code()).param("fr", e.labelFr()).param("en", e.labelEn())
                .param("parent", e.parentCode()).param("attr", json.ecrire(e.attributes()))
                .param("du", e.validFrom() == null ? null : Date.valueOf(e.validFrom()))
                .param("au", e.validTo() == null ? null : Date.valueOf(e.validTo())).param("statut", e.status())
                .update();
    }

    /** Insertion par lots (chargement initial, imports volumineux). */
    public void insererLot(long tableId, List<Entry> lot) {
        template.batchUpdate("""
                insert into ref_entry (table_id, code, label_fr, label_en, parent_code, attributes, valid_from, valid_to, status)
                values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)
                """, lot, 2000, (ps, e) -> {
            ps.setLong(1, tableId);
            ps.setString(2, e.code());
            ps.setString(3, e.labelFr());
            ps.setString(4, e.labelEn());
            ps.setString(5, e.parentCode());
            ps.setString(6, json.ecrire(e.attributes()));
            ps.setDate(7, e.validFrom() == null ? null : Date.valueOf(e.validFrom()));
            ps.setDate(8, e.validTo() == null ? null : Date.valueOf(e.validTo()));
            ps.setString(9, e.status() == null ? "ACTIVE" : e.status());
        });
    }

    public int modifier(long tableId, Entry e, Long version) {
        return jdbc.sql("""
                update ref_entry set label_fr = :fr, label_en = :en, parent_code = :parent, attributes = cast(:attr as jsonb),
                       valid_from = :du, valid_to = :au, status = :statut
                 where table_id = :t and code = :code and (cast(:version as bigint) is null or version = :version)
                """)
                .param("t", tableId).param("code", e.code()).param("fr", e.labelFr()).param("en", e.labelEn())
                .param("parent", e.parentCode()).param("attr", json.ecrire(e.attributes()))
                .param("du", e.validFrom() == null ? null : Date.valueOf(e.validFrom()))
                .param("au", e.validTo() == null ? null : Date.valueOf(e.validTo())).param("statut", e.status())
                .param("version", version)
                .update();
    }

    public List<LookupItem> lookup(long tableId, boolean anglais, LocalDate date, String q, String parent, int limite) {
        StringBuilder sql = new StringBuilder("""
                select e.code, coalesce(%s, e.label_fr, e.code) as label, e.parent_code from ref_entry e
                 where e.table_id = :t and e.status = 'ACTIVE'
                   and (e.valid_from is null or e.valid_from <= :d) and (e.valid_to is null or e.valid_to >= :d)
                """.formatted(anglais ? "e.label_en" : "e.label_fr"));
        Map<String, Object> p = new HashMap<>(Map.of("t", tableId, "d", Date.valueOf(date), "lim", limite));
        if (q != null && !q.isBlank()) sql.append(Recherche.clause(TEXTE, q, p, "q"));
        if (parent != null) { sql.append(" and e.parent_code = :p"); p.put("p", parent); }
        sql.append(" order by e.code limit :lim");
        return jdbc.sql(sql.toString()).params(p).query((rs, i) -> new LookupItem(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }

    private Entry entree(ResultSet rs, int i) throws SQLException {
        Date du = rs.getDate("valid_from"), au = rs.getDate("valid_to");
        Object enfants = rs.getObject("child_count");
        return new Entry(rs.getString("code"), rs.getString("label_fr"), rs.getString("label_en"), rs.getString("parent_code"),
                json.map(rs.getString("attributes")), du == null ? null : du.toLocalDate(), au == null ? null : au.toLocalDate(),
                rs.getString("status"), rs.getLong("version"), enfants == null ? null : ((Number) enfants).longValue(),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}
