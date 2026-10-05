package com.nexora.refdata.search;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nexora.refdata.entry.EntryRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recherche globale : tables (code, nom, standard) et codes de toutes les tables (code et libellés),
 * insensible aux accents, classée par pertinence (correspondance exacte, puis similarité trigramme).
 */
@RestController
@RequestMapping("/api/v1")
public class SearchController {

    public record TableTrouvee(String code, String nameFr, String categoryCode, String standards, long entryCount) {}

    public record CodeTrouve(String tableCode, String tableName, String code, String labelFr, String labelEn, String status) {}

    public record Resultats(String query, List<TableTrouvee> tables, List<CodeTrouve> entries) {}

    private final JdbcClient jdbc;

    public SearchController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/search")
    @Transactional(readOnly = true)
    public Resultats rechercher(@RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
        String terme = q.strip();
        if (terme.length() < 2) return new Resultats(terme, List.of(), List.of());
        int lim = Math.clamp(limit, 1, 100);
        Map<String, Object> pt = new HashMap<>(Map.of("q", terme));
        String motsTables = Recherche.clause("ref_texte_recherche(t.code, t.name_fr, coalesce(t.name_en, ''), coalesce(t.standards, ''), "
                + "coalesce(t.description, ''), replace(t.code, '_', ' '))", terme, pt, "m");
        List<TableTrouvee> tables = jdbc.sql("""
                select t.code, t.name_fr, t.category_code, t.standards,
                       (select count(*) from ref_entry e where e.table_id = t.id) as n
                  from ref_table t
                 where t.status <> 'ARCHIVED'""" + motsTables + """
                 order by (upper(t.code) = upper(:q) or upper(t.code) = 'REF_' || upper(:q)) desc, n desc, t.code
                 limit 8
                """)
                .params(pt)
                .query((rs, i) -> new TableTrouvee(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)))
                .list();
        Map<String, Object> pe = new HashMap<>(Map.of("q", terme, "lim", lim));
        List<CodeTrouve> codes = jdbc.sql("""
                select t.code, t.name_fr, e.code, e.label_fr, e.label_en, e.status
                  from ref_entry e join ref_table t on t.id = e.table_id
                 where t.status <> 'ARCHIVED'""" + Recherche.clause(EntryRepository.TEXTE, terme, pe, "m") + """
                 order by (upper(e.code) = upper(:q)) desc, e.status,
                          (%s <-> ref_texte_recherche(cast(:q as text))), t.code, e.code
                 limit :lim
                """.formatted(EntryRepository.TEXTE))
                .params(pe)
                .query((rs, i) -> new CodeTrouve(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)))
                .list();
        return new Resultats(terme, tables, codes);
    }
}
