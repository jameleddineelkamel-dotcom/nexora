package com.nexora.refdata.dashboard;

import com.nexora.refdata.catalogue.CatalogueModel.TableDef;
import com.nexora.refdata.catalogue.CatalogueRepository;
import com.nexora.refdata.history.HistoryService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/** Indicateurs du référentiel : volumes, couverture Buy-Ship-Pay, qualité des métadonnées, activité. */
@RestController
@RequestMapping("/api/v1")
public class DashboardController {

    public record Indicateurs(long tables, long tablesWithData, long entries, long activeEntries, long invalidEntries,
                              long changesLast7Days, int averageCompleteness, Map<String, Long> bsp, Map<String, Long> sources,
                              List<Map<String, Object>> largestTables, List<Map<String, Object>> toComplete,
                              List<HistoryService.Activite> activity, List<HistoryService.Evenement> recent) {}

    private final CatalogueRepository catalogue;
    private final HistoryService historique;
    private final JdbcClient jdbc;

    public DashboardController(CatalogueRepository catalogue, HistoryService historique, JdbcClient jdbc) {
        this.catalogue = catalogue;
        this.historique = historique;
        this.jdbc = jdbc;
    }

    private static Map<String, Object> resume(TableDef t, String cle, Object valeur) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", t.code());
        m.put("nameFr", t.nameFr());
        m.put("nameEn", t.nameEn());
        m.put(cle, valeur);
        return m;
    }

    @GetMapping("/dashboard")
    @Transactional(readOnly = true)
    public Indicateurs indicateurs() {
        List<TableDef> tables = catalogue.tables().stream().filter(t -> !"ARCHIVED".equals(t.status())).toList();
        long codes = tables.stream().mapToLong(TableDef::entryCount).sum();
        long actifs = tables.stream().mapToLong(TableDef::activeCount).sum();
        Map<String, Long> bsp = new LinkedHashMap<>();
        for (String p : List.of("BUY", "SHIP", "PAY")) bsp.put(p, tables.stream().filter(t -> t.bspPhases().contains(p)).count());
        Map<String, Long> sources = new TreeMap<>();
        tables.forEach(t -> sources.merge(t.source(), 1L, Long::sum));
        long semaine = jdbc.sql("select count(*) from ref_history where occurred_at >= now() - interval '7 days' and channel <> 'SEED'")
                .query(Long.class).single();
        List<Map<String, Object>> plusGrandes = tables.stream().sorted(Comparator.comparingLong(TableDef::entryCount).reversed()).limit(6)
                .map(t -> resume(t, "entryCount", t.entryCount())).toList();
        List<Map<String, Object>> aCompleter = tables.stream().sorted(Comparator.comparingInt(TableDef::completeness)).limit(6)
                .map(t -> resume(t, "completeness", t.completeness())).toList();
        int moyenne = (int) Math.round(tables.stream().mapToInt(TableDef::completeness).average().orElse(0));
        return new Indicateurs(tables.size(), tables.stream().filter(t -> t.entryCount() > 0).count(), codes, actifs, codes - actifs,
                semaine, moyenne, bsp, sources, plusGrandes, aCompleter, historique.activite(30),
                historique.evenements(new HistoryService.Filtre(null, null, null, null, null, "!SEED", null, null, null), 0, 8).items());
    }
}
