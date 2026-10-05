package com.nexora.refdata.catalogue;

import com.nexora.refdata.catalogue.CatalogueModel.Category;
import com.nexora.refdata.catalogue.CatalogueModel.ColumnDef;
import com.nexora.refdata.catalogue.CatalogueModel.TableDef;
import com.nexora.refdata.web.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

/** Accès SQL au catalogue (ref_category, ref_table, ref_column). */
@Repository
public class CatalogueRepository {

    private static final String SELECT_TABLE = """
            select t.*, array_to_string(t.bsp_phases, ',') as bsp,
                   coalesce(s.total, 0) as entry_count, coalesce(s.actives, 0) as active_count
              from ref_table t
              left join (select table_id, count(*) as total, count(*) filter (where status = 'ACTIVE') as actives
                           from ref_entry group by table_id) s on s.table_id = t.id
            """;

    private final JdbcClient jdbc;
    private final Json json;

    public CatalogueRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---------- Catégories ----------
    public List<Category> categories() {
        return jdbc.sql("""
                select c.*, coalesce(x.tables, 0) as table_count, coalesce(x.entries, 0) as entry_count
                  from ref_category c
                  left join (select t.category_code, count(distinct t.id) as tables, count(e.id) as entries
                               from ref_table t left join ref_entry e on e.table_id = t.id
                              where t.status <> 'ARCHIVED' group by t.category_code) x on x.category_code = c.code
                 order by c.parent_code nulls first, c.sort_order, c.code
                """)
                .query((rs, i) -> new Category(rs.getString("code"), rs.getString("parent_code"), rs.getString("label_fr"),
                        rs.getString("label_en"), rs.getString("description"), rs.getString("source"), rs.getString("icon"),
                        rs.getString("color"), rs.getInt("sort_order"), rs.getLong("table_count"), rs.getLong("entry_count")))
                .list();
    }

    public boolean categorieExiste(String code) {
        return jdbc.sql("select count(*) from ref_category where code = :c").param("c", code).query(Long.class).single() > 0;
    }

    public void insererCategorie(Category c) {
        jdbc.sql("""
                insert into ref_category (code, parent_code, label_fr, label_en, description, source, icon, color, sort_order)
                values (:code, :parent, :fr, :en, :descr, :source, :icon, :color, :ordre)
                """)
                .param("code", c.code()).param("parent", c.parentCode()).param("fr", c.labelFr()).param("en", c.labelEn())
                .param("descr", c.description()).param("source", c.source()).param("icon", c.icon()).param("color", c.color())
                .param("ordre", c.sortOrder())
                .update();
    }

    public int modifierCategorie(String code, Category c) {
        return jdbc.sql("""
                update ref_category set parent_code = :parent, label_fr = :fr, label_en = :en, description = :descr,
                       source = :source, icon = :icon, color = :color, sort_order = :ordre
                 where code = :code
                """)
                .param("code", code).param("parent", c.parentCode()).param("fr", c.labelFr()).param("en", c.labelEn())
                .param("descr", c.description()).param("source", c.source()).param("icon", c.icon()).param("color", c.color())
                .param("ordre", c.sortOrder())
                .update();
    }

    // ---------- Tables ----------
    public List<TableDef> tables() {
        List<TableDef> sansColonnes = jdbc.sql(SELECT_TABLE + " order by t.number nulls last, t.code").query(this::lireTable).list();
        Map<Long, List<ColumnDef>> colonnes = toutesColonnes();
        return sansColonnes.stream().map(t -> avecColonnes(t, colonnes.getOrDefault(idDe(t.code()), List.of()))).toList();
    }

    public Optional<TableDef> table(String code) {
        return jdbc.sql(SELECT_TABLE + " where t.code = :c").param("c", code).query(this::lireTable).optional()
                .map(t -> avecColonnes(t, colonnes(idDe(code))));
    }

    public Long idDe(String code) {
        return ids().get(code);
    }

    /** Cache léger code → id (le catalogue compte quelques centaines de tables au plus). */
    private Map<String, Long> ids() {
        Map<String, Long> m = new HashMap<>();
        jdbc.sql("select code, id from ref_table").query(rs -> { m.put(rs.getString(1), rs.getLong(2)); });
        return m;
    }

