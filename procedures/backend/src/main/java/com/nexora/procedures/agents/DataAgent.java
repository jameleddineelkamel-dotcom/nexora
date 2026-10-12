package com.nexora.procedures.agents;

import com.nexora.procedures.agents.Agents.*;
import com.nexora.procedures.rdm.RdmService;
import com.nexora.procedures.studio.Modele.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Data Agent (Trade Data Mapper) : rapproche chaque champ du formulaire d'un élément du SCRDM D23B (chemin BIE et DEN)
 * et propose la liste de codes du Référentiel Commun adaptée. Le score de confiance sert à prioriser la revue, jamais
 * à décider (§9.1) : chaque rapprochement est une proposition à accepter, corriger ou rejeter.
 */
@Component
public class DataAgent implements Agent {

    /** Mots vides des noms normalisés (DEN) et des libellés. */
    private static final Set<String> VIDES = Set.of("details", "text", "the", "of", "a", "an", "and", "for", "this", "de", "du", "la", "le", "des", "d");

    /** Listes de codes du Référentiel Commun reconnues par nom de champ. */
    private static final List<Map.Entry<String, String>> LISTES = List.of(
            Map.entry("countrycode", "REF_COUNTRY"), Map.entry("countryoforigincode", "REF_COUNTRY"),
            Map.entry("destinationcountrycode", "REF_COUNTRY"), Map.entry("currencycode", "REF_CURRENCY"),
            Map.entry("unitcode", "REF_MEASUREMENT_UNIT"), Map.entry("activitysectorcode", "REF_NACAM"),
            Map.entry("industryclassificationcode", "REF_NACAM"), Map.entry("incoterm", "REF_INCOTERM"),
            Map.entry("transportmode", "REF_TRANSPORT_MODE"), Map.entry("packagetype", "REF_PACKAGING_TYPES"),
            Map.entry("locode", "REF_UNLOCODE"), Map.entry("documenttype", "REF_DOCUMENT_CODES"));

    private final RdmService rdm;

    public DataAgent(RdmService rdm) {
        this.rdm = rdm;
    }

    @Override
    public String code() {
        return "DONNEES";
    }

    @Override
    public Sortie executer(Contexte ctx) {
        VersionComplete v = ctx.version();
        List<Proposition> props = new ArrayList<>();
        List<RdmService.Bie> bbies = rdm.bbies();
        Map<String, String> contextes = contextes(v);
        int rapproches = 0;
        for (Field f : v.fields()) {
            if (f.biePath() == null || f.biePath().isBlank()) {
                Optional<Proposition> p = rapprocher(f, bbies, contextes);
                if (p.isPresent()) {
                    props.add(p.get());
                    rapproches++;
                }
            }
            if (f.refdataTable() == null || f.refdataTable().isBlank()) {
                String cle = f.code().toLowerCase(Locale.ROOT);
                LISTES.stream().filter(e -> cle.endsWith(e.getKey()) || cle.equals(e.getKey())).findFirst().ifPresent(e -> props.add(new Proposition(
                        "REFDATA", f.code(), "Liste de codes " + e.getValue() + " pour « " + f.labelFr() + " »",
                        Map.of("fieldCode", f.code(), "refdataTable", e.getValue()),
                        "Le nom du champ désigne une liste normalisée (UN/CEFACT, ISO ou nationale) déjà présente dans le Référentiel Commun : "
                                + "la consommer évite une double gestion (RG-REF-01, C39).",
                        Objects.toString(f.cclDen(), f.labelFr()), "inference", 0.9)));
            }
        }
        long listes = props.stream().filter(p -> p.kind().equals("REFDATA")).count();
        return Sortie.de(rapproches + " champ(s) rapproché(s) du SCRDM D23B, " + listes + " liste(s) de codes proposée(s) ; "
                + v.fields().stream().filter(f -> f.biePath() != null && !f.biePath().isBlank()).count() + " champ(s) déjà rapproché(s).", props);
    }

