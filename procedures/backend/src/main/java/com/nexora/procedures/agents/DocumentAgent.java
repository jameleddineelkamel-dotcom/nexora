package com.nexora.procedures.agents;

import com.nexora.procedures.agents.Agents.*;
import com.nexora.procedures.studio.Modele.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Document Agent : lit une SFD (Word ou texte) et en extrait, avec l'extrait source comme preuve (AP-04), les pièces à
 * fournir, les acteurs, la répartition des frais, le délai de délivrance et les règles de gestion (RG / RM). Chaque
 * élément devient une proposition ; rien n'est appliqué sans revue. Le contenu du document est une donnée : aucune
 * phrase du document n'est interprétée comme une consigne.
 */
@Component
public class DocumentAgent implements Agent {

    private static final Pattern PIECE = Pattern.compile("^P\\d{1,2}$");
    private static final Pattern REGLE = Pattern.compile("^(RG\\s?\\d+|RM\\s?\\d+|RG0\\d+)\\s*[:\\-–]\\s*(.+)$");
    private static final Pattern MONTANT = Pattern.compile("^-?\\s*([^:]{2,60}?)\\s*:\\s*([\\d  .]+)\\s*(?:f|F|CFA|FCFA|f\\s?CFA)?\\b(.*)$");
    private static final Pattern UC = Pattern.compile("^(UC-?\\d+(?:\\.\\d+)?)\\b");
    private static final Pattern HEURES = Pattern.compile("(\\d+)\\s*heures");
    private static final Pattern INJECTION = Pattern.compile("(?i)(ignore|oublie|disregard)\\s+(les|toutes|all|previous|the)\\s+(r[eè]gles|instructions|consignes)");

    private final ModeleLangage llm;

    public DocumentAgent(ModeleLangage llm) {
        this.llm = llm;
    }

    @Override
    public String code() {
        return "DOCUMENT";
    }

    @Override
    public Sortie executer(Contexte ctx) {
        if (ctx.texteSource() == null || ctx.texteSource().isBlank())
            return Sortie.de("Aucun document source fourni : joignez la SFD (Word ou texte) pour lancer l'extraction.", List.of());
        TexteSource src = TexteSource.texte(ctx.texteSource());
        VersionComplete v = ctx.version();
        List<Proposition> props = new ArrayList<>();
        pieces(src, v, props);
        acteurs(src, v, props);
        frais(src, v, props);
        delais(src, v, props);
        regles(src, v, props);
        if (INJECTION.matcher(ctx.texteSource()).find())
            props.add(new Proposition("FINDING", "securite", "Instruction suspecte dans le document source",
                    Map.of("severity", "warning", "rule", "AC-AG-02", "message", "Le document contient une phrase demandant d'ignorer des règles : elle a été traitée comme une donnée."),
                    "Protection contre l'injection documentaire (§8.4, AC-AG-02) : le contenu importé n'est jamais exécuté comme une consigne.",
                    extrait(INJECTION.matcher(ctx.texteSource()), ctx.texteSource()), "check", 0.99));
        String modele = "heuristique";
        if (llm.disponible()) modele = llm.nom() + " (non sollicité : extraction déterministe suffisante)";
        Map<String, Long> parType = new TreeMap<>();
        props.forEach(p -> parType.merge(p.kind(), 1L, Long::sum));
        return new Sortie(props.isEmpty() ? "Aucun élément reconnu dans « " + ctx.nomSource() + " »."
                : "Extraction de « " + Objects.toString(ctx.nomSource(), "document") + " » : " + parType, props, modele);
    }

