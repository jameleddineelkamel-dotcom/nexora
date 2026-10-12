package com.nexora.procedures.agents;

import com.nexora.procedures.agents.Agents.Proposition;
import com.nexora.procedures.studio.Modele.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Données d'une procédure tirées du texte de la SFD lorsqu'elle n'a pas (ou pas complètement) de modèle de données :
 * scénarios des cas d'utilisation (« il renseigne le numéro de circuit… », « l'agent saisit la référence (exactement
 * 5 caractères) et la date… »), options proposées au demandeur (« sélectionne « Domiciliation des biens » ») et
 * règles de gestion (montant, devise). Produit des propositions de sections, champs, types de demande et, si la
 * version ne les a pas, le socle nécessaire à l'exécution : identification du demandeur et lieu de traitement.
 * Les codes de champs sont formés en anglais (camelCase) pour faciliter le rapprochement SCRDM du Data Agent.
 */
final class DonneesSfd {

    /** Section des données saisies par l'organisme instructeur dans sa tâche (contrat du Runtime). */
    static final String INSTRUCTION = "INSTRUCTION";

    private static final Pattern SAISIE = Pattern.compile("(?i)\\b(renseigne(?:nt)?|saisi(?:t|ssent|r)|indique(?:nt)?|pr[ée]cise(?:nt)?|remplit|compl[èe]te(?:nt)?)\\s+(.+)");
    private static final Pattern OPTION = Pattern.compile("(?i)\\bs[ée]lectionne(?:nt)?\\s+«\\s*([^»]{3,60}?)\\s*»");
    private static final Pattern FIN_PHRASE = Pattern.compile("(?i)\\s(puis|avant de|afin de|pour|et clique|et valide|et confirme|et attache|et joint)\\s.*$");
    private static final Pattern CONTRAINTE = Pattern.compile("(?i)\\((?:exactement\\s+)?(\\d{1,3})\\s+caract[èe]res?(\\s+maximum)?\\)");
    private static final Pattern EXACT = Pattern.compile("(?i)exactement\\s+(\\d{1,3})\\s+caract");
    private static final Set<String> VIDES = Set.of("le", "la", "les", "l", "d", "de", "du", "des", "a", "au", "aux", "en", "et", "un", "une", "son", "sa",
            "ses", "leur", "leurs", "pour", "par", "sur", "ainsi", "que", "qui", "ce", "cette");

    /** Noms de tête reconnus comme données, avec leur traduction (code anglais) et leur type. */
    private static final Map<String, String[]> TETES = new LinkedHashMap<>();
    /** Compléments fréquents traduits pour former le code. */
    private static final Map<String, String> MOTS = new HashMap<>();