    public long insererTable(TableDef t) {
        return jdbc.sql("""
                insert into ref_table (code, number, name_fr, name_en, description, category_code, source, standards, producer,
                       update_authority, obtention_mode, update_mode, bsp_phases, metadata, source_document, data_source, status)
                values (:code, :num, :fr, :en, :descr, :cat, :source, :std, :prod, :aut, :obt, :maj,
                        string_to_array(:bsp, ',')::varchar(4)[], cast(:meta as jsonb), :doc, :data, :statut)
                returning id
                """)
                .param("code", t.code()).param("num", t.number()).param("fr", t.nameFr()).param("en", t.nameEn())
                .param("descr", t.description()).param("cat", t.categoryCode()).param("source", t.source())
                .param("std", t.standards()).param("prod", t.producer()).param("aut", t.updateAuthority())
                .param("obt", t.obtentionMode()).param("maj", t.updateMode()).param("bsp", String.join(",", t.bspPhases()))
                .param("meta", json.ecrire(t.metadata())).param("doc", t.sourceDocument()).param("data", t.dataSource())
                .param("statut", t.status())
                .query(Long.class).single();
    }

    public int modifierTable(String code, TableDef t, long version) {
        return jdbc.sql("""
                update ref_table set number = :num, name_fr = :fr, name_en = :en, description = :descr, category_code = :cat,
                       source = :source, standards = :std, producer = :prod, update_authority = :aut, obtention_mode = :obt,
                       update_mode = :maj, bsp_phases = string_to_array(:bsp, ',')::varchar(4)[], metadata = cast(:meta as jsonb),
                       parent_table_code = :parent, status = :statut, data_source = :data
                 where code = :code and version = :version
                """)
                .param("code", code).param("num", t.number()).param("fr", t.nameFr()).param("en", t.nameEn())
                .param("descr", t.description()).param("cat", t.categoryCode()).param("source", t.source())
                .param("std", t.standards()).param("prod", t.producer()).param("aut", t.updateAuthority())
                .param("obt", t.obtentionMode()).param("maj", t.updateMode()).param("bsp", String.join(",", t.bspPhases()))
                .param("meta", json.ecrire(t.metadata())).param("parent", t.parentTableCode()).param("statut", t.status())
                .param("data", t.dataSource()).param("version", version)
                .update();
    }

    public void definirParent(String code, String parent) {
        jdbc.sql("update ref_table set parent_table_code = :p where code = :c").param("p", parent).param("c", code).update();
    }

    // ---------- Colonnes ----------
    public List<ColumnDef> colonnes(Long tableId) {
        if (tableId == null) return List.of();
        return jdbc.sql("select * from ref_column where table_id = :t order by sort_order, id").param("t", tableId)
                .query(CatalogueRepository::colonne).list();
    }

    private Map<Long, List<ColumnDef>> toutesColonnes() {
        Map<Long, List<ColumnDef>> m = new HashMap<>();
        jdbc.sql("select * from ref_column order by table_id, sort_order, id")
                .query(rs -> { m.computeIfAbsent(rs.getLong("table_id"), k -> new ArrayList<>()).add(colonne(rs, 0)); });
        return m;
    }

    /** Remplace la structure : mise à jour des colonnes conservées, ajout des nouvelles, retrait des absentes. */
    public void enregistrerColonnes(long tableId, List<ColumnDef> colonnes) {
        Set<String> cles = new HashSet<>();
        colonnes.forEach(c -> cles.add(c.key()));
        List<String> existantes = jdbc.sql("select key from ref_column where table_id = :t").param("t", tableId).query(String.class).list();
        for (String k : existantes) {
            if (!cles.contains(k)) jdbc.sql("delete from ref_column where table_id = :t and key = :k").param("t", tableId).param("k", k).update();
        }
        // Libère les rôles structurants avant réaffectation (index unique par rôle)
        jdbc.sql("update ref_column set role = 'ATTRIBUTE' where table_id = :t and role <> 'ATTRIBUTE'").param("t", tableId).update();
        for (ColumnDef c : colonnes) {
            jdbc.sql("""
                    insert into ref_column (table_id, key, label_fr, label_en, role, data_type, max_length, pattern, required,
                           cardinality, xml_tag, untded, unit, definition, comment, ref_table_code, sort_order)
                    values (:t, :key, :fr, :en, :role, :type, :len, :pattern, :req, :card, :xml, :untded, :unit, :def, :com, :ref, :ordre)
                    on conflict (table_id, key) do update set label_fr = excluded.label_fr, label_en = excluded.label_en,
                       role = excluded.role, data_type = excluded.data_type, max_length = excluded.max_length,
                       pattern = excluded.pattern, required = excluded.required, cardinality = excluded.cardinality,
                       xml_tag = excluded.xml_tag, untded = excluded.untded, unit = excluded.unit, definition = excluded.definition,
                       comment = excluded.comment, ref_table_code = excluded.ref_table_code, sort_order = excluded.sort_order
                    """)
                    .param("t", tableId).param("key", c.key()).param("fr", c.labelFr()).param("en", c.labelEn())
                    .param("role", c.role()).param("type", c.dataType()).param("len", c.maxLength()).param("pattern", c.pattern())
                    .param("req", c.required()).param("card", c.cardinality()).param("xml", c.xmlTag()).param("untded", c.untded())
                    .param("unit", c.unit()).param("def", c.definition()).param("com", c.comment()).param("ref", c.refTableCode())
                    .param("ordre", c.sortOrder())
                    .update();
        }
    }

