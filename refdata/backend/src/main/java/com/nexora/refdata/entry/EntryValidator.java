package com.nexora.refdata.entry;

import com.nexora.refdata.catalogue.CatalogueModel.ColumnDef;
import com.nexora.refdata.catalogue.CatalogueModel.TableDef;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Contrôle et normalise un code selon la structure de sa table (colonnes typées).
 * Sans accès à la base : les références sont vérifiées par le {@link Resolveur} fourni.
 */
public final class EntryValidator {

    /** Indique si un code existe dans une table de référence. */
    @FunctionalInterface
    public interface Resolveur {
        boolean existe(String table, String code);
    }

    public record Resultat(Entry entree, List<String> erreurs) {
        public boolean valide() { return erreurs.isEmpty(); }
    }

    private static final Map<String, Pattern> MOTIFS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final DateTimeFormatter FR =DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Set<String> VRAI = Set.of("true", "1", "oui", "o", "yes", "y", "vrai", "x");
    private static final Set<String> FAUX = Set.of("false", "0", "non", "n", "no", "faux");

    private EntryValidator() {}

    public static Resultat valider(TableDef table, Entry e, Resolveur resolveur) {
        List<String> err = new ArrayList<>();
        ColumnDef colCode = table.colonne("CODE"), colFr = table.colonne("LABEL_FR"), colEn = table.colonne("LABEL_EN");

        String code = texte(e.code());
        if (code == null) err.add("Le code est obligatoire.");
        else controlerTexte(colCode, code, "Code", err);

        String fr = texte(e.labelFr()), en = texte(e.labelEn());
        if (colFr != null) {
            if (fr == null && colFr.required()) err.add("Le libellé « " + colFr.labelFr() + " » est obligatoire.");
            if (fr != null) controlerTexte(colFr, fr, colFr.labelFr(), err);
        }
        if (colEn != null && en != null) controlerTexte(colEn, en, colEn.labelFr(), err);

        String parent = texte(e.parentCode());
        if (parent != null) {
            if (table.parentTableCode() == null) err.add("Cette table n'est pas hiérarchique : pas de code parent possible.");
            else if (parent.equals(code) && table.parentTableCode().equals(table.code())) err.add("Un code ne peut pas être son propre parent.");
            else if (!resolveur.existe(table.parentTableCode(), parent))
                err.add("Code parent « " + parent + " » inconnu dans " + table.parentTableCode() + ".");
        }

        Map<String, Object> attributs = new LinkedHashMap<>();
        Map<String, Object> saisis = e.attributes() == null ? Map.of() : e.attributes();
        Map<String, ColumnDef> colonnes = new LinkedHashMap<>();
        table.attributs().forEach(c -> colonnes.put(c.key(), c));
        for (String cle : saisis.keySet()) {
            if (!colonnes.containsKey(cle)) err.add("Attribut inconnu « " + cle + " » pour la table " + table.code() + ".");
        }
        for (ColumnDef c : colonnes.values()) {
            Object brut = saisis.get(c.key());
            String v = brut == null ? null : texte(brut.toString());
            if (v == null) {
                if (c.required()) err.add("« " + c.labelFr() + " » est obligatoire.");
                continue;
            }
            Object normalise = switch (c.dataType()) {
                case "INTEGER" -> entier(v, c, err);
                case "DECIMAL" -> decimal(v, c, err);
                case "DATE" -> date(v, c.labelFr(), err);
                case "BOOLEAN" -> booleen(v, c, err);
                case "CODE_REF" -> {
                    if (!resolveur.existe(c.refTableCode(), v)) err.add("« " + c.labelFr() + " » : valeur « " + v + " » inconnue dans " + c.refTableCode() + ".");
                    yield v;
                }
                default -> { controlerTexte(c, v, c.labelFr(), err); yield v; }
            };
            if (normalise != null) attributs.put(c.key(), normalise);
        }

        if (e.validFrom() != null && e.validTo() != null && e.validTo().isBefore(e.validFrom()))
            err.add("La fin de validité précède le début de validité.");
        String statut = e.status() == null || e.status().isBlank() ? "ACTIVE" : e.status().strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ACTIVE", "INVALID").contains(statut)) err.add("Statut : ACTIVE ou INVALID.");

        Entry normalisee = new Entry(code, fr, en, parent, attributs, e.validFrom(), e.validTo(), statut, e.version(), null,
                e.createdAt(), e.updatedAt());
        return new Resultat(normalisee, err);
    }

    private static void controlerTexte(ColumnDef c, String v, String nom, List<String> err) {
        if (c == null) return;
        if (c.maxLength() != null && v.length() > c.maxLength())
            err.add("« " + nom + " » : " + v.length() + " caractères pour " + c.maxLength() + " au maximum.");
        if (c.pattern() != null && !MOTIFS.computeIfAbsent(c.pattern(), Pattern::compile).matcher(v).matches())
            err.add("« " + nom + " » : la valeur « " + v + " » ne respecte pas le format attendu.");
    }

    private static Long entier(String v, ColumnDef c, List<String> err) {
        try {
            return Long.parseLong(v.replace(" ", "").replace(" ", ""));
        } catch (NumberFormatException ex) {
            err.add("« " + c.labelFr() + " » : « " + v + " » n'est pas un nombre entier.");
            return null;
        }
    }

    private static BigDecimal decimal(String v, ColumnDef c, List<String> err) {
        try {
            return new BigDecimal(v.replace(" ", "").replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException ex) {
            err.add("« " + c.labelFr() + " » : « " + v + " » n'est pas un nombre.");
            return null;
        }
    }

    /** Dates acceptées : AAAA-MM-JJ ou JJ/MM/AAAA ; enregistrées au format ISO 8601 (Rec. 7 UN/CEFACT). */
    public static String date(String v, String nom, List<String> err) {
        try {
            return LocalDate.parse(v).toString();
        } catch (DateTimeParseException ex) {
            try {
                return LocalDate.parse(v, FR).toString();
            } catch (DateTimeParseException ex2) {
                err.add("« " + nom + " » : « " + v + " » n'est pas une date (AAAA-MM-JJ ou JJ/MM/AAAA).");
                return null;
            }
        }
    }

    private static Boolean booleen(String v, ColumnDef c, List<String> err) {
        String b = v.toLowerCase(Locale.ROOT);
        if (VRAI.contains(b)) return Boolean.TRUE;
        if (FAUX.contains(b)) return Boolean.FALSE;
        err.add("« " + c.labelFr() + " » : « " + v + " » n'est pas une valeur oui/non.");
        return null;
    }

    static String texte(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