    static {
        String[][] t = {
                {"numero", "Number", "TEXT", "35"}, {"reference", "Reference", "TEXT", "35"}, {"code", "Code", "TEXT", "17"}, {"date", "Date", "DATE", null},
                {"annee", "Year", "YEAR", null}, {"montant", "Amount", "DECIMAL", null}, {"valeur", "Value", "DECIMAL", null}, {"devise", "CurrencyCode", "CODE", null},
                {"nom", "Name", "TEXT", "200"}, {"raison", "Name", "TEXT", "200"}, {"adresse", "Address", "TEXT", "200"}, {"pays", "CountryCode", "CODE", null},
                {"quantite", "Quantity", "NUMBER", null}, {"poids", "Weight", "DECIMAL", null}, {"nombre", "Count", "NUMBER", null},
                {"identifiant", "Identifier", "TEXT", "35"}, {"taux", "Rate", "DECIMAL", null}, {"motif", "Reason", "TEXT", "500"},
                {"observation", "Remark", "TEXT", "500"}, {"description", "Description", "TEXT", "500"}, {"libelle", "Label", "TEXT", "200"},
                {"type", "TypeCode", "TEXT", "17"}, {"nature", "NatureCode", "TEXT", "17"}, {"mode", "ModeCode", "TEXT", "17"}, {"banque", "BankCode", "TEXT", "17"},
                {"compte", "AccountNumber", "TEXT", "35"}, {"niu", "TaxIdentifier", "TEXT", "35"}, {"objet", "Purpose", "TEXT", "200"}};
        for (String[] l : t) TETES.put(l[0], new String[]{l[1], l[2], l[3]});
        String[][] m = {{"circuit", "Circuit"}, {"declaration", "Declaration"}, {"importation", "Import"}, {"exportation", "Export"}, {"di", "ImportDeclaration"},
                {"de", ""}, {"domiciliation", "Domiciliation"}, {"delivrance", "Issue"}, {"emission", "Issue"}, {"facture", "Invoice"}, {"proforma", "Proforma"},
                {"marchandise", "Goods"}, {"marchandises", "Goods"}, {"biens", "Goods"}, {"service", "Service"}, {"services", "Service"}, {"transfert", "Transfer"},
                {"origine", "Origin"}, {"provenance", "Provenance"}, {"destination", "Destination"}, {"fournisseur", "Supplier"}, {"contrat", "Contract"},
                {"operation", "Operation"}, {"expiration", "Expiry"}, {"validite", "Validity"}, {"demande", "Request"}, {"dossier", "File"}, {"titre", "Title"},
                {"licence", "Licence"}, {"autorisation", "Authorization"}, {"position", "Position"}, {"tarifaire", "Tariff"}, {"douane", "Customs"},
                {"paiement", "Payment"}, {"reglement", "Settlement"}, {"beneficiaire", "Beneficiary"}, {"agence", "Branch"}, {"banque", "Bank"},
                {"signature", "Signature"}, {"sociale", ""}, {"importateur", "Importer"}, {"exportateur", "Exporter"}, {"vendeur", "Seller"},
                {"acheteur", "Buyer"}, {"mois", "Month"}, {"enregistrement", "Registration"}, {"agrement", "Approval"}, {"commande", "Order"}};
        for (String[] l : m) MOTS.put(l[0], l[1]);
    }

    private DonneesSfd() {}

    /** Champ candidat, avant mise en forme de la proposition. */
    record Champ(String code, String libelle, String section, String type, Integer longueur, String format, String liste, boolean recherche,
                 String source, String origine, double confiance) {}

    // ------------------------------------------------------------------ extraction heuristique
    static void proposer(TexteSource src, String texte, VersionComplete v, List<Proposition> props) {
        List<Champ> champs = new ArrayList<>();
        List<String[]> options = new ArrayList<>();   // {libellé, citation}
        String acteurTraitement = null;
        for (TexteSource.Tableau t : src.tableaux()) {
            String acteur = null, cas = null;
            for (List<String> l : t.lignes()) {
                if (l.size() < 2) continue;
                String entete = DocumentAgent.normaliser(l.get(0));
                if (entete.startsWith("acteur principal")) acteur = l.get(1).replace('\n', ' ').strip();
                if (entete.startsWith("nom du cas")) cas = l.get(1).strip();
            }
            if (acteur == null) continue;
            boolean demandeur = estDemandeur(acteur);
            if (!demandeur && acteurTraitement == null && !DocumentAgent.normaliser(acteur).contains("plateforme")) acteurTraitement = premierActeur(acteur);
            for (List<String> l : t.lignes()) {
                if (l.size() < 2 || !DocumentAgent.normaliser(l.get(0)).startsWith("scenario")) continue;
                for (String phrase : phrases(l.get(1))) {
                    Matcher o = OPTION.matcher(phrase);
                    while (o.find()) if (demandeur && !DocumentAgent.normaliser(o.group(1)).matches("(initier|charger|envoyer|valider|consulter).*"))
                        options.add(new String[]{o.group(1).strip(), phrase.strip()});
                    Matcher s = SAISIE.matcher(phrase);
                    if (s.find()) for (String item : elements(s.group(2))) {
                        Champ c = champ(item, demandeur ? "DEMANDE" : INSTRUCTION, phrase.strip(), "SFD" + (cas == null ? "" : " — " + cas), 0.7);
                        if (c != null) champs.add(c);
                    }
                }
            }
        }
        // Montant et devise cités par les règles de gestion (seuils, référence construite)
        String n = DocumentAgent.normaliser(texte);
        if (n.contains("d un montant") || n.contains("montant superieur") || n.contains("montant inferieur"))
            champs.add(new Champ("operationAmount", "Montant de l'opération", "DEMANDE", "DECIMAL", null, null, null, true,
                    citation(texte, "montant"), "Règle de gestion (seuil de montant)", 0.55));
        if (n.contains(" devise"))
            champs.add(new Champ("operationCurrencyCode", "Devise de l'opération", "DEMANDE", "CODE", 3, null, "REF_CURRENCY", false,
                    citation(texte, "devise"), "Règle de gestion (devise)", 0.55));
        ajouter(champs, options, acteurTraitement, texte, v, props);
    }

