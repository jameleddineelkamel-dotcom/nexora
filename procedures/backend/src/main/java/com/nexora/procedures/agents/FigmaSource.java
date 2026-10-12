package com.nexora.procedures.agents;

import com.nexora.procedures.web.Json;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Maquettes Figma exportées en JSON (réponse de l'API Figma « GET /v1/files/:clé », ou export d'une extension) :
 * les cadres de premier niveau d'une page sont des écrans, leurs sous-cadres / groupes des blocs, les composants de
 * saisie (nom contenant input, textfield, select, dropdown, combo, date, textarea, champ, field) des champs, libellés
 * par leur premier texte (ou le texte qui les précède) ; un astérisque marque un champ obligatoire. Un tableau (nom
 * contenant table, tableau, grid, liste) devient un groupe répétitif dont les colonnes sont les textes de l'en-tête.
 * Le format natif .fig (binaire) n'est pas lisible : exporter le JSON.
 */
final class FigmaSource {

    private static final Pattern SAISIE = Pattern.compile("(?i).*(input|text ?field|select|dropdown|combo|date ?picker|textarea|champ|field|saisie).*");
    private static final Pattern LISTE = Pattern.compile("(?i).*(select|dropdown|combo|liste d[ée]roulante).*");
    private static final Pattern TABLEAU = Pattern.compile("(?i).*(table|tableau|grid|grille).*");
    private static final Set<String> CONTENEURS = Set.of("FRAME", "GROUP", "SECTION", "COMPONENT", "INSTANCE", "COMPONENT_SET");

    private FigmaSource() {}

    static boolean estFigma(String nom, String contenu) {
        return nom != null && nom.toLowerCase(Locale.ROOT).endsWith(".json") && contenu.contains("\"document\"") && contenu.contains("\"CANVAS\"");
    }

    static boolean estFigNatif(String nom) {
        return nom != null && nom.toLowerCase(Locale.ROOT).endsWith(".fig");
    }

    @SuppressWarnings("unchecked")
    static Maquette lire(String contenu, Json json) {
        Map<String, Object> racine = json.map(contenu);
        Map<String, Object> doc = racine.get("document") instanceof Map<?, ?> d ? (Map<String, Object>) d : racine;
        List<Maquette.Ecran> ecrans = new ArrayList<>();
        for (Map<String, Object> page : enfants(doc))
            for (Map<String, Object> cadre : enfants(page)) {
                if (!CONTENEURS.contains(type(cadre))) continue;
                List<Maquette.Bloc> blocs = new ArrayList<>();
                List<Maquette.Champ> libres = new ArrayList<>();
                for (Map<String, Object> e : enfants(cadre)) {
                    if (!CONTENEURS.contains(type(e))) continue;
                    if (SAISIE.matcher(nom(e)).matches()) { Maquette.Champ c = champ(e, null); if (c != null) libres.add(c); continue; }
                    Maquette.Bloc b = bloc(e);
                    if (b != null) blocs.add(b);
                }
                if (!libres.isEmpty()) blocs.addFirst(new Maquette.Bloc(nom(cadre), false, libres, false));
                if (!blocs.isEmpty()) ecrans.add(new Maquette.Ecran(nom(cadre), blocs));
            }
        return Maquette.de(ecrans);
    }

    private static Maquette.Bloc bloc(Map<String, Object> n) {
        if (TABLEAU.matcher(nom(n)).matches()) {
            List<Map<String, Object>> lignes = enfants(n);
            List<Maquette.Champ> colonnes = new ArrayList<>();
            if (!lignes.isEmpty())
                for (String t : textes(lignes.getFirst())) colonnes.add(new Maquette.Champ(t.replace("*", "").strip(), t.contains("*"), false, "input", List.of(), null, null));
            return colonnes.isEmpty() ? null : new Maquette.Bloc(titre(n), false, colonnes, true);
        }
        List<Maquette.Champ> champs = new ArrayList<>();
        parcourir(n, champs, new String[1]);
        return champs.isEmpty() ? null : new Maquette.Bloc(titre(n), false, champs, false);
    }

    /** Parcours en profondeur : un composant de saisie devient un champ, libellé par son texte ou le dernier texte rencontré. */
    private static void parcourir(Map<String, Object> n, List<Maquette.Champ> champs, String[] dernierTexte) {
        for (Map<String, Object> e : enfants(n)) {
            if ("TEXT".equals(type(e))) { dernierTexte[0] = caracteres(e); continue; }
            if (SAISIE.matcher(nom(e)).matches()) {
                Maquette.Champ c = champ(e, dernierTexte[0]);
                if (c != null) champs.add(c);
                dernierTexte[0] = null;
            } else if (CONTENEURS.contains(type(e))) parcourir(e, champs, dernierTexte);
        }
    }

    private static Maquette.Champ champ(Map<String, Object> n, String precedent) {
        List<String> t = textes(n);
        String libelle = !t.isEmpty() && (precedent == null || t.size() > 1) ? t.getFirst() : precedent;
        if (libelle == null || libelle.isBlank()) libelle = nom(n);
        String placeholder = t.size() > 1 ? t.get(1) : null;
        boolean liste = LISTE.matcher(nom(n)).matches();
        List<String> options = liste && t.size() > 2 ? t.subList(2, t.size()) : List.of();
        return new Maquette.Champ(libelle.replace("*", "").strip(), libelle.contains("*"), false, liste ? "select" : "input", options, placeholder, null);
    }

    private static String titre(Map<String, Object> n) {
        List<String> t = textes(n);
        String nom = nom(n);
        // Nom de calque générique (« Frame 12 ») : le premier texte du bloc sert de titre
        return nom.matches("(?i)(frame|group|groupe|section|rectangle)\\s*\\d*") && !t.isEmpty() ? t.getFirst() : nom;
    }

    private static List<String> textes(Map<String, Object> n) {
        List<String> r = new ArrayList<>();
        Deque<Map<String, Object>> pile = new ArrayDeque<>(List.of(n));
        while (!pile.isEmpty()) {
            Map<String, Object> e = pile.pop();
            if ("TEXT".equals(type(e))) { String c = caracteres(e); if (!c.isBlank()) r.add(c); }
            List<Map<String, Object>> f = enfants(e);
            for (int i = f.size() - 1; i >= 0; i--) pile.push(f.get(i));
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> enfants(Map<String, Object> n) {
        List<Map<String, Object>> r = new ArrayList<>();
        if (n.get("children") instanceof List<?> l) for (Object x : l) if (x instanceof Map<?, ?> m && !Boolean.FALSE.equals(m.get("visible"))) r.add((Map<String, Object>) m);
        return r;
    }

    private static String type(Map<String, Object> n) {
        return Objects.toString(n.get("type"), "");
    }

    private static String nom(Map<String, Object> n) {
        return Objects.toString(n.get("name"), "").strip();
    }

    private static String caracteres(Map<String, Object> n) {
        return Objects.toString(n.get("characters"), "").replaceAll("\\s+", " ").strip();
    }
}
