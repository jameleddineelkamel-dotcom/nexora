package com.nexora.refdata.catalogue;

import com.nexora.refdata.audit.AuditContext;
import com.nexora.refdata.catalogue.CatalogueModel.Category;
import com.nexora.refdata.catalogue.CatalogueModel.ColumnDef;
import com.nexora.refdata.catalogue.CatalogueModel.TableDef;
import com.nexora.refdata.catalogue.CatalogueModel.TableSummary;
import com.nexora.refdata.web.RefdataException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class CatalogueService {

    private static final Pattern CODE_TABLE = Pattern.compile("^REF_[A-Z0-9_]{2,56}$");
    private static final Pattern CODE_CATEGORIE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Pattern CLE_COLONNE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,59}$");

    private final CatalogueRepository repo;
    private final AuditContext audit;
    private final JdbcClient jdbc;

    public CatalogueService(CatalogueRepository repo, AuditContext audit, JdbcClient jdbc) {
        this.repo = repo;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // ---------- Lecture ----------
    @Transactional(readOnly = true)
    public List<Category> categories() {
        return repo.categories();
    }

    @Transactional(readOnly = true)
    public List<TableSummary> resumes(String categorie, String source, String phase, String statut) {
        Set<String> categories = categorie == null ? null : descendants(categorie);
        return repo.tables().stream()
                .filter(t -> categories == null || categories.contains(t.categoryCode()))
                .filter(t -> source == null || source.equals(t.source()))
                .filter(t -> phase == null || t.bspPhases().contains(phase))
                .filter(t -> statut == null ? !"ARCHIVED".equals(t.status()) : "ALL".equals(statut) || statut.equals(t.status()))
                .map(t -> new TableSummary(t.code(), t.number(), t.nameFr(), t.nameEn(), t.description(), t.categoryCode(),
                        t.source(), t.bspPhases(), t.status(), t.contentType(), t.standards(), t.entryCount(), t.activeCount(),
                        t.completeness(), t.updatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public TableDef table(String code) {
        return repo.table(code.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> RefdataException.introuvable("Table « " + code + " » introuvable dans le référentiel."));
    }

    @Transactional(readOnly = true)
    public List<TableDef> definitions() {
        return repo.tables();
    }

    /** Une catégorie et toutes ses sous-catégories. */
    public Set<String> descendants(String code) {
        Set<String> res = new HashSet<>(Set.of(code));
        List<Category> toutes = repo.categories();
        boolean ajout = true;
        while (ajout) {
            ajout = false;
            for (Category c : toutes) if (c.parentCode() != null && res.contains(c.parentCode()) && res.add(c.code())) ajout = true;
        }
        return res;
    }

    // ---------- Catégories ----------
    @Transactional
    public Category creerCategorie(Category c) {
        List<String> e = new ArrayList<>();
        if (c.code() == null || !CODE_CATEGORIE.matcher(c.code()).matches()) e.add("Code de catégorie : lettres majuscules, chiffres et « _ » (2 à 40 caractères).");
        else if (repo.categorieExiste(c.code())) e.add("La catégorie « " + c.code() + " » existe déjà.");
        validerCategorie(c, e);
        if (!e.isEmpty()) throw RefdataException.invalide(e);
        audit.appliquer();
        repo.insererCategorie(c);
        return repo.categories().stream().filter(x -> x.code().equals(c.code())).findFirst().orElseThrow();
    }

    @Transactional
    public Category modifierCategorie(String code, Category c) {
        List<String> e = new ArrayList<>();
        validerCategorie(c, e);
        if (code.equals(c.parentCode()) || (c.parentCode() != null && descendants(code).contains(c.parentCode())))
            e.add("Une catégorie ne peut pas être rangée sous elle-même.");
        if (!e.isEmpty()) throw RefdataException.invalide(e);
        audit.appliquer();
        if (repo.modifierCategorie(code, c) == 0) throw RefdataException.introuvable("Catégorie « " + code + " » introuvable.");
        return repo.categories().stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow();
    }

    private void validerCategorie(Category c, List<String> e) {
        if (blanc(c.labelFr())) e.add("Le libellé français de la catégorie est obligatoire.");
        if (!CatalogueModel.SOURCES.contains(c.source())) e.add("Source : INTERNATIONALE, REGIONALE ou NATIONALE.");
        if (c.parentCode() != null && !repo.categorieExiste(c.parentCode())) e.add("Catégorie parente « " + c.parentCode() + " » inconnue.");
    }

    // ---------- Tables (création dynamique) ----------
    @Transactional
    public TableDef creer(TableDef saisie) {
        TableDef t = normaliser(saisie, true);
        List<String> e = new ArrayList<>();
        if (!CODE_TABLE.matcher(t.code()).matches()) e.add("Le code de table doit suivre le format REF_XXX (majuscules, chiffres, « _ »).");
        else if (repo.idDe(t.code()) != null) e.add("La table « " + t.code() + " » existe déjà.");
        valider(t, e);
        if (!e.isEmpty()) throw RefdataException.invalide(e);
        audit.appliquer();
        long id = repo.insererTable(t);
        if (t.parentTableCode() != null) repo.definirParent(t.code(), t.parentTableCode());
        repo.enregistrerColonnes(id, t.columns());
        repo.genererVue(t.code());
        return table(t.code());
    }

    /** Modifie l'identité, la fiche de métadonnées et la structure d'une table. */
    @Transactional
    public TableDef modifier(String code, TableDef saisie, boolean forcer) {
        TableDef actuelle = table(code);
        TableDef t = normaliser(new TableDef(actuelle.code(), saisie.number(), saisie.nameFr(), saisie.nameEn(), saisie.description(),
                saisie.categoryCode(), saisie.source(), saisie.standards(), saisie.producer(), saisie.updateAuthority(),
                saisie.obtentionMode(), saisie.updateMode(), saisie.bspPhases(), saisie.parentTableCode(), saisie.metadata(),
                actuelle.sourceDocument(), saisie.dataSource() != null ? saisie.dataSource() : actuelle.dataSource(),
                saisie.status(), saisie.columns() != null ? saisie.columns() : actuelle.columns(), saisie.version(),
                null, null, 0, 0, 0, null, null), false);
        List<String> e = new ArrayList<>();
        valider(t, e);
        long id = repo.idDe(actuelle.code());
        // Colonnes retirées ou changées de type : protection des données existantes
        List<String> aPurger = new ArrayList<>();
        for (ColumnDef ancienne : actuelle.attributs()) {
            ColumnDef nouvelle = t.columns().stream().filter(c -> c.key().equals(ancienne.key())).findFirst().orElse(null);
            if (nouvelle == null || !nouvelle.attribut()) {
                long n = repo.valeursAttribut(id, ancienne.key());
                if (n > 0 && !forcer) e.add("La colonne « " + ancienne.key() + " » contient des valeurs pour " + n
                        + " code(s) : confirmez son retrait (les valeurs seront effacées, l'historique les conserve).");
                else if (n > 0) aPurger.add(ancienne.key());
            } else if (!nouvelle.dataType().equals(ancienne.dataType())) {
                long n = repo.valeursIncompatibles(id, ancienne.key(), nouvelle.dataType());
                if (n > 0) e.add("La colonne « " + ancienne.key() + " » contient " + n + " valeur(s) incompatibles avec le type " + nouvelle.dataType() + ".");
            }
        }
        if (!e.isEmpty()) throw RefdataException.invalide(e);
        audit.appliquer();
        if (repo.modifierTable(actuelle.code(), t, saisie.version() == null ? actuelle.version() : saisie.version()) == 0)
            throw RefdataException.conflit("La table a été modifiée par quelqu'un d'autre entre-temps. Rechargez la page.");
        for (String cle : aPurger) {
            jdbc.sql("update ref_entry set attributes = attributes - :k where table_id = :t and jsonb_exists(attributes, :k)")
                    .param("k", cle).param("t", id).update();
        }
        repo.enregistrerColonnes(id, t.columns());
        repo.genererVue(t.code());
        return table(t.code());
    }

    /** Archivage (pas de suppression) : la table disparaît des listes et sa vue SQL est retirée. */
    @Transactional
    public TableDef changerStatut(String code, String statut) {
        TableDef t = table(code);
        if (!CatalogueModel.STATUTS.contains(statut)) throw RefdataException.invalide("Statut : ACTIVE, DRAFT ou ARCHIVED.");
        if ("ARCHIVED".equals(statut)) {
            long dependantes = repo.tables().stream().filter(x -> !x.code().equals(t.code()) && !"ARCHIVED".equals(x.status()))
                    .filter(x -> t.code().equals(x.parentTableCode()) || x.columns().stream().anyMatch(c -> t.code().equals(c.refTableCode())))
                    .count();
            if (dependantes > 0) throw RefdataException.conflit(dependantes + " table(s) active(s) font référence à " + t.code() + " : archivez-les ou retirez la référence d'abord.");
        }
        audit.appliquer();
        jdbc.sql("update ref_table set status = :s where code = :c").param("s", statut).param("c", t.code()).update();
        repo.genererVue(t.code());
        return table(t.code());
    }

    // ---------- Règles ----------
    private TableDef normaliser(TableDef s, boolean creation) {
        String code = s.code() == null ? "" : s.code().strip().toUpperCase(Locale.ROOT);
        if (creation && !code.isEmpty() && !code.startsWith("REF_")) code = "REF_" + code;
        String source = s.source();
        if (blanc(source) && s.categoryCode() != null) {
            source = repo.categories().stream().filter(c -> c.code().equals(s.categoryCode())).map(Category::source).findFirst().orElse(null);
        }
        List<ColumnDef> colonnes = new ArrayList<>();
        List<ColumnDef> brutes = s.columns() == null ? List.of() : s.columns();
        for (int i = 0; i < brutes.size(); i++) {
            ColumnDef c = brutes.get(i);
            String type = blanc(c.dataType()) ? "STRING" : c.dataType().toUpperCase(Locale.ROOT);
            String role = blanc(c.role()) ? "ATTRIBUTE" : c.role().toUpperCase(Locale.ROOT);
            colonnes.add(new ColumnDef(c.key() == null ? null : c.key().strip(), vide(c.labelFr()), vide(c.labelEn()), role, type,
                    c.maxLength(), vide(c.pattern()), "CODE".equals(role) || c.required(), vide(c.cardinality()), vide(c.xmlTag()),
                    vide(c.untded()), vide(c.unit()), vide(c.definition()), vide(c.comment()),
                    "CODE_REF".equals(type) ? vide(c.refTableCode()) : null, i + 1));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        if (s.metadata() != null) s.metadata().forEach((k, v) -> { if (v != null && !v.toString().isBlank()) meta.put(k, v); });
        List<String> phases = s.bspPhases() == null ? List.of()
                : CatalogueModel.PHASES_BSP.stream().filter(p -> s.bspPhases().contains(p)).toList();
        return new TableDef(code, vide(s.number()), vide(s.nameFr()), vide(s.nameEn()), vide(s.description()), s.categoryCode(),
                source, vide(s.standards()), vide(s.producer()), vide(s.updateAuthority()), vide(s.obtentionMode()),
                vide(s.updateMode()), phases, vide(s.parentTableCode()), meta, vide(s.sourceDocument()), vide(s.dataSource()),
                blanc(s.status()) ? "ACTIVE" : s.status().toUpperCase(Locale.ROOT), colonnes, s.version(),
                null, null, 0, 0, 0, null, null);
    }

    private void valider(TableDef t, List<String> e) {
        if (blanc(t.nameFr())) e.add("Le nom français de la table est obligatoire.");
        if (t.categoryCode() == null || !repo.categorieExiste(t.categoryCode())) e.add("Catégorie inconnue : choisissez une catégorie du référentiel.");
        if (!CatalogueModel.SOURCES.contains(t.source())) e.add("Source : INTERNATIONALE, REGIONALE ou NATIONALE.");
        if (!CatalogueModel.STATUTS.contains(t.status())) e.add("Statut : ACTIVE, DRAFT ou ARCHIVED.");
        if (t.parentTableCode() != null && !t.parentTableCode().equals(t.code()) && repo.idDe(t.parentTableCode()) == null)
            e.add("Table parente « " + t.parentTableCode() + " » inconnue.");
        if (t.columns().isEmpty()) { e.add("La table doit comporter au moins une colonne (le code)."); return; }
        Set<String> cles = new HashSet<>();
        Map<String, Integer> roles = new HashMap<>();
        for (ColumnDef c : t.columns()) {
            String nom = c.key() == null ? "?" : c.key();
            if (c.key() == null || !CLE_COLONNE.matcher(c.key()).matches())
                e.add("Clé de colonne « " + nom + " » invalide : lettre initiale puis lettres, chiffres ou « _ » (60 caractères au plus).");
            else if (!cles.add(c.key().toLowerCase(Locale.ROOT))) e.add("Clé de colonne « " + nom + " » en double.");
            if (blanc(c.labelFr())) e.add("Le libellé de la colonne « " + nom + " » est obligatoire.");
            if (!CatalogueModel.ROLES.contains(c.role())) e.add("Rôle de la colonne « " + nom + " » invalide.");
            else roles.merge(c.role(), 1, Integer::sum);
            if (!CatalogueModel.TYPES.contains(c.dataType())) e.add("Type de la colonne « " + nom + " » invalide.");
            if (c.maxLength() != null && c.maxLength() <= 0) e.add("Longueur maximale de « " + nom + " » invalide.");
            if (c.pattern() != null) {
                try { Pattern.compile(c.pattern()); } catch (PatternSyntaxException ex) { e.add("Expression régulière de « " + nom + " » invalide."); }
            }
            if ("CODE_REF".equals(c.dataType())) {
                if (c.refTableCode() == null) e.add("La colonne « " + nom + " » doit indiquer la table de référence visée.");
                else if (!c.refTableCode().equals(t.code()) && repo.idDe(c.refTableCode()) == null)
                    e.add("Table de référence « " + c.refTableCode() + " » inconnue pour la colonne « " + nom + " ».");
            }
            if (!c.attribut() && !Set.of("STRING", "TEXT").contains(c.dataType()))
                e.add("La colonne « " + nom + " » (" + c.role() + ") doit être de type texte.");
        }
        if (roles.getOrDefault("CODE", 0) != 1) e.add("Une et une seule colonne doit porter le rôle CODE (clé de la table).");
        roles.forEach((r, n) -> { if (!"ATTRIBUTE".equals(r) && !"CODE".equals(r) && n > 1) e.add("Le rôle " + r + " ne peut être attribué qu'à une colonne."); });
    }

    static boolean blanc(String s) {
        return s == null || s.isBlank();
    }

    static String vide(String s) {
        return blanc(s) ? null : s.strip();
    }
}