    /** Champs proposés par le modèle de langage (citations déjà vérifiées par l'appelant). */
    static Champ depuisModele(String libelle, String section, String type, Integer longueur, String source, String modele) {
        Champ c = champ(libelle, "TRAITEMENT".equals(section) || INSTRUCTION.equals(section) ? INSTRUCTION : "DEMANDE", source, "modèle " + modele, 0.6, false);
        if (c == null) return null;
        String t = type != null && Set.of("TEXT", "DATE", "YEAR", "DECIMAL", "NUMBER", "CODE", "EMAIL", "BOOLEAN").contains(type) ? type : c.type();
        return new Champ(c.code(), c.libelle(), c.section(), t, longueur != null && longueur > 0 ? longueur : c.longueur(), c.format(), c.liste(),
                c.recherche(), source, c.origine(), 0.6);
    }

    static void ajouter(List<Champ> champs, List<String[]> options, String acteurTraitement, String texte, VersionComplete v, List<Proposition> props) {
        Set<String> existants = new HashSet<>();
        v.fields().forEach(f -> existants.add(f.code()));
        Set<String> libellesExistants = new HashSet<>();
        v.fields().forEach(f -> libellesExistants.add(DocumentAgent.normaliser(f.labelFr())));
        props.forEach(p -> { if (p.kind().equals("FIELD")) libellesExistants.add(DocumentAgent.normaliser(String.valueOf(p.payload().get("labelFr")))); });
        props.forEach(p -> { if (p.kind().equals("FIELD")) existants.add(p.target()); });
        Set<String> sections = new HashSet<>();
        v.sections().forEach(s -> sections.add(s.code()));
        props.forEach(p -> { if (p.kind().equals("SECTION")) sections.add(p.target()); });
        int ordre = v.fields().stream().mapToInt(Field::sortOrder).max().orElse(0);
        int ordreSection = v.sections().stream().mapToInt(Section::sortOrder).max().orElse(0);

        // Socle d'exécution : identification du demandeur et lieu de traitement (cotation)
        List<Champ> socle = new ArrayList<>();
        if (v.fields().stream().noneMatch(f -> "niu".equals(f.prefill()) || f.code().equals("taxIdentifier")))
            socle.add(new Champ("taxIdentifier", "Numéro d'identification unique (NIU)", "IDENTITE", "TEXT", 35, null, null, true, null, "socle", 0.9));
        if (v.fields().stream().noneMatch(f -> "legalName".equals(f.prefill()) || f.code().equals("legalName")))
            socle.add(new Champ("legalName", "Nom / Raison sociale", "IDENTITE", "TEXT", 200, null, null, true, null, "socle", 0.9));
        if (v.fields().stream().noneMatch(f -> "@LIEUX_TRAITEMENT".equals(f.refdataTable())))
            socle.add(new Champ("processingLocation", acteurTraitement != null ? acteurTraitement : "Lieu de traitement", "TRAITEMENT", "CODE", 10, null,
                    "@LIEUX_TRAITEMENT", true, null, "socle", 0.85));
        List<Champ> tous = new ArrayList<>(socle);
        tous.addAll(champs);

        boolean saisieAgent = false;
        for (Champ c : tous) {
            if (existants.contains(c.code()) || (!c.origine().equals("socle") && libellesExistants.contains(DocumentAgent.normaliser(c.libelle())))) continue;
            existants.add(c.code());
            if (!sections.contains(c.section())) {
                sections.add(c.section());
                String[] s = switch (c.section()) {
                    case "IDENTITE" -> new String[]{"Informations générales", "General information"};
                    case "TRAITEMENT" -> new String[]{"Informations relatives au traitement", "Processing information"};
                    case INSTRUCTION -> new String[]{"Données saisies par l'organisme instructeur", "Data entered by the processing body"};
                    default -> new String[]{"Demande", "Request"};
                };
                ordreSection += 10;
                props.add(new Proposition("SECTION", c.section(), "Section « " + s[0] + " »",
                        new LinkedHashMap<>(Map.of("code", c.section(), "labelFr", s[0], "labelEn", s[1], "repeating", false, "minRows", 0,
                                "sortOrder", c.section().equals("IDENTITE") ? 5 : ordreSection)),
                        "Section du formulaire nécessaire aux champs proposés.", null, "generation", 0.9));
            }
            ordre += 10;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("code", c.code());
            p.put("sectionCode", c.section());
            p.put("labelFr", c.libelle());
            p.put("labelEn", null);
            p.put("dataType", c.liste() != null ? "CODE" : c.type());
            p.put("maxLength", c.longueur());
            p.put("pattern", c.format());
            p.put("mandatory", "OUI");
            p.put("refdataTable", c.liste());
            p.put("searchable", c.recherche());
            p.put("prefill", switch (c.code()) { case "taxIdentifier" -> "niu"; case "legalName" -> "legalName"; default -> null; });
            p.put("amendable", !c.code().equals("taxIdentifier"));
            p.put("helpFr", c.code().equals("processingLocation") ? "Détermine l'unité de traitement (ex. agence bancaire) et la cotation du dossier." : null);
            p.put("sortOrder", ordre);
            String raison = switch (c.origine()) {
                case "socle" -> c.code().equals("processingLocation")
                        ? "Socle d'exécution : le lieu de traitement désigne l'unité qui instruit le dossier" + (acteurTraitement != null ? " (" + acteurTraitement + ")" : "")
                        + " et sert à la cotation. Les unités se paramètrent dans e-Services › Administration › Cotation."
                        : "Socle d'exécution : identification du demandeur, pré-remplie depuis le registre des opérateurs.";
                default -> "Donnée citée dans " + c.origine() + (c.longueur() != null && c.format() != null ? " ; longueur exacte " + c.longueur() + " (format contrôlé)" : "")
                        + ". Le Data Agent la rapprochera ensuite du SCRDM.";
            };
            props.add(new Proposition("FIELD", c.code(), "Champ « " + c.libelle() + " » (" + p.get("dataType") + ", section " + c.section() + ")", p,
                    raison, c.source(), c.origine().equals("socle") ? "generation" : "extraction", c.confiance()));
            if (c.section().equals(INSTRUCTION)) saisieAgent = true;
        }
        if (saisieAgent)
            props.add(new Proposition("FINDING", "saisie-agent", "Données saisies par l'organisme instructeur",
                    Map.of("severity", "info", "rule", "DONNEES-INSTRUCTION", "message",
                            "Les données que la SFD fait saisir par l'organisme instructeur sont placées dans la section INSTRUCTION : absentes du "
                                    + "formulaire du demandeur, elles sont saisies par l'agent dans sa tâche de traitement. Pour les rendre obligatoires à une "
                                    + "étape, déclarez-les dans la propriété « fields » de la tâche BPMN (ex. fields = domiciliationReference,domiciliationDate)."),
                    "Répartition des saisies entre le demandeur et l'organisme instructeur, d'après les acteurs des cas d'utilisation.", null, "check", 0.9));

        // Types de demande : options proposées au demandeur, sinon un type unique
        if (v.requestTypes().isEmpty() && props.stream().noneMatch(p -> p.kind().equals("REQUEST_TYPE"))) {
            List<String> natures = new ArrayList<>(List.of("INITIAL"));
            String n = DocumentAgent.normaliser(texte);
            if (n.contains("modification")) natures.add("AMENDMENT");
            if (n.contains("annulation")) natures.add("CANCELLATION");
            Map<String, String[]> types = new LinkedHashMap<>();
            for (String[] o : options) {
                String code = codeType(o[0]);
                types.putIfAbsent(code, o);
            }
            String proc = v.procedure().code().replaceAll("[^A-Z]", "");
            int rang = 0;
            if (types.isEmpty()) types.put("STD", new String[]{v.procedure().labelFr(), null});
            for (var e : types.entrySet()) {
                rang += 10;
                String cur = (proc.length() >= 2 ? proc.substring(0, 2) : (proc + "XX").substring(0, 2)) + e.getKey().charAt(0);
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("code", e.getKey());
                p.put("labelFr", e.getValue()[0]);
                p.put("labelEn", null);
                p.put("curCode", cur);
                p.put("natures", natures);
                p.put("description", null);
                p.put("sortOrder", rang);
                props.add(new Proposition("REQUEST_TYPE", e.getKey(), "Type de demande « " + e.getValue()[0] + " » (CUR " + cur + ")", p,
                        (e.getValue()[1] != null ? "Option proposée au demandeur dans la SFD" : "Aucune option dans la SFD : type de demande unique")
                                + ". Natures : " + String.join(", ", natures) + (natures.size() > 1 ? " (modification / annulation décrites dans la SFD)" : "")
                                + ". Le code CUR (3 lettres) est à confirmer.", e.getValue()[1], e.getValue()[1] != null ? "extraction" : "generation", 0.7));
            }
        }
    }

