package com.nexora.refdata.seed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.nexora.refdata.audit.AuditContext;
import com.nexora.refdata.catalogue.CatalogueModel.Category;
import com.nexora.refdata.catalogue.CatalogueModel.ColumnDef;
import com.nexora.refdata.catalogue.CatalogueModel.TableDef;
import com.nexora.refdata.catalogue.CatalogueRepository;
import com.nexora.refdata.entry.Entry;
import com.nexora.refdata.entry.EntryRepository;
import com.nexora.refdata.web.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Chargement initial du Référentiel Commun lorsque la base est vide : catégories, tables REF_* et fiches
 * ISO 19115 (rapport GUCE V1.5), puis données des classeurs UN/CEFACT et NACAM (seed/ produit par
 * tools/seed/construire-seed.mjs).
 */
@Component
public class SeedLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);
    private static final String MOTIF = "Chargement initial — Rapport Référentiel Commun GUCE V1.5, classeurs UN/CEFACT et NACAM";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Catalogue(List<Categorie> categories, List<Table> tables, List<String> sources) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Categorie(String code, String parent, String labelFr, String labelEn, String source, String icon, String color,
                     Integer order, String description) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Table(String code, String number, String nameFr, String nameEn, String description, String category, String source,
                 String standards, String producer, String updateAuthority, String obtentionMode, String updateMode,
                 List<String> bspPhases, String parentTable, Map<String, Object> metadata, String sourceDocument,
                 String dataSource, List<Colonne> columns, String ficheTitle) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Colonne(String key, String labelFr, String labelEn, String role, String dataType, Integer maxLength, String pattern,
                   Boolean required, String cardinality, String xmlTag, String untded, String unit, String definition,
                   String comment, String refTable, Integer order) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ligne(String c, String fr, String en, String p, Map<String, Object> a) {}

    static final String CARDINALITES_SELON_DONNEES = """
            update ref_column c set required = false,
                   comment = concat_ws(' — ', c.comment, 'Cardinalité ' || coalesce(c.cardinality, '(1,1)')
                             || ' dans le rapport, non renseignée dans la source : rendue facultative')
             where c.role = 'ATTRIBUTE' and c.required
               and exists (select 1 from ref_entry e where e.table_id = c.table_id and not jsonb_exists(e.attributes, c.key))
            """;

    private final CatalogueRepository catalogue;
    private final EntryRepository entrees;
    private final AuditContext audit;
    private final JdbcClient jdbc;
    private final Json json;
    private final TransactionTemplate tx;
    private final boolean actif;

    public SeedLoader(CatalogueRepository catalogue, EntryRepository entrees, AuditContext audit, JdbcClient jdbc, Json json,
                      TransactionTemplate tx, @Value("${nexora.refdata.seed.enabled:true}") boolean actif) {
        this.catalogue = catalogue;
        this.entrees = entrees;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = tx;
        this.actif = actif;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!actif) return;
        long tables = jdbc.sql("select count(*) from ref_table").query(Long.class).single();
        if (tables > 0) return;
        ClassPathResource ressource = new ClassPathResource("seed/catalogue.json");
        if (!ressource.exists()) { log.warn("Jeu initial absent (seed/catalogue.json) : référentiel vide."); return; }
        long debut = System.currentTimeMillis();
        Catalogue cat;
        try (InputStream in = ressource.getInputStream()) {
            cat = json.lire(in, Catalogue.class);
        }
        tx.executeWithoutResult(s -> charger(cat));
        log.info("Référentiel Commun chargé en {} ms.", System.currentTimeMillis() - debut);
    }

    private void charger(Catalogue cat) {
        audit.appliquer("SEED", MOTIF, "SEED-INITIAL");
        // 1. Catégories (parents d'abord)
        List<Categorie> categories = new ArrayList<>(cat.categories());
        categories.sort(Comparator.comparing(c -> c.parent() == null ? 0 : 1));
        for (Categorie c : categories) {
            catalogue.insererCategorie(new Category(c.code(), c.parent(), c.labelFr(), c.labelEn(), c.description(), c.source(),
                    c.icon(), c.color(), c.order() == null ? 0 : c.order(), 0, 0));
        }
        // 2. Tables, puis hiérarchies et colonnes (les références visent des tables déjà créées)
        Map<String, Long> ids = new HashMap<>();
        for (Table t : cat.tables()) {
            Map<String, Object> meta = new LinkedHashMap<>(t.metadata() == null ? Map.of() : t.metadata());
            if (t.ficheTitle() != null) meta.put("ficheTitle", t.ficheTitle());
            ids.put(t.code(), catalogue.insererTable(new TableDef(t.code(), t.number(), t.nameFr(), t.nameEn(), t.description(),
                    t.category(), t.source(), t.standards(), t.producer(), t.updateAuthority(), t.obtentionMode(), t.updateMode(),
                    t.bspPhases() == null ? List.of() : t.bspPhases(), null, meta, t.sourceDocument(), t.dataSource(), "ACTIVE",
                    List.of(), null, null, null, 0, 0, 0, null, null)));
        }
        for (Table t : cat.tables()) {
            if (t.parentTable() != null) catalogue.definirParent(t.code(), t.parentTable());
            List<ColumnDef> colonnes = new ArrayList<>();
            for (Colonne c : t.columns()) {
                colonnes.add(new ColumnDef(c.key(), c.labelFr(), c.labelEn(), c.role() == null ? "ATTRIBUTE" : c.role(),
                        c.dataType() == null ? "STRING" : c.dataType(), c.maxLength(), c.pattern(),
                        Boolean.TRUE.equals(c.required()), c.cardinality(), c.xmlTag(), c.untded(), c.unit(), c.definition(),
                        c.comment(), "CODE_REF".equals(c.dataType()) ? c.refTable() : null, c.order() == null ? colonnes.size() + 1 : c.order()));
            }
            catalogue.enregistrerColonnes(ids.get(t.code()), colonnes);
        }
        // 3. Données : historique ligne à ligne désactivé, un enregistrement par table dans le journal des chargements
        audit.historique(false);
        audit.synchronisation(false); // les tables physiques sont générées en une fois à la fin
        long total = 0;
        for (Table t : cat.tables()) {
            ClassPathResource fichier = new ClassPathResource("seed/data/" + t.code() + ".jsonl.gz");
            if (!fichier.exists()) continue;
            List<Entry> lot = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(fichier.getInputStream()), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    if (l.isBlank()) continue;
                    Ligne x = json.lire(l, Ligne.class);
                    lot.add(new Entry(x.c(), x.fr(), x.en(), x.p(), x.a() == null ? Map.of() : x.a(), null, null, "ACTIVE",
                            null, null, null, null));
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            entrees.insererLot(ids.get(t.code()), lot);
            total += lot.size();
            jdbc.sql("""
                    insert into ref_import (id, table_code, file_name, format, mode, total, created, author, channel, reason)
                    values (:id, :t, :f, 'XLSX', 'SEED', :n, :n, 'systeme', 'SEED', :motif)
                    """)
                    .param("id", "SEED-" + t.code()).param("t", t.code()).param("f", t.dataSource()).param("n", lot.size())
                    .param("motif", MOTIF).update();
        }
        // Cardinalités du rapport démenties par les données sources : la colonne devient facultative
        int relachees = jdbc.sql(CARDINALITES_SELON_DONNEES).update();
        audit.historique(true);
        log.info("{} colonne(s) obligatoire(s) selon le rapport mais non renseignée(s) dans les sources : rendues facultatives.", relachees);
        // 4. Tables physiques referentiel.ref_xxx
        for (Table t : cat.tables()) catalogue.genererVue(t.code());
        audit.synchronisation(true);
        log.info("Chargement initial : {} catégories, {} tables, {} codes.", categories.size(), cat.tables().size(), total);
    }
}
