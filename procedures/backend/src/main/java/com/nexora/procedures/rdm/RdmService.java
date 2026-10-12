package com.nexora.procedures.rdm;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dictionnaire du Supply Chain Reference Data Model (UN/CEFACT D23B) chargé dans le schéma rdm. Un chemin BIE est
 * une suite de codes de propriétés depuis la racine du message maître ({@code scrdmMaster}), par exemple
 * {@code exchangedDocument/sender/postalTradeAddress/cityName} ; chaque segment intermédiaire est un ASBIE.
 */
@Service
public class RdmService {

    public static final String RACINE = "scrdmMaster";

    public record Abie(String code, String den, String definition, int depth, boolean root) {}

    public record Bie(String abie, String property, String den, String kind, String cardinality, String dataType, String target, String definition) {}

    /** Élément résolu d'un chemin BIE. */
    public record Element(String path, Bie bie, List<String> denChain) {}

    private final JdbcClient jdbc;
    private volatile Integer release;
    private volatile Map<String, Abie> abies;
    private volatile Map<String, List<Bie>> proprietes;

    public RdmService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private synchronized void charger() {
        if (abies != null) return;
        release = jdbc.sql("select release_id from rdm.standard_release where family = 'SCRDM' order by release_code desc limit 1")
                .query(Integer.class).optional().orElse(null);
        Map<String, Abie> a = new LinkedHashMap<>();
        Map<String, List<Bie>> p = new HashMap<>();
        if (release != null) {
            jdbc.sql("select abie_code, den, definition, coalesce(depth_from_root, 0), is_root from rdm.abie where release_id = :r order by abie_code")
                    .param("r", release).query((rs, i) -> a.put(rs.getString(1), new Abie(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), rs.getBoolean(5)))).list();
            jdbc.sql("""
                    select abie_code, property_code, den, kind::text, cardinality, data_type, target_abie, definition
                      from rdm.bie where release_id = :r order by abie_code, property_code
                    """).param("r", release).query((rs, i) -> p.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                    .add(new Bie(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getString(8)))).list();
        }
        proprietes = p;
        abies = a;
    }

    public Map<String, Object> resume() {
        charger();
        return Map.of("family", "SCRDM", "release", "D23B", "abies", abies.size(),
                "bbies", proprietes.values().stream().flatMap(List::stream).filter(b -> b.kind().equals("BBIE")).count(),
                "asbies", proprietes.values().stream().flatMap(List::stream).filter(b -> b.kind().equals("ASBIE")).count(),
                "roots", proprietes.getOrDefault(RACINE, List.of()));
    }

    public Optional<Abie> abie(String code) {
        charger();
        return Optional.ofNullable(abies.get(code));
    }

    public List<Bie> proprietes(String abie) {
        charger();
        return proprietes.getOrDefault(abie, List.of());
    }

    public List<Abie> abies() {
        charger();
        return List.copyOf(abies.values());
    }

    /** Recherche sans accents dans les DEN, codes et définitions des propriétés. */
    public List<Bie> rechercher(String q, int limite) {
        charger();
        String n = normaliser(q);
        if (n.isBlank()) return List.of();
        List<String> mots = List.of(n.split("\\s+"));
        return proprietes.values().stream().flatMap(List::stream)
                .filter(b -> {
                    String t = normaliser(b.den() + " " + b.property() + " " + Objects.toString(b.definition(), ""));
                    return mots.stream().allMatch(t::contains);
                })
                .sorted(Comparator.comparing((Bie b) -> !normaliser(b.den()).contains(n)).thenComparing(Bie::den))
                .limit(limite).toList();
    }

    /** Résout un chemin BIE ; vide si un segment n'existe pas ou si un segment intermédiaire n'est pas un ASBIE. */
    public Optional<Element> resoudre(String chemin) {
        charger();
        if (chemin == null || chemin.isBlank()) return Optional.empty();
        String abie = RACINE;
        Bie courant = null;
        List<String> dens = new ArrayList<>();
        String[] segments = chemin.strip().split("/");
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            String a = abie;
            courant = proprietes.getOrDefault(a, List.of()).stream().filter(b -> b.property().equals(seg)).findFirst().orElse(null);
            if (courant == null) return Optional.empty();
            dens.add(courant.den());
            if (i < segments.length - 1) {
                if (!courant.kind().equals("ASBIE")) return Optional.empty();
                abie = courant.target();
            }
        }
        return Optional.of(new Element(chemin.strip(), courant, dens));
    }

    /** Chemins les plus courts depuis la racine jusqu'à un ABIE (parcours en largeur, profondeur bornée). */
    public List<String> cheminsVers(String cible, int profondeurMax, int limite) {
        charger();
        List<String> resultats = new ArrayList<>();
        Deque<Map.Entry<String, String>> file = new ArrayDeque<>();
        file.add(Map.entry(RACINE, ""));
        Map<String, Integer> vus = new HashMap<>();
        vus.put(RACINE, 0);
        while (!file.isEmpty() && resultats.size() < limite) {
            var e = file.poll();
            int profondeur = e.getValue().isEmpty() ? 0 : e.getValue().split("/").length;
            if (profondeur >= profondeurMax) continue;
            for (Bie b : proprietes.getOrDefault(e.getKey(), List.of())) {
                if (!b.kind().equals("ASBIE")) continue;
                String chemin = e.getValue().isEmpty() ? b.property() : e.getValue() + "/" + b.property();
                if (b.target().equals(cible)) resultats.add(chemin);
                Integer deja = vus.get(b.target());
                if (deja == null || deja >= profondeur + 1) {
                    vus.put(b.target(), profondeur + 1);
                    file.add(Map.entry(b.target(), chemin));
                }
            }
        }
        return resultats;
    }

    /** Tous les BBIE (pour le rapprochement sémantique du Data Agent). */
    public List<Bie> bbies() {
        charger();
        return proprietes.values().stream().flatMap(List::stream).filter(b -> b.kind().equals("BBIE")).collect(Collectors.toList());
    }

    public static String normaliser(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").strip();
    }
}