    // ------------------------------------------------------------------ analyse des phrases
    private static List<String> phrases(String cellule) {
        List<String> r = new ArrayList<>();
        for (String bloc : cellule.split("\n"))
            for (String p : bloc.split("(?<=[.;])(?=\\s*[A-ZÉÈÀ0-9])")) if (!p.isBlank()) r.add(p.strip());
        return r;
    }

    /** Énumération « le numéro de X et de Y, ainsi que l'année de Z » → éléments, la tête élidée étant reprise. */
    static List<String> elements(String suite) {
        String s = FIN_PHRASE.matcher(suite).replaceAll("").replaceAll("[.;:]+$", "").strip();
        List<String> r = new ArrayList<>();
        String tete = null;
        List<String> morceaux = new ArrayList<>();
        for (String brut : s.split(",\\s*ainsi que\\s+|\\s+ainsi que\\s+|,\\s*|\\s+et\\s+(?=(?:le|la|les|l'|l’|son|sa|ses|de|du|des|d'|d’)\\b)")) {
            // « montant du transfert et observation » : « et » suivi directement d'un nom de donnée
            String reste = brut;
            Matcher et = Pattern.compile("\\s+et\\s+(\\p{L}+)").matcher(reste);
            while (et.find() && TETES.containsKey(DocumentAgent.normaliser(et.group(1)))) {
                morceaux.add(reste.substring(0, et.start()));
                reste = reste.substring(et.start()).replaceFirst("^\\s+et\\s+", "");
                et = Pattern.compile("\\s+et\\s+(\\p{L}+)").matcher(reste);
            }
            morceaux.add(reste);
        }
        for (String brut : morceaux) {
            // Ponctuation de tête, incise après « ; », parenthèse orpheline, points de suspension
            String item = brut.split(";")[0].replaceAll("^[\\s:,\\-–—]+", "").replace("…", "").strip();
            if (item.chars().filter(ch -> ch == ')').count() > item.chars().filter(ch -> ch == '(').count()) item = item.replace(")", "").strip();
            if (item.isEmpty()) continue;
            String norm = DocumentAgent.normaliser(item);
            if (norm.matches("^(de|du|des|d)\\b.*") && tete != null) item = tete + " " + item;
            else {
                String[] mots = DocumentAgent.normaliser(sansArticle(item)).split("\\s+");
                tete = mots.length > 0 ? sansArticle(item).split("\\s+")[0] : null;
            }
            r.add(sansArticle(item));
        }
        return r;
    }