    // ------------------------------------------------------------------ pièces à fournir
    private void pieces(TexteSource src, VersionComplete v, List<Proposition> props) {
        Map<String, RequiredDocument> existants = new HashMap<>();
        v.documents().forEach(d -> existants.put(d.code(), d));
        boolean champNationalite = v.fields().stream().anyMatch(f -> f.code().equals("foreignerCitizen"));
        int ordre = 0;
        for (TexteSource.Tableau t : src.tableaux())
            for (List<String> l : t.lignes()) {
                if (l.size() < 2 || !PIECE.matcher(l.get(0).strip()).matches()) continue;
                String code = l.get(0).strip(), libelle = l.get(1).replace('\n', ' ').strip();
                String oblig = l.size() > 2 ? l.get(2).strip().toLowerCase(Locale.ROOT) : "o";
                String observation = l.size() > 3 ? l.get(3).replace('\n', ' ').strip() : "";
                boolean obligatoire = oblig.startsWith("o") && !normaliser(observation).contains("le cas echeant");
                String condition = champNationalite && normaliser(observation).contains("etranger") ? "foreignerCitizen == 'E'" : null;
                ordre += 10;
                RequiredDocument d = existants.get(code);
                if (d != null && d.labelFr().equals(libelle) && d.mandatory() == obligatoire) continue;
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("code", code);
                p.put("labelFr", libelle);
                p.put("mandatory", obligatoire && condition == null);
                p.put("conditionText", condition);
                p.put("observation", observation);
                p.put("sortOrder", ordre);
                props.add(new Proposition("DOCUMENT", code, (d == null ? "Pièce " : "Pièce modifiée ") + code + " : " + libelle, p,
                        "Ligne du tableau « Composition du dossier »" + (condition != null ? " ; l'observation « " + observation
                                + " » est traduite en condition sur le champ foreignerCitizen" : "") + ".",
                        String.join(" | ", l).replace('\n', ' '), "extraction", condition != null ? 0.7 : 0.95));
            }
    }

    // ------------------------------------------------------------------ acteurs
    private void acteurs(TexteSource src, VersionComplete v, List<Proposition> props) {
        Set<String> existants = new HashSet<>();
        v.actors().forEach(a -> existants.add(a.code()));
        for (TexteSource.Tableau t : src.tableaux()) {
            if (t.lignes().isEmpty() || t.lignes().getFirst().isEmpty()) continue;
            String entete = normaliser(t.lignes().getFirst().getFirst());
            if (!entete.startsWith("acteur")) continue;
            int ordre = 100;
            for (List<String> l : t.lignes().subList(1, t.lignes().size())) {
                if (l.size() < 2 || l.get(0).isBlank()) continue;
                String libelle = l.get(0).replace('\n', ' ').strip();
                String n = normaliser(libelle);
                String code = codeActeur(libelle);
                String kind = n.contains("importateur") || n.contains("exportateur") || n.contains("demandeur") ? "DEMANDEUR"
                        : n.contains("banque") ? "BANQUE" : n.contains("guichet") || n.contains("guce") ? "SYSTEME"
                        : n.contains("direction") || n.contains("ministere") || n.contains("impots") ? "ADMINISTRATION" : "PARTENAIRE";
                if (kind.equals("DEMANDEUR")) code = "DEMANDEUR";
                if (existants.contains(code)) continue;
                ordre += 10;
                String systeme = kind.equals("DEMANDEUR") ? "EFORCE" : kind.equals("BANQUE") || kind.equals("SYSTEME") ? "EPAYMENT" : "EXTERNE";
                props.add(new Proposition("ACTOR", code, "Acteur " + code + " : " + libelle,
                        Map.of("code", code, "labelFr", libelle, "kind", kind, "systemCode", systeme, "responsibilities", l.get(1).replace("\n", " ; ").strip(),
                                "sortOrder", ordre),
                        "Ligne du tableau « Acteurs du processus » ; type d'acteur déduit du libellé.", String.join(" | ", l).replace('\n', ' '),
                        "extraction", 0.8));
            }
        }
    }

    static String codeActeur(String libelle) {
        Matcher sigle = Pattern.compile("\\(([A-Z][A-Z0-9\\- /]{1,15})\\)").matcher(libelle);
        if (sigle.find()) return sigle.group(1).split("[ /]")[0].replace("-", "_").replace("E_GUCE", "GUCE");
        for (String s : List.of("MINCOMMERCE", "CNCC", "GUCE", "DGI")) if (libelle.contains(s)) return s;
        String n = normaliser(libelle);
        if (n.startsWith("banque")) return "BANQUE";
        StringBuilder c = new StringBuilder();
        for (String m : n.split("\\s+")) if (m.length() > 3) c.append(Character.toUpperCase(m.charAt(0)));
        return c.isEmpty() ? "ACTEUR" : c.toString();
    }