    /** Nombre d'entrées ayant une valeur pour un attribut (contrôle avant retrait d'une colonne). */
    public long valeursAttribut(long tableId, String cle) {
        // jsonb_exists() plutôt que l'opérateur « ? », pris par JDBC pour un paramètre
        return jdbc.sql("select count(*) from ref_entry where table_id = :t and jsonb_exists(attributes, :k)")
                .param("t", tableId).param("k", cle).query(Long.class).single();
    }

    /** Valeurs existantes incompatibles avec un nouveau type de colonne. */
    public long valeursIncompatibles(long tableId, String cle, String type) {
        String regle = switch (type) {
            case "INTEGER" -> "^-?[0-9]+$";
            case "DECIMAL" -> "^-?[0-9]+(\\.[0-9]+)?$";
            case "DATE" -> "^[0-9]{4}-[0-9]{2}-[0-9]{2}$";
            case "BOOLEAN" -> "^(true|false)$";
            default -> null;
        };
        if (regle == null) return 0;
        return jdbc.sql("select count(*) from ref_entry where table_id = :t and jsonb_exists(attributes, :k) and not (attributes ->> :k) ~ :r")
                .param("t", tableId).param("k", cle).param("r", regle).query(Long.class).single();
    }

    public void genererVue(String code) {
        jdbc.sql("select ref_generer_vue(:c)").param("c", code).query((rs, i) -> 1).single();
    }

    // ---------- Correspondances ----------
    private TableDef lireTable(ResultSet rs, int i) throws SQLException {
        String bsp = rs.getString("bsp");
        TableDef t = new TableDef(rs.getString("code"), rs.getString("number"), rs.getString("name_fr"), rs.getString("name_en"),
                rs.getString("description"), rs.getString("category_code"), rs.getString("source"), rs.getString("standards"),
                rs.getString("producer"), rs.getString("update_authority"), rs.getString("obtention_mode"),
                rs.getString("update_mode"), bsp == null || bsp.isBlank() ? List.of() : List.of(bsp.split(",")),
                rs.getString("parent_table_code"), json.map(rs.getString("metadata")), rs.getString("source_document"),
                rs.getString("data_source"), rs.getString("status"), List.of(), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                rs.getLong("entry_count"), rs.getLong("active_count"), 0, null, "referentiel." + rs.getString("code").toLowerCase());
        return t;
    }

    /** Complète une table lue en base : colonnes, contenu simple/complexe (ERMIS) et complétude ISO 19115. */
    static TableDef avecColonnes(TableDef t, List<ColumnDef> colonnes) {
        return new TableDef(t.code(), t.number(), t.nameFr(), t.nameEn(), t.description(), t.categoryCode(), t.source(),
                t.standards(), t.producer(), t.updateAuthority(), t.obtentionMode(), t.updateMode(), t.bspPhases(),
                t.parentTableCode(), t.metadata(), t.sourceDocument(), t.dataSource(), t.status(), colonnes, t.version(),
                t.createdAt(), t.updatedAt(), t.entryCount(), t.activeCount(), CatalogueModel.completude(t),
                colonnes.stream().anyMatch(ColumnDef::attribut) ? "COMPLEXE" : "SIMPLE", t.sqlView());
    }

    private static ColumnDef colonne(ResultSet rs, int i) throws SQLException {
        return new ColumnDef(rs.getString("key"), rs.getString("label_fr"), rs.getString("label_en"), rs.getString("role"),
                rs.getString("data_type"), (Integer) rs.getObject("max_length"), rs.getString("pattern"), rs.getBoolean("required"),
                rs.getString("cardinality"), rs.getString("xml_tag"), rs.getString("untded"), rs.getString("unit"),
                rs.getString("definition"), rs.getString("comment"), rs.getString("ref_table_code"), rs.getInt("sort_order"));
    }
}