    private static String sansArticle(String s) {
        return s.strip().replaceFirst("(?i)^(le|la|les|son|sa|ses|leur|leurs|un|une)\\s+", "").replaceFirst("(?i)^(l'|l’)", "").strip();
    }

    /** Champ candidat à partir d'un groupe nominal, ou null s'il ne désigne pas une donnée. */
    static Champ champ(String groupe, String section, String source, String origine, double confiance) {
        return champ(groupe, section, source, origine, confiance, true);
    }

    /** {@code strict} : seuls les groupes dont le mot de tête désigne une donnée (numéro, date, montant…) sont retenus. */
    static Champ champ(String groupe, String section, String source, String origine, double confiance, boolean strict) {
        Integer longueur = null;
        String format = null;
        Matcher c = CONTRAINTE.matcher(groupe);
        if (c.find()) {
            longueur = Integer.parseInt(c.group(1));
            if (EXACT.matcher(groupe).find()) format = "^.{" + longueur + "}$";
        }
        String libelle = CONTRAINTE.matcher(groupe).replaceAll("").replaceAll("\\([^)]*\\)", "").replaceAll("\\s+", " ").strip();
        libelle = libelle.replaceAll("[«»\"]", "").strip();
        if (libelle.length() < 4 || libelle.length() > 90) return null;
        List<String> mots = new ArrayList<>();
        for (String m : DocumentAgent.normaliser(libelle).split("\\s+")) if (!m.isBlank() && !VIDES.contains(m)) mots.add(m);
        if (mots.isEmpty() || (strict && !TETES.containsKey(mots.getFirst()))) return null;
        String[] tete = TETES.getOrDefault(mots.getFirst(), new String[]{anglais(mots.getFirst()), "TEXT", "200"});
        List<String> complements = new ArrayList<>(mots.subList(1, mots.size()));
        Collections.reverse(complements);
        StringBuilder code = new StringBuilder();
        for (String m : complements) code.append(anglais(m));
        code.append(tete[0]);
        String cle = code.substring(0, 1).toLowerCase(Locale.ROOT) + code.substring(1);
        if (cle.length() > 40) cle = cle.substring(0, 40);
        String type = tete[1];
        String liste = mots.getFirst().equals("devise") ? "REF_CURRENCY" : mots.getFirst().equals("pays") ? "REF_COUNTRY" : null;
        if (longueur == null && tete[2] != null) longueur = Integer.parseInt(tete[2]);
        boolean recherche = Set.of("numero", "reference", "identifiant", "niu").contains(mots.getFirst());
        String l = Character.toUpperCase(libelle.charAt(0)) + libelle.substring(1);
        return new Champ(cle, l, section, type, type.equals("TEXT") || type.equals("CODE") ? longueur : null, format, liste, recherche, source, origine, confiance);
    }