    /** Racine de contexte par section : réglage « bieContext » de la version, sinon émetteur du document / articles de la consignation. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> contextes(VersionComplete v) {
        Map<String, String> c = new HashMap<>();
        Object reglage = v.version().settings().get("bieContext");
        if (reglage instanceof Map<?, ?> m) m.forEach((k, val) -> c.put(String.valueOf(k), String.valueOf(val)));
        c.putIfAbsent("default", "exchangedDocument/sender");
        v.sections().stream().filter(Section::repeating).forEach(s -> c.putIfAbsent(s.code(), "specifiedConsignment/includedConsignmentItem"));
        return c;
    }

    private Optional<Proposition> rapprocher(Field f, List<RdmService.Bie> bbies, Map<String, String> contextes) {
        Set<String> cible = jetons(f.cclDen() != null && !f.cclDen().isBlank() ? f.cclDen() : separerCamel(f.code()) + " " + f.labelFr());
        Set<String> code = jetons(separerCamel(f.code()));
        RdmService.Bie meilleur = null;
        double score = 0;
        for (RdmService.Bie b : bbies) {
            Set<String> den = jetons(b.den());
            double s = jaccard(cible, den);
            if (b.property().equalsIgnoreCase(f.code())) s += 0.45;
            else s += 0.25 * jaccard(code, jetons(separerCamel(b.property())));
            if (s > score) {
                score = s;
                meilleur = b;
            }
        }
        if (meilleur == null || score < 0.4) return Optional.empty();
        String racine = contextes.getOrDefault(f.sectionCode(), contextes.get("default"));
        List<String> chemins = rdm.cheminsVers(meilleur.abie(), 6, 40);
        if (meilleur.abie().equals(RdmService.RACINE)) chemins = List.of("");
        if (chemins.isEmpty()) return Optional.empty();
        String chemin = chemins.stream().filter(c -> c.equals(racine) || c.startsWith(racine + "/")).findFirst()
                .orElse(chemins.stream().min(Comparator.comparingInt(String::length)).orElseThrow());
        String complet = chemin.isEmpty() ? meilleur.property() : chemin + "/" + meilleur.property();
        boolean exact = meilleur.property().equalsIgnoreCase(f.code()) || jaccard(cible, jetons(meilleur.den())) >= 0.99;
        double confiance = Math.min(0.99, Math.round(Math.min(1, score) * 100) / 100.0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fieldCode", f.code());
        payload.put("biePath", complet);
        payload.put("den", meilleur.den());
        payload.put("mappingType", exact ? "exact" : "close");
        payload.put("cardinality", meilleur.cardinality());
        payload.put("dataType", Objects.toString(meilleur.dataType(), ""));
        return Optional.of(new Proposition("FIELD_MAPPING", f.code(), "« " + f.labelFr() + " » → " + meilleur.den(), payload,
                "Correspondance " + (exact ? "exacte" : "proche") + " entre "
                        + (f.cclDen() != null ? "le DEN UN/CCL « " + f.cclDen() + " »" : "le nom du champ") + " et le BBIE SCRDM « " + meilleur.den()
                        + " » (" + meilleur.cardinality() + "). Chemin choisi dans le contexte « " + racine + " ».",
                Objects.toString(meilleur.definition(), ""), "inference", confiance));
    }

    static Set<String> jetons(String s) {
        Set<String> r = new LinkedHashSet<>();
        for (String m : RdmService.normaliser(s).split("\\s+")) {
            if (m.isBlank() || VIDES.contains(m)) continue;
            r.add(m.equals("identification") ? "identifier" : m.equals("id") ? "identifier" : m);
        }
        return r;
    }

    static String separerCamel(String s) {
        return s == null ? "" : s.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> i = new HashSet<>(a);
        i.retainAll(b);
        Set<String> u = new HashSet<>(a);
        u.addAll(b);
        return (double) i.size() / u.size();
    }
}
