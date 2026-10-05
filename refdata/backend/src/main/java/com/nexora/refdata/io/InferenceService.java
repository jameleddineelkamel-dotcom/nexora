package com.nexora.refdata.io;

import com.nexora.refdata.catalogue.CatalogueModel.ColumnDef;
import com.nexora.refdata.web.Json;
import com.nexora.refdata.web.RefdataException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Assistant de création de table : à partir d'un fichier d'exemple, propose la structure (clés, types,
 * longueurs, colonne code et libellés) et détecte les colonnes qui référencent une table existante.
 */
@Service
public class InferenceService {

    public record Proposition(String suggestedCode, String suggestedName, String format, int rowCount, List<ColumnDef> columns,
                              List<Map<String, String>> sample, Map<String, String> references) {}

    private static final Pattern ENTIER = Pattern.compile("^-?\\d{1,18}$");
    private static final Pattern DECIMAL = Pattern.compile("^-?\\d+([.,]\\d+)?$");
    private static final Pattern DATE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}|\\d{2}/\\d{2}/\\d{4})$");
    private static final Set<String> BOOLEENS = Set.of("true", "false", "oui", "non", "yes", "no", "vrai", "faux");
    private static final int ECHANTILLON = 5000;

    private final JdbcClient jdbc;
    private final Json json;

    public InferenceService(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Proposition proposer(String nomFichier, byte[] contenu) {
        FichierTabulaire f = FichierTabulaire.lire(nomFichier, contenu, json);
        if (f.entetes().isEmpty() || f.lignes().isEmpty()) throw RefdataException.invalide("Le fichier ne contient pas de données exploitables.");
        List<Map<String, String>> lignes = f.lignes().subList(0, Math.min(ECHANTILLON, f.lignes().size()));

        List<ColumnDef> colonnes = new ArrayList<>();
        Map<String, String> references = new LinkedHashMap<>();
        Set<String> cles = new HashSet<>();
        String colCode = null, colFr = null, colEn = null;
        int ordre = 0;
        for (String h : f.entetes()) {
            List<String> valeurs = lignes.stream().map(l -> l.getOrDefault(h, "")).map(String::strip).toList();
            List<String> remplies = valeurs.stream().filter(v -> !v.isEmpty()).toList();
            int max = remplies.stream().mapToInt(String::length).max().orElse(0);
            boolean complete = remplies.size() == valeurs.size();
            boolean unique = complete && new HashSet<>(remplies).size() == remplies.size();
            String type = typer(remplies, max);
            String cle = cle(h, cles);
            String n = norm(h);

            String role = "ATTRIBUTE";
            if (colCode == null && unique && max <= 35 && !"TEXT".equals(type) && !"DATE".equals(type)) { role = "CODE"; colCode = h; type = "STRING"; }
            else if (colEn == null && n.matches(".*(anglais|english|\\ben\\b|labelen|name).*") && "STRING".equals(type)) { role = "LABEL_EN"; colEn = h; }
            else if (colFr == null && ("STRING".equals(type) || "TEXT".equals(type)) && remplies.stream().anyMatch(v -> v.contains(" ") || v.length() > 3)
                    && !n.matches(".*(anglais|english).*")) { role = "LABEL_FR"; colFr = h; type = "STRING"; }

            String ref = null;
            if ("ATTRIBUTE".equals(role) && "STRING".equals(type) && max <= 12 && !remplies.isEmpty()) {
                ref = tableReferencee(new HashSet<>(remplies));
                if (ref != null) { type = "CODE_REF"; references.put(cle, ref); }
            }
            int longueur = Math.max(1, (int) Math.ceil(max * 1.5 / 5.0) * 5);
            colonnes.add(new ColumnDef(cle, h, null, role, type, Set.of("STRING", "CODE_REF").contains(type) ? Math.max(longueur, max) : null,
                    null, "CODE".equals(role) || ("LABEL_FR".equals(role) && complete), null, null, null, null, null,
                    "Proposé à partir de « " + nomFichier + " » (" + remplies.size() + " valeurs)", ref, ++ordre));
        }
        // Sans colonne unique, la première colonne devient le code (les doublons seront signalés à l'import)
        if (colCode == null && !colonnes.isEmpty()) {
            ColumnDef c = colonnes.getFirst();
            colonnes.set(0, new ColumnDef(c.key(), c.labelFr(), c.labelEn(), "CODE", "STRING", c.maxLength() == null ? 35 : c.maxLength(),
                    null, true, null, null, null, null, null, c.comment() + " — valeurs non uniques", null, c.sortOrder()));
            references.remove(c.key());
        }
        String base = nomFichier == null ? "NOUVELLE_TABLE" : nomFichier.replaceAll("\\.[^.]+$", "");
        String code = "REF_" + norm(base).toUpperCase(Locale.ROOT).replaceAll("^REF", "");
        code = code.length() > 60 ? code.substring(0, 60) : code;
        String nom = base.replace('_', ' ').replace('-', ' ').strip();
        return new Proposition(code.length() < 6 ? "REF_NOUVELLE_TABLE" : code, nom.isEmpty() ? "Nouvelle table" : Character.toUpperCase(nom.charAt(0)) + nom.substring(1),
                f.format(), f.lignes().size(), colonnes, f.lignes().subList(0, Math.min(10, f.lignes().size())), references);
    }

    private static String typer(List<String> v, int max) {
        if (v.isEmpty()) return "STRING";
        if (v.stream().allMatch(x -> BOOLEENS.contains(x.toLowerCase(Locale.ROOT)))) return "BOOLEAN";
        // Les codes numériques à zéros initiaux (« 004 ») restent du texte
        if (v.stream().allMatch(x -> ENTIER.matcher(x).matches()) && v.stream().noneMatch(x -> x.length() > 1 && x.startsWith("0"))) return "INTEGER";
        if (v.stream().allMatch(x -> DECIMAL.matcher(x).matches()) && v.stream().anyMatch(x -> x.contains(".") || x.contains(","))) return "DECIMAL";
        if (v.stream().allMatch(x -> DATE.matcher(x).matches())) return "DATE";
        return max > 500 ? "TEXT" : "STRING";
    }

    /** Table dont les codes couvrent toutes les valeurs distinctes de la colonne (au moins 3 valeurs). */
    private String tableReferencee(Set<String> valeurs) {
        if (valeurs.size() < 3 || valeurs.size() > 2000) return null;
        return jdbc.sql("""
                select t.code from ref_entry e join ref_table t on t.id = e.table_id
                 where e.code = any(cast(:v as text[])) and t.status <> 'ARCHIVED'
                 group by t.id, t.code having count(distinct e.code) = :n
                 order by (select count(*) from ref_entry x where x.table_id = t.id) asc limit 1
                """)
                .param("v", valeurs.toArray(String[]::new)).param("n", valeurs.size())
                .query(String.class).optional().orElse(null);
    }

    private static String cle(String entete, Set<String> pris) {
        String[] mots = norm(entete).isEmpty() ? new String[]{"colonne"} : Normalizer.normalize(entete, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]+", " ").strip().split(" ");
        StringBuilder sb = new StringBuilder();
        for (String m : mots) {
            if (m.isEmpty()) continue;
            sb.append(sb.isEmpty() ? m.toLowerCase(Locale.ROOT) : Character.toUpperCase(m.charAt(0)) + m.substring(1).toLowerCase(Locale.ROOT));
        }
        String base = sb.isEmpty() || !Character.isLetter(sb.charAt(0)) ? "c" + sb : sb.toString();
        base = base.length() > 50 ? base.substring(0, 50) : base;
        String k = base;
        for (int i = 2; !pris.add(k.toLowerCase(Locale.ROOT)); i++) k = base + i;
        return k;
    }

    private static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
    }
}