    private static String anglais(String mot) {
        String m = MOTS.get(mot);
        if (m != null) return m;
        if (TETES.containsKey(mot)) return TETES.get(mot)[0];
        return mot.isEmpty() ? "" : Character.toUpperCase(mot.charAt(0)) + mot.substring(1);
    }

    private static String codeType(String libelle) {
        List<String> mots = new ArrayList<>();
        for (String m : DocumentAgent.normaliser(libelle).split("\\s+")) if (!m.isBlank() && !VIDES.contains(m)) mots.add(m);
        String dernier = mots.isEmpty() ? "STD" : mots.getLast();
        return dernier.toUpperCase(Locale.ROOT).substring(0, Math.min(10, dernier.length()));
    }

    private static boolean estDemandeur(String acteur) {
        String n = DocumentAgent.normaliser(acteur);
        for (String m : List.of("importateur", "exportateur", "operateur", "demandeur", "cad", "commissionnaire", "declarant", "usager"))
            if (n.contains(m)) return true;
        return false;
    }

    private static String premierActeur(String acteur) {
        String a = acteur.split("[;(/]")[0].strip();
        return a.isEmpty() ? null : Character.toUpperCase(a.charAt(0)) + a.substring(1);
    }

    private static String citation(String texte, String mot) {
        for (String l : texte.split("\n")) {
            int i = DocumentAgent.normaliser(l).indexOf(mot);
            if (i >= 0) {
                String s = l.replace(" ¶ ", " ").strip();
                return s.length() > 300 ? s.substring(0, 300) + "…" : s;
            }
        }
        return null;
    }
}
