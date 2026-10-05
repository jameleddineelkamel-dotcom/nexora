package com.nexora.refdata.io;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexora.refdata.audit.AuditContext;
import com.nexora.refdata.catalogue.CatalogueModel.ColumnDef;
import com.nexora.refdata.catalogue.CatalogueModel.TableDef;
import com.nexora.refdata.catalogue.CatalogueRepository;
import com.nexora.refdata.catalogue.CatalogueService;
import com.nexora.refdata.entry.Entry;
import com.nexora.refdata.entry.EntryRepository;
import com.nexora.refdata.entry.EntryValidator;
import com.nexora.refdata.web.Json;
import com.nexora.refdata.web.RefdataException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Chargement des listes de codes (exigence e-Guce+_REF-05) : CSV, JSON ou Excel, en fusion ou en
 * remplacement, avec simulation préalable. Aucun code n'est supprimé : en remplacement, les codes absents
 * du fichier sont invalidés.
 */
@Service
public class ImportService {

    public enum Mode { MERGE, REPLACE }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Resultat(String importId, String tableCode, String fileName, String format, Mode mode, boolean dryRun,
                           int total, int created, int updated, int unchanged, int invalidated, int rejected,
                           List<String> errors, List<String> warnings, Map<String, String> mapping) {}

    private static final int MAX_ERREURS = 200;
    private static final String CHAMP_CODE = "code", CHAMP_FR = "labelFr", CHAMP_EN = "labelEn", CHAMP_PARENT = "parentCode",
            CHAMP_DEBUT = "validFrom", CHAMP_FIN = "validTo", CHAMP_STATUT = "status", PREFIXE_ATTR = "attr:";

    private final CatalogueService catalogue;
    private final CatalogueRepository catalogueRepo;
    private final EntryRepository entrees;
    private final AuditContext audit;
    private final JdbcClient jdbc;
    private final Json json;

