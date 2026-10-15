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
    private final ModeleLangage llm;

    public DataAgent(RdmService rdm, ModeleLangage llm) {
        this.rdm = rdm;
        this.llm = llm;
    }

    /** Candidat de rapprochement : BBIE et score heuristique. */
    record Candidat(RdmService.Bie bie, double score) {}

    /** Arbitrage du modèle pour un champ : candidat retenu (ou aucun) et justification. */
    record Arbitrage(Integer choix, String raison) {}

    private static final int CANDIDATS = 5, LOT = 8;
    /** En dessous de ce score, ou si deux candidats sont proches, le rapprochement est soumis au modèle. */
    private static final double CERTAIN = 0.75, ECART = 0.1;

    private static final String CONSIGNE = """
            Tu es le Data Agent du studio NEXORA. Pour chaque champ de formulaire, choisis parmi les candidats fournis
            l'élément du modèle de référence UN/CEFACT SCRDM D23B (BBIE) qui a le même sens métier, ou aucun si aucun ne convient.
            Réponds uniquement par un objet JSON : {"choices":[{"field":"<code du champ>","choice":<numéro du candidat ou null>,"reason":"<justification courte en français>"}]}.
            N'invente aucun candidat : « choice » est l'un des numéros proposés pour ce champ, ou null.""";

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
        // Candidats heuristiques de chaque champ non rapproché, puis arbitrage du modèle pour les cas incertains
        Map<String, List<Candidat>> candidats = new LinkedHashMap<>();
        for (Field f : v.fields())
            if (f.biePath() == null || f.biePath().isBlank()) candidats.put(f.code(), candidats(f, bbies));
        Map<String, Arbitrage> arbitrages = arbitrer(v, candidats);
        List<String> sansEquivalent = new ArrayList<>(), multiples = new ArrayList<>();
        int untded = 0;
        for (Field f : v.fields()) {
            Proposition mapping = null;
            if (f.biePath() == null || f.biePath().isBlank()) {
                Optional<Proposition> p = parRole(f).or(() -> rapprocher(f, candidats.get(f.code()), arbitrages.get(f.code()), contextes));
                if (p.isPresent()) {
                    mapping = p.get();
                    rapproches++;
                } else sansEquivalent.add(f.code());
                List<Candidat> c = candidats.get(f.code());
                if (p.isPresent() && !p.get().payload().containsKey("partyRole") && arbitrages.get(f.code()) == null && c.size() > 1 && c.getFirst().score() - c.get(1).score() < ECART)
                    multiples.add(f.code());
            }
            // Référence UNTDED (ISO 7372) déduite du BBIE : jointe au rapprochement proposé, ou proposée seule
            if (f.untdedRef() == null || f.untdedRef().isBlank()) {
                String den = mapping != null ? String.valueOf(mapping.payload().get("den"))
                        : f.biePath() == null || f.biePath().isBlank() ? null : rdm.resoudre(f.biePath()).map(e -> e.bie().den()).orElse(null);
                var u = rdm.untdedPour(den);
                if (u.isPresent()) {
                    untded++;
                    RdmService.Untded e = u.get().getKey();
                    if (mapping != null) {
                        Map<String, Object> pl = new LinkedHashMap<>(mapping.payload());
                        pl.put("untdedRef", e.tag());
                        pl.put("untdedName", e.name());
                        mapping = new Proposition(mapping.kind(), mapping.target(), mapping.title() + " · UNTDED " + e.tag(), pl,
                                mapping.rationale() + " Élément UNTDED " + e.tag() + " « " + e.name() + " » (" + Objects.toString(e.representation(), "?") + ").",
                                mapping.sourceExcerpt(), mapping.type(), mapping.confidence());
                    } else
                        props.add(new Proposition("FIELD_MAPPING", f.code(), "« " + f.labelFr() + " » → UNTDED " + e.tag() + " " + e.name(),
                                Map.of("fieldCode", f.code(), "untdedRef", e.tag(), "untdedName", e.name(), "den", den),
                                "Le BBIE SCRDM « " + den + " » du champ correspond à l'élément UNTDED 2005 " + e.tag() + " « " + e.name()
                                        + " » (même terme de représentation, propriété proche ; représentation " + Objects.toString(e.representation(), "?") + ").",
                                Objects.toString(e.description(), ""), "inference", Math.round(u.get().getValue() * 100) / 100.0));
                }
            }
            if (mapping != null) props.add(mapping);
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
        // RDM Guideline UN/CEFACT §7.5 : cas « plusieurs correspondances » et « aucune correspondance »
        if (!multiples.isEmpty())
            props.add(new Proposition("FINDING", "rdm-multiples", multiples.size() + " champ(s) à correspondances multiples dans le SCRDM",
                    Map.of("severity", "info", "rule", "RDM-GUIDE-2", "message", "Plusieurs éléments du SCRDM sont proches pour : " + String.join(", ", multiples)
                            + ". Vérifiez l'usage du champ dans le processus (analyse BPA) avant d'accepter le rapprochement proposé."),
                    "RDM Guideline v1.0.0.2, étape 5, cas 2 : une donnée correspond à plusieurs éléments du RDM.", null, "check", 0.8));
        if (!sansEquivalent.isEmpty())
            props.add(new Proposition("FINDING", "rdm-sans-equivalent", sansEquivalent.size() + " champ(s) sans équivalent dans le SCRDM D23B",
                    Map.of("severity", "warning", "rule", "RDM-GUIDE-3", "message", "Aucun élément du SCRDM pour : " + String.join(", ", sansEquivalent)
                            + ". Documentez-les comme extension nationale et, si besoin, soumettez une demande d'inclusion (DMR) à l'UN/CEFACT."),
                    "RDM Guideline v1.0.0.2, étape 5, cas 3 : donnée sans correspondance directe dans le RDM.", null, "check", 0.8));
        long listes = props.stream().filter(p -> p.kind().equals("REFDATA")).count();
        String resume = rapproches + " champ(s) rapproché(s) du SCRDM D23B, " + listes + " liste(s) de codes proposée(s), " + untded + " référence(s) UNTDED ; "
                + v.fields().stream().filter(f -> f.biePath() != null && !f.biePath().isBlank()).count() + " champ(s) déjà rapproché(s).";
        if (!llm.disponible()) return Sortie.de(resume, props);
        return arbitrages.isEmpty() && candidats.values().stream().anyMatch(DataAgent::incertain)
                ? new Sortie(resume + " Modèle " + llm.nom() + " injoignable : rapprochements heuristiques seuls.", props, "heuristique (modèle " + llm.nom() + " injoignable)")
                : new Sortie(resume + " " + arbitrages.size() + " rapprochement(s) incertain(s) arbitré(s) par le modèle " + llm.nom() + ".", props,
                        arbitrages.isEmpty() ? "heuristique" : llm.nom() + " + heuristique");
    }

    /** Les meilleurs BBIE pour un champ, par score décroissant. */
    private List<Candidat> candidats(Field f, List<RdmService.Bie> bbies) {
        Set<String> cible = jetons(f.cclDen() != null && !f.cclDen().isBlank() ? f.cclDen() : separerCamel(f.code()) + " " + f.labelFr());
        Set<String> code = jetons(separerCamel(f.code()));
        List<Candidat> l = new ArrayList<>();
        for (RdmService.Bie b : bbies) {
            double s = jaccard(cible, jetons(b.den()));
            if (b.property().equalsIgnoreCase(f.code())) s += 0.45;
            else s += 0.25 * jaccard(code, jetons(separerCamel(b.property())));
            if (s >= 0.15) l.add(new Candidat(b, s));
        }
        l.sort(Comparator.comparingDouble(Candidat::score).reversed());
        return l.size() > CANDIDATS ? List.copyOf(l.subList(0, CANDIDATS)) : l;
    }

    private static boolean incertain(List<Candidat> c) {
        return !c.isEmpty() && (c.getFirst().score() < CERTAIN || c.size() > 1 && c.getFirst().score() - c.get(1).score() < ECART);
    }

    /** Soumet au modèle, par lots, les champs dont le rapprochement est incertain ; réponses restreintes aux candidats. */
    private Map<String, Arbitrage> arbitrer(VersionComplete v, Map<String, List<Candidat>> candidats) {
        Map<String, Arbitrage> r = new HashMap<>();
        if (!llm.disponible()) return r;
        Map<String, Field> champs = new HashMap<>();
        v.fields().forEach(f -> champs.put(f.code(), f));
        Map<String, String> sections = new HashMap<>();
        v.sections().forEach(s -> sections.put(s.code(), s.labelFr()));
        List<String> douteux = candidats.entrySet().stream().filter(e -> incertain(e.getValue())).map(Map.Entry::getKey).toList();
        for (int i = 0; i < douteux.size(); i += LOT) {
            StringBuilder q = new StringBuilder("Procédure : ").append(v.procedure().labelFr()).append("\n\n");
            for (String code : douteux.subList(i, Math.min(douteux.size(), i + LOT))) {
                Field f = champs.get(code);
                q.append("Champ ").append(code).append(" — « ").append(f.labelFr()).append(" » (section « ")
                        .append(sections.getOrDefault(f.sectionCode(), f.sectionCode())).append(" », type ").append(f.dataType()).append(")");
                if (f.cclDen() != null && !f.cclDen().isBlank()) q.append(", DEN UN/CCL « ").append(f.cclDen()).append(" »");
                q.append("\n");
                List<Candidat> c = candidats.get(code);
                for (int k = 0; k < c.size(); k++) {
                    RdmService.Bie b = c.get(k).bie();
                    String def = Objects.toString(b.definition(), "");
                    q.append("  ").append(k + 1).append(". ").append(b.den()).append(" — ").append(def.length() > 160 ? def.substring(0, 160) + "…" : def).append("\n");
                }
                q.append("\n");
            }
            llm.json(CONSIGNE, q.toString()).ifPresent(rep -> {
                if (!(rep.json().get("choices") instanceof List<?> l)) return;
                for (Object o : l) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    String champ = Objects.toString(m.get("field"), "");
                    List<Candidat> c = candidats.get(champ);
                    if (c == null || !douteux.contains(champ)) continue;
                    Integer choix = null;
                    try {
                        if (m.get("choice") != null) choix = (int) Double.parseDouble(String.valueOf(m.get("choice")));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (choix != null && (choix < 1 || choix > c.size())) continue;   // hors des candidats : ignoré
                    r.put(champ, new Arbitrage(choix, Objects.toString(m.get("reason"), "").strip()));
                }
            });
        }
        return r;
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

    private Optional<Proposition> rapprocher(Field f, List<Candidat> candidats, Arbitrage arbitrage, Map<String, String> contextes) {
        Set<String> cible = jetons(f.cclDen() != null && !f.cclDen().isBlank() ? f.cclDen() : separerCamel(f.code()) + " " + f.labelFr());
        if (candidats == null || candidats.isEmpty()) return Optional.empty();
        RdmService.Bie meilleur;
        double score;
        if (arbitrage != null) {
            // Le modèle a départagé un cas incertain : « aucun » écarte la proposition
            if (arbitrage.choix() == null) return Optional.empty();
            Candidat c = candidats.get(arbitrage.choix() - 1);
            meilleur = c.bie();
            score = Math.max(0.6, Math.min(0.85, c.score()));
        } else {
            meilleur = candidats.getFirst().bie();
            score = candidats.getFirst().score();
            if (score < 0.4) return Optional.empty();
        }
        Optional<RdmService.RolePartie> role = rdm.rolePour(f.labelFr() + " " + Objects.toString(f.labelEn(), ""));
        String racine = role.map(RdmService.RolePartie::biePath).orElse(contextes.getOrDefault(f.sectionCode(), contextes.get("default")));
        List<String> chemins = rdm.cheminsVers(meilleur.abie(), 6, 40);
        if (meilleur.abie().equals(RdmService.RACINE)) chemins = List.of("");
        if (chemins.isEmpty()) return Optional.empty();
        // Le rôle de partie fixe directement la racine quand il pointe sur l'ABIE du BBIE retenu (ex. …/consignor → tradeParty)
        String abie = meilleur.abie();
        boolean racineDirecte = rdm.resoudre(racine).map(e -> abie.equals(e.bie().target())).orElse(false);
        String chemin = racineDirecte ? racine : chemins.stream().filter(c -> c.equals(racine) || c.startsWith(racine + "/")).findFirst()
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
        String justification = arbitrage != null
                ? "Choix du modèle " + llm.nom() + " parmi " + candidats.size() + " candidats heuristiques" + (arbitrage.raison().isBlank() ? "" : " : " + arbitrage.raison().replaceAll("[.\s]+$", ""))
                        + ". BBIE SCRDM « " + meilleur.den() + " » (" + meilleur.cardinality() + ")"
                : "Correspondance " + (exact ? "exacte" : "proche") + " entre "
                        + (f.cclDen() != null ? "le DEN UN/CCL « " + f.cclDen() + " »" : "le nom du champ") + " et le BBIE SCRDM « " + meilleur.den()
                        + " » (" + meilleur.cardinality() + ")";
        if (arbitrage != null) payload.put("arbitratedBy", llm.nom());
        role.ifPresent(r -> payload.put("partyRole", r.code()));
        return Optional.of(new Proposition("FIELD_MAPPING", f.code(), "« " + f.labelFr() + " » → " + meilleur.den(), payload,
                justification + ". Chemin choisi dans le contexte « " + racine + " »"
                        + role.map(r -> " (rôle « " + r.labelEn() + " » du RSM SCRDM)").orElse("") + ".",
                Objects.toString(meilleur.definition(), ""), "inference", confiance));
    }

    /** Termes français des libellés et leur équivalent dans les noms de propriétés SCRDM. */
    private static final Map<String, String> TERMES = Map.of("nom", "name", "raison sociale", "name", "denomination", "name",
            "identifiant", "identifier", "numero", "identifier", "niu", "identifier", "matricule", "identifier",
            "description", "description", "designation", "description");

    /**
     * Rapprochement par rôle de partie (RSM SCRDM) : « Nom de l'expéditeur » → rôle CONSIGNOR → ABIE de la partie
     * (specifiedConsignment/consignor → tradeParty) → propriété dont tous les termes figurent dans le champ (name).
     */
    private Optional<Proposition> parRole(Field f) {
        Optional<RdmService.RolePartie> role = rdm.rolePour(f.labelFr() + " " + Objects.toString(f.labelEn(), ""));
        if (role.isEmpty()) return Optional.empty();
        Optional<RdmService.Element> partie = rdm.resoudre(role.get().biePath());
        if (partie.isEmpty() || partie.get().bie().target() == null) return Optional.empty();
        Set<String> mots = jetons(separerCamel(f.code()) + " " + f.labelFr());
        String libelle = " " + RdmService.normaliser(f.labelFr()) + " ";
        TERMES.forEach((fr, en) -> { if (libelle.contains(" " + fr + " ")) mots.add(en); });
        RdmService.Bie meilleur = null;
        for (RdmService.Bie b : rdm.proprietes(partie.get().bie().target())) {
            if (!b.kind().equals("BBIE")) continue;
            Set<String> p = jetons(separerCamel(b.property()));
            if (!p.isEmpty() && mots.containsAll(p) && (meilleur == null || p.size() > jetons(separerCamel(meilleur.property())).size()))
                meilleur = b;
        }
        if (meilleur == null) return Optional.empty();
        RdmService.RolePartie r = role.get();
        String chemin = r.biePath() + "/" + meilleur.property();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fieldCode", f.code());
        payload.put("biePath", chemin);
        payload.put("den", meilleur.den());
        payload.put("mappingType", "exact");
        payload.put("cardinality", meilleur.cardinality());
        payload.put("dataType", Objects.toString(meilleur.dataType(), ""));
        payload.put("partyRole", r.code());
        return Optional.of(new Proposition("FIELD_MAPPING", f.code(), "« " + f.labelFr() + " » → " + r.labelEn() + " · " + meilleur.den(), payload,
                "Le libellé désigne le rôle « " + r.labelEn() + " » (" + r.labelFr() + ") du RSM SCRDM, rattaché à " + r.biePath()
                        + " ; propriété « " + meilleur.property() + " » de la partie. Chemin : " + chemin + ".",
                r.definition(), "inference", 0.85));
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