    // ------------------------------------------------------------------ frais
    private void frais(TexteSource src, VersionComplete v, List<Proposition> props) {
        // bénéficiaire → montant → types de demande concernés
        Map<String, Map<BigDecimal, Set<String>>> repartition = new LinkedHashMap<>();
        Map<String, String> libelles = new HashMap<>();
        Map<String, String> sources = new HashMap<>();
        for (TexteSource.Tableau t : src.tableaux())
            for (List<String> l : t.lignes()) {
                if (l.size() < 3 || !normaliser(l.get(0)).startsWith("cout")) continue;
                String type = typeDemande(normaliser(l.get(0)), v);
                Map<String, BigDecimal> parBeneficiaire = new LinkedHashMap<>();
                for (String ligne : l.get(2).split("\n")) {
                    Matcher m = MONTANT.matcher(ligne.strip());
                    if (!m.matches()) continue;
                    String libelle = m.group(1).replace("-", "").strip();
                    String beneficiaire = beneficiaire(libelle);
                    BigDecimal montant = new BigDecimal(m.group(2).replaceAll("[  .]", ""));
                    if (beneficiaire.equals("GUCE") && normaliser(ligne).contains("10")) {
                        // « GUCE : 10 % de 30 000 (à déduire de la quote-part MINCOMMERCE) »
                        BigDecimal base = parBeneficiaire.getOrDefault("MINCOMMERCE", montant);
                        montant = base.multiply(BigDecimal.TEN).divide(BigDecimal.valueOf(100));
                        if (parBeneficiaire.containsKey("MINCOMMERCE")) parBeneficiaire.put("MINCOMMERCE", base.subtract(montant));
                        libelles.putIfAbsent("GUCE", "Commission GUCE (10 % de la quote-part MINCOMMERCE)");
                    }
                    parBeneficiaire.put(beneficiaire, montant);
                    libelles.putIfAbsent(beneficiaire, beneficiaire.equals("TIMBRE") ? "Timbre fiscal" : beneficiaire.equals("MINCOMMERCE")
                            ? "Quote-part MINCOMMERCE" : libelle);
                    sources.merge(beneficiaire, l.get(0).strip() + " : " + ligne.strip(), (a, b) -> a.length() > 300 ? a : a + " · " + b);
                }
                parBeneficiaire.forEach((b, mt) -> repartition.computeIfAbsent(b, k -> new LinkedHashMap<>())
                        .computeIfAbsent(mt, k -> new TreeSet<>()).add(type == null ? "*" : type));
            }
        Set<String> existants = new HashSet<>();
        v.fees().forEach(f -> existants.add(f.code() + "|" + f.amount().stripTrailingZeros().toPlainString()));
        int ordre = 0;
        for (var e : repartition.entrySet())
            for (var m : e.getValue().entrySet()) {
                String code = e.getKey();
                if (existants.contains(code + "|" + m.getKey().stripTrailingZeros().toPlainString())) continue;
                List<String> types = m.getValue().contains("*") ? List.of() : List.copyOf(m.getValue());
                ordre += 10;
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("code", code);
                p.put("beneficiary", code.equals("TIMBRE") ? "DGI" : code);
                p.put("labelFr", libelles.getOrDefault(code, code));
                p.put("amount", m.getKey());
                p.put("currency", "XAF");
                p.put("requestTypes", types);
                p.put("natures", List.of("INITIAL", "RENEWAL"));
                p.put("sortOrder", ordre);
                props.add(new Proposition("FEE", code, "Frais " + code + " : " + m.getKey().toPlainString() + " FCFA"
                        + (types.isEmpty() ? "" : " (" + String.join(", ", types) + ")"), p,
                        "Répartition lue dans le tableau « Délais et coûts » ; la commission GUCE (10 %) est déduite de la quote-part MINCOMMERCE. "
                                + "Natures payantes supposées : demande initiale et renouvellement (l'amendement n'est pas payant d'après la SFD).",
                        sources.get(code), "extraction", 0.75));
            }
    }