    public ImportService(CatalogueService catalogue, CatalogueRepository catalogueRepo, EntryRepository entrees,
                         AuditContext audit, JdbcClient jdbc, Json json) {
        this.catalogue = catalogue;
        this.catalogueRepo = catalogueRepo;
        this.entrees = entrees;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public Resultat importer(String tableCode, String nomFichier, byte[] contenu, Mode mode, boolean simulation, String motif) {
        TableDef t = catalogue.table(tableCode);
        if ("ARCHIVED".equals(t.status())) throw RefdataException.conflit("La table « " + t.code() + " » est archivée.");
        FichierTabulaire f = FichierTabulaire.lire(nomFichier, contenu, json);
        if (f.lignes().isEmpty()) throw RefdataException.invalide("Le fichier ne contient aucune ligne de données.");

        Map<String, String> correspondance = correspondance(t, f.entetes());
        List<String> avertissements = new ArrayList<>();
        f.entetes().stream().filter(h -> !correspondance.containsKey(h))
                .forEach(h -> avertissements.add("Colonne « " + h + " » ignorée : elle ne correspond à aucune colonne de " + t.code() + "."));
        if (!correspondance.containsValue(CHAMP_CODE))
            throw RefdataException.invalide("Aucune colonne du fichier ne correspond au code de la table (« "
                    + t.colonne("CODE").key() + " », « " + t.colonne("CODE").labelFr() + " » ou « code »).");

        long tableId = catalogueRepo.idDe(t.code());
        Map<String, Entry> existantes = entrees.toutes(tableId);
        Set<String> codesFichier = new HashSet<>();
        for (Map<String, String> l : f.lignes()) {
            String c = valeur(l, correspondance, CHAMP_CODE);
            if (c != null) codesFichier.add(c);
        }
        Resolveur resolveur = new Resolveur(t, codesFichier);

        List<Entry> aCreer = new ArrayList<>();
        List<Entry[]> aModifier = new ArrayList<>(); // [ancienne, nouvelle]
        List<String> erreurs = new ArrayList<>();
        int inchangees = 0, rejetees = 0;
        Set<String> vus = new HashSet<>();
        int numero = f.format().equals("JSON") ? 0 : 1;
        for (Map<String, String> l : f.lignes()) {
            numero++;
            String code = valeur(l, correspondance, CHAMP_CODE);
            String ref = "Ligne " + numero + (code == null ? "" : " (" + code + ")") + " : ";
            List<String> e = new ArrayList<>();
            if (code != null && !vus.add(code)) {
                rejetees++;
                ajouter(erreurs, ref + "code en double dans le fichier.");
                continue;
            }
            Entry ancienne = code == null ? null : existantes.get(code);
            Entry saisie = fusionner(ancienne, l, correspondance, code, e);
            EntryValidator.Resultat r = EntryValidator.valider(t, saisie, resolveur);
            e.addAll(r.erreurs());
            if (!e.isEmpty()) {
                rejetees++;
                ajouter(erreurs, ref + String.join(" ", e));
                continue;
            }
            if (ancienne == null) aCreer.add(r.entree());
            else if (ancienne.memesDonnees(r.entree())) inchangees++;
            else aModifier.add(new Entry[]{ancienne, r.entree()});
        }
        List<Entry> aInvalider = mode == Mode.REPLACE
                ? existantes.values().stream().filter(x -> "ACTIVE".equals(x.status()) && !codesFichier.contains(x.code())).toList()
                : List.of();

        String id = "IMP-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-"
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        if (!simulation) {
            audit.appliquer("IMPORT", motif, id);
            entrees.insererLot(tableId, aCreer);
            for (Entry[] m : aModifier) entrees.modifier(tableId, m[1], null);
            LocalDate aujourdhui = LocalDate.now();
            for (Entry x : aInvalider) {
                LocalDate fin = x.validFrom() != null && x.validFrom().isAfter(aujourdhui) ? x.validFrom() : aujourdhui;
                entrees.modifier(tableId, new Entry(x.code(), x.labelFr(), x.labelEn(), x.parentCode(), x.attributes(), x.validFrom(),
                        fin, "INVALID", null, null, null, null), null);
            }
            jdbc.sql("""
                    insert into ref_import (id, table_code, file_name, format, mode, total, created, updated, unchanged, invalidated,
                           rejected, errors, author, channel, reason)
                    values (:id, :t, :f, :fmt, :mode, :total, :c, :u, :inch, :inv, :rej, cast(:err as jsonb), :auteur, 'IMPORT', :motif)
                    """)
                    .param("id", id).param("t", t.code()).param("f", nomFichier).param("fmt", f.format()).param("mode", mode.name())
                    .param("total", f.lignes().size()).param("c", aCreer.size()).param("u", aModifier.size()).param("inch", inchangees)
                    .param("inv", aInvalider.size()).param("rej", rejetees).param("err", json.ecrire(erreurs))
                    .param("auteur", audit.utilisateur()).param("motif", motif)
                    .update();
        }
        return new Resultat(simulation ? null : id, t.code(), nomFichier, f.format(), mode, simulation, f.lignes().size(),
                aCreer.size(), aModifier.size(), inchangees, aInvalider.size(), rejetees, erreurs, avertissements, correspondance);
    }

    /** Applique sur le code existant les seules colonnes présentes dans le fichier. */
    private static Entry fusionner(Entry ancienne, Map<String, String> l, Map<String, String> corr, String code, List<String> e) {
        Map<String, Object> attributs = new LinkedHashMap<>(ancienne == null || ancienne.attributes() == null ? Map.of() : ancienne.attributes());
        String fr = ancienne == null ? null : ancienne.labelFr(), en = ancienne == null ? null : ancienne.labelEn();
        String parent = ancienne == null ? null : ancienne.parentCode(), statut = ancienne == null ? "ACTIVE" : ancienne.status();
        LocalDate du = ancienne == null ? null : ancienne.validFrom(), au = ancienne == null ? null : ancienne.validTo();
        for (var c : corr.entrySet()) {
            if (!l.containsKey(c.getKey())) continue;
            String v = l.get(c.getKey());
            v = v == null || v.isBlank() ? null : v.strip();
            switch (c.getValue()) {
                case CHAMP_CODE -> { }
                case CHAMP_FR -> fr = v;
                case CHAMP_EN -> en = v;
                case CHAMP_PARENT -> parent = v;
                case CHAMP_DEBUT -> du = v == null ? null : date(v, "Début de validité", e);
                case CHAMP_FIN -> au = v == null ? null : date(v, "Fin de validité", e);
                case CHAMP_STATUT -> statut = v == null ? statut : statutDe(v);
                default -> {
                    String cle = c.getValue().substring(PREFIXE_ATTR.length());
                    if (v == null) attributs.remove(cle); else attributs.put(cle, v);
                }
            }
        }
        return new Entry(code, fr, en, parent, attributs, du, au, statut, null, null, null, null);
    }

    /** En-tête du fichier → champ : par clé, libellé ou balise XML de la colonne, ou par nom standard. */
    static Map<String, String> correspondance(TableDef t, List<String> entetes) {
        Map<String, String> alias = new HashMap<>();
        for (ColumnDef c : t.columns()) {
            String cible = switch (c.role()) {
                case "CODE" -> CHAMP_CODE;
                case "LABEL_FR" -> CHAMP_FR;
                case "LABEL_EN" -> CHAMP_EN;
                default -> PREFIXE_ATTR + c.key();
            };
            for (String a : new String[]{c.key(), c.labelFr(), c.labelEn(), c.xmlTag()}) if (a != null) alias.putIfAbsent(norm(a), cible);
        }
        for (String a : List.of("code", "valeur", "value")) alias.putIfAbsent(a, CHAMP_CODE);
        for (String a : List.of("labelfr", "libelle", "libellefr", "designation", "nom", "description", "label")) alias.putIfAbsent(a, CHAMP_FR);
        for (String a : List.of("labelen", "libelleen", "anglais", "english", "labelenglish", "name")) alias.putIfAbsent(a, CHAMP_EN);
        for (String a : List.of("parentcode", "parent", "codeparent")) alias.putIfAbsent(a, CHAMP_PARENT);
        for (String a : List.of("validfrom", "debutvalidite", "datedebut", "debut")) alias.putIfAbsent(a, CHAMP_DEBUT);
        for (String a : List.of("validto", "finvalidite", "datefin", "fin")) alias.putIfAbsent(a, CHAMP_FIN);
        for (String a : List.of("status", "statut", "etat")) alias.putIfAbsent(a, CHAMP_STATUT);
        if (t.colonne("LABEL_FR") == null) alias.values().removeIf(CHAMP_FR::equals);
        if (t.colonne("LABEL_EN") == null) alias.values().removeIf(CHAMP_EN::equals);
        if (t.parentTableCode() == null) alias.values().removeIf(CHAMP_PARENT::equals);
        Map<String, String> res = new LinkedHashMap<>();
        Set<String> pris = new HashSet<>();
        for (String h : entetes) {
            java.util.regex.Matcher crochets = java.util.regex.Pattern.compile("\\[([A-Za-z][A-Za-z0-9_]*)]\\s*$").matcher(h);
            String cible = crochets.find() && alias.containsKey(norm(crochets.group(1))) ? alias.get(norm(crochets.group(1))) : alias.get(norm(h));
            if (cible != null && pris.add(cible)) res.put(h, cible);
        }
        return res;
    }

    static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String valeur(Map<String, String> l, Map<String, String> corr, String champ) {
        for (var c : corr.entrySet()) if (c.getValue().equals(champ)) {
            String v = l.get(c.getKey());
            return v == null || v.isBlank() ? null : v.strip();
        }
        return null;
    }

    private static LocalDate date(String v, String nom, List<String> e) {
        String iso = EntryValidator.date(v, nom, e);
        return iso == null ? null : LocalDate.parse(iso);
    }

    private static String statutDe(String v) {
        String s = norm(v);
        return switch (s) {
            case "active", "actif", "valide", "a" -> "ACTIVE";
            case "invalid", "invalide", "inactif", "inactive", "i" -> "INVALID";
            default -> v.toUpperCase(Locale.ROOT);
        };
    }

    private static void ajouter(List<String> erreurs, String message) {
        if (erreurs.size() < MAX_ERREURS) erreurs.add(message);
        else if (erreurs.size() == MAX_ERREURS) erreurs.add("… erreurs suivantes non affichées.");
    }

    /** Références résolues en mémoire : codes des tables visées, plus ceux du fichier pour les hiérarchies internes. */
    private final class Resolveur implements EntryValidator.Resolveur {
        private final Map<String, Set<String>> cache = new HashMap<>();
        private final String table;
        private final Set<String> codesFichier;

        Resolveur(TableDef t, Set<String> codesFichier) {
            this.table = t.code();
            this.codesFichier = codesFichier;
        }

        @Override
        public boolean existe(String tableVisee, String code) {
            if (tableVisee.equals(table) && codesFichier.contains(code)) return true;
            return cache.computeIfAbsent(tableVisee, k -> {
                Long id = catalogueRepo.idDe(k);
                return id == null ? Set.of() : entrees.codes(id);
            }).contains(code);
        }
    }
}