    private static String typeDemande(String libelle, VersionComplete v) {
        for (RequestType t : v.requestTypes()) {
            String n = normaliser(t.labelFr());
            if (libelle.contains("importateur") && n.contains("importateur")) return t.code();
            if (libelle.contains("exportateur") && n.contains("exportateur")) return t.code();
            if (libelle.contains("autorisation") && n.contains("autorisation")) return t.code();
        }
        return null;
    }

    private static String beneficiaire(String libelle) {
        String n = normaliser(libelle);
        if (n.contains("timbre")) return "TIMBRE";
        for (String s : List.of("MINCOMMERCE", "CNCC", "GUCE", "DGI")) if (libelle.toUpperCase(Locale.ROOT).contains(s)) return s;
        return codeActeur(libelle);
    }

    // ------------------------------------------------------------------ délais
    private void delais(TexteSource src, VersionComplete v, List<Proposition> props) {
        for (TexteSource.Tableau t : src.tableaux())
            for (List<String> l : t.lignes()) {
                if (l.size() < 2 || !normaliser(l.get(0)).startsWith("delai")) continue;
                Matcher h = HEURES.matcher(l.get(1));
                if (!h.find()) continue;
                int heures = Integer.parseInt(h.group(1));
                if (Objects.equals(v.version().settings().get("slaHours"), heures)) continue;
                props.add(new Proposition("SETTING", "slaHours", "Délai de délivrance : " + heures + " heures", Map.of("key", "slaHours", "value", heures),
                        "Délai officiel lu dans le tableau « Délais et coûts » : il fixe l'échéance (SLA) des dossiers.", String.join(" | ", l).replace('\n', ' '),
                        "extraction", 0.9));
            }
    }

    // ------------------------------------------------------------------ règles de gestion
    private void regles(TexteSource src, VersionComplete v, List<Proposition> props) {
        Set<String> existantes = new HashSet<>();
        if (v.rules() != null) v.rules().businessRules().forEach(r -> existantes.add(r.code() + "|" + normaliser(r.text())));
        Set<String> vues = new HashSet<>();
        for (TexteSource.Tableau t : src.tableaux()) {
            String uc = null;
            for (List<String> l : t.lignes()) {
                if (l.size() >= 2 && normaliser(l.get(0)).equals("code")) {
                    Matcher m = UC.matcher(l.get(1).strip());
                    if (m.find()) uc = m.group(1);
                }
                for (String cellule : l)
                    for (String ligne : cellule.split("\n")) {
                        Matcher m = REGLE.matcher(ligne.strip());
                        if (!m.matches()) continue;
                        String code = (uc == null ? "" : uc + " ") + m.group(1).replace(" ", "");
                        String texte = m.group(2).strip();
                        if (!vues.add(code + "|" + normaliser(texte)) || existantes.contains(code + "|" + normaliser(texte))) continue;
                        props.add(new Proposition("BUSINESS_RULE", code, code + " : " + (texte.length() > 90 ? texte.substring(0, 90) + "…" : texte),
                                Map.of("code", code, "text", texte, "source", "SFD" + (uc == null ? "" : " " + uc)),
                                "Règle de gestion citée dans la SFD ; à relier à une règle DRL exécutable ou à un contrôle humain.",
                                ligne.strip(), "extraction", 0.9));
                    }
            }
        }
    }

    private static String extrait(Matcher m, String texte) {
        m.reset();
        if (!m.find()) return null;
        int a = Math.max(0, m.start() - 60), b = Math.min(texte.length(), m.end() + 60);
        return texte.substring(a, b).replace('\n', ' ');
    }

    static String normaliser(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9%]+", " ").strip();
    }
}
