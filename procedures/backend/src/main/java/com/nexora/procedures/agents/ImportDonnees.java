package com.nexora.procedures.agents;

import com.nexora.procedures.agents.Agents.Proposition;
import com.nexora.procedures.studio.Modele.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formulaire d'une procédure composé à partir de ses annexes : modèles de données (classeurs DM, une feuille par
 * entité) et maquettes d'écrans (wireframes HTML).
 * <ul>
 *   <li>avec une maquette : l'écran de saisie principal (et ses variantes, qui deviennent les types de demande) donne
 *       les blocs, l'ordre et les libellés ; chaque champ est rapproché de l'attribut du modèle de données qui porte le
 *       même sens (code, format, liste de codes, obligation, références UNTDED / OMD / UN/CCL) ;</li>
 *   <li>sans maquette : l'entité propre à la procédure (PROCEDURE_&lt;code&gt;), son entité parente (le dossier) et ses
 *       entités filles 1 — N (marchandises, services…) deviennent des sections, répétitives pour les filles.</li>
 * </ul>
 * Les attributs techniques (identifiants, clés, traçabilité) ne deviennent jamais des champs.
 */
final class ImportDonnees {

    private static final Set<String> VIDES = Set.of("de", "du", "des", "la", "le", "les", "l", "d", "en", "a", "au", "aux", "et", "un", "une", "fk",
            "code", "identifiant", "n", "no", "numero", "par", "pour", "sur");
    private static final Pattern FORMAT = Pattern.compile("(?i)^\\s*(a|an|n)(\\.\\.)?(\\d{1,4})(?:[,.](\\d{1,2}))?\\s*$");
    private static final Set<String> BLOCS_IGNORES = Set.of("frais", "paiement", "procedures declenchees", "confirmation", "pieces", "historique",
            "documents joints", "pieces jointes", "actions");
    private static final Map<String, String[]> PREREMPLI = new LinkedHashMap<>();

    static {
        // libellé (normalisé, début) → { attribut du registre des opérateurs, code du champ }
        PREREMPLI.put("numero contribuable", new String[]{"niu", "taxIdentifier"});
        PREREMPLI.put("niu", new String[]{"niu", "taxIdentifier"});
        PREREMPLI.put("nom", new String[]{"legalName", "legalName"});
        PREREMPLI.put("raison sociale", new String[]{"legalName", "legalName"});
        PREREMPLI.put("adresse", new String[]{"street", "streetAddress"});
        PREREMPLI.put("telephone", new String[]{"mobile", "mobilePhone"});
        PREREMPLI.put("mail", new String[]{"email", "emailAddress"});
        PREREMPLI.put("courriel", new String[]{"email", "emailAddress"});
        PREREMPLI.put("pays", new String[]{"countryCode", "partyCountryCode"});
        PREREMPLI.put("ville", new String[]{"city", "cityName"});
    }

    /** Attribut candidat ; {@code prefere} : entité de la procédure (dossier, entité propre, filles), sinon autre modèle. */
    private record Source(ModeleDonnees modele, ModeleDonnees.Entite entite, ModeleDonnees.Attribut attribut, boolean prefere) {}

    private final List<ModeleDonnees> modeles;
    private final VersionComplete v;
    private final Referentiel referentiel;
    private final List<Proposition> props;
    private final Set<String> codes = new HashSet<>(), sections = new HashSet<>();
    private int ordreChamp, ordreSection;
    private final List<String> constats = new ArrayList<>();
    private final Map<String, String> libelles = new HashMap<>(), sectionDe = new HashMap<>();
    private final Set<String> repetitives = new HashSet<>();
    private final Map<String, Field> existants = new HashMap<>();
    private final Map<String, Section> sectionsExistantes = new LinkedHashMap<>();
    /** Variante de l'écran → code du type de demande. */
    private final Map<String, String> typeDeVariante = new HashMap<>();

    private ImportDonnees(List<ModeleDonnees> modeles, VersionComplete v, Referentiel referentiel, List<Proposition> props) {
        this.modeles = modeles;
        this.v = v;
        this.referentiel = referentiel;
        this.props = props;
        // Le formulaire suit l'ordre de la source : les champs et sections déjà présents sont mis à jour (déplacés, renommés)
        v.fields().forEach(f -> existants.put(f.code(), f));
        v.sections().forEach(x -> sectionsExistantes.put(x.code(), x));
    }

    /** Ajoute les propositions ; renvoie le résumé. */
    static String proposer(List<ModeleDonnees> modeles, List<Maquette> maquettes, VersionComplete v, Referentiel referentiel, List<Proposition> props) {
        ImportDonnees i = new ImportDonnees(modeles, v, referentiel, props);
        int avant = props.size();
        String detail = maquettes.stream().anyMatch(m -> !m.ecrans().isEmpty()) ? i.depuisMaquettes(maquettes) : i.depuisModeles();
        long champs = props.subList(avant, props.size()).stream().filter(p -> p.kind().equals("FIELD")).count();
        i.constats.forEach(c -> props.add(new Proposition("FINDING", "donnees-" + Integer.toHexString(c.hashCode()), "Modèle de données : point à vérifier",
                Map.of("severity", "info", "rule", "IMPORT-DONNEES", "message", c), c, null, "check", 0.9)));
        return champs + " champ(s) proposé(s) " + detail;
    }

    // ------------------------------------------------------------------ entités du modèle utiles à la procédure
    private String entiteProcedure() {
        String cible = "PROCEDURE_" + v.procedure().code().toUpperCase(Locale.ROOT);
        for (ModeleDonnees m : modeles) if (m.entites().containsKey(cible)) return cible;
        return null;
    }

    private ModeleDonnees modeleDe(String entite) {
        return modeles.stream().filter(m -> m.entites().containsKey(entite)).findFirst().orElse(null);
    }

    private static boolean filleUtile(String nom) {
        String n = nom.toUpperCase(Locale.ROOT);
        return !(n.startsWith("PROCEDURE_") || n.contains("CHARGES") || n.contains("DOCUMENT") || n.contains("ATTACHMENT") || n.contains("FIELD_CONFIG")
                || n.equals("DOSSIER_PROCEDURE") || n.startsWith("REF_") || n.contains("PARTY"));
    }

    /** Entités dans lesquelles chercher les attributs, par ordre de préférence. */
    private List<Source> candidats(boolean tableau, boolean partie) {
        List<Source> r = new ArrayList<>();
        String proc = entiteProcedure();
        List<String> ordre = new ArrayList<>();
        if (proc != null) {
            ModeleDonnees m = modeleDe(proc);
            String racine = m.parent(proc);
            if (tableau && racine != null) ordre.addAll(m.enfants(racine).stream().filter(ImportDonnees::filleUtile).toList());
            if (partie) m.entites().keySet().stream().filter(e -> e.contains("PARTY")).forEach(ordre::add);
            ordre.add(proc);
            if (racine != null) ordre.add(racine);
            if (!tableau && racine != null) ordre.addAll(m.enfants(racine).stream().filter(ImportDonnees::filleUtile).toList());
        }
        Set<String> preferees = new HashSet<>(ordre);
        for (ModeleDonnees m : modeles) ordre.addAll(m.entites().keySet());
        Set<String> vus = new LinkedHashSet<>(ordre);
        for (String e : vus) {
            ModeleDonnees m = modeleDe(e);
            for (ModeleDonnees.Attribut a : m.entites().get(e).attributs())
                if (!ModeleDonnees.technique(a)) r.add(new Source(m, m.entites().get(e), a, preferees.contains(e)));
        }
        return r;
    }

    // ------------------------------------------------------------------ composition depuis les maquettes
    private String depuisMaquettes(List<Maquette> maquettes) {
        // Écran de saisie principal : le groupe d'écrans « préfixe — variante » qui porte le plus de champs saisissables
        Map<String, List<Maquette.Ecran>> groupes = new LinkedHashMap<>();
        for (Maquette mq : maquettes)
            for (Maquette.Ecran e : mq.ecrans()) {
                String[] parties = e.titre().split("\\s+[—–-]\\s+");
                String cle = parties.length >= 3 ? String.join(" — ", Arrays.copyOf(parties, parties.length - 1)) : e.titre();
                groupes.computeIfAbsent(cle, k -> new ArrayList<>()).add(e);
            }
        var principal = groupes.entrySet().stream().max(Comparator.comparingLong(g -> g.getValue().stream()
                .flatMap(e -> e.blocs().stream()).flatMap(b -> b.champs().stream()).filter(c -> !c.lectureSeule()).count())).orElseThrow();
        List<Maquette.Ecran> ecrans = principal.getValue();
        List<String> variantes = new ArrayList<>();
        if (ecrans.size() > 1)
            for (Maquette.Ecran e : ecrans) {
                String[] p = e.titre().split("\\s+[—–-]\\s+");
                variantes.add(p[p.length - 1].strip());
            }
        Set<String> variantesN = new HashSet<>();
        variantes.forEach(x -> variantesN.add(DocumentAgent.normaliser(x)));
        for (String x : variantes) typeDeVariante.put(x, codeType(x));

        // Blocs et champs : union des variantes, en retenant les variantes où chaque champ apparaît
        Map<String, Maquette.Bloc> blocs = new LinkedHashMap<>();
        Map<String, Map<String, Maquette.Champ>> champs = new LinkedHashMap<>();
        Map<String, Set<String>> presence = new HashMap<>();
        for (int i = 0; i < ecrans.size(); i++)
            for (Maquette.Bloc b : ecrans.get(i).blocs()) {
                String nb = DocumentAgent.normaliser(b.titre());
                if (BLOCS_IGNORES.stream().anyMatch(nb::contains)) continue;
                blocs.putIfAbsent(nb, b);
                for (Maquette.Champ c : b.champs()) {
                    // Sélecteur de variante (« Type d'opération » : Classique, Médicaments…) = type de demande
                    if (!c.options().isEmpty() && c.options().stream().map(DocumentAgent::normaliser).allMatch(o -> variantesN.stream().anyMatch(x -> x.contains(o))))
                        continue;
                    champs.computeIfAbsent(nb, k -> new LinkedHashMap<>()).putIfAbsent(DocumentAgent.normaliser(c.libelle()), c);
                    if (!variantes.isEmpty()) presence.computeIfAbsent(nb + "|" + DocumentAgent.normaliser(c.libelle()), k -> new LinkedHashSet<>()).add(variantes.get(i));
                }
            }
        int rapproches = 0, total = 0;
        for (var b : blocs.entrySet()) {
            Map<String, Maquette.Champ> liste = champs.getOrDefault(b.getKey(), Map.of());
            if (liste.isEmpty()) continue;
            Maquette.Bloc bloc = b.getValue();
            boolean partie = b.getKey().contains("importateur") || b.getKey().contains("exportateur") || b.getKey().contains("partie")
                    || b.getKey().contains("demandeur") || b.getKey().contains("operateur");
            String section = section(bloc.titre(), bloc.tableau(), bloc.obligatoire() ? 1 : 0, "Maquette « " + ecrans.getFirst().titre() + " »");
            List<Source> sources = candidats(bloc.tableau(), partie);
            for (Maquette.Champ c : liste.values()) {
                total++;
                Set<String> ou = presence.getOrDefault(b.getKey() + "|" + DocumentAgent.normaliser(c.libelle()), Set.of());
                // Champ propre à certaines variantes : affiché et exigé seulement pour leurs types de demande
                String restriction = !variantes.isEmpty() && ou.size() < variantes.size()
                        ? "typeDemande in (" + String.join(", ", ou.stream().map(typeDeVariante::get).distinct().toList()) + ")" : null;
                Source s = rapprocher(c.libelle(), sources);
                if (s != null) rapproches++;
                champ(section, c, s, partie, restriction, "Maquette « " + ecrans.getFirst().titre() + " », bloc « " + bloc.titre() + " »");
            }
        }
        if (!variantes.isEmpty()) typesDemande(variantes, "Variantes de l'écran « " + principal.getKey() + " »");
        // Sections de la version absentes de la maquette (ex. gabarit) : placées après celles de l'écran
        for (Section x : sectionsExistantes.values()) {
            if (sections.contains(x.code())) continue;
            ordreSection += 10;
            int ordre = 500 + ordreSection;
            if (x.sortOrder() >= 500) continue;
            props.add(new Proposition("SECTION", x.code(), "Section « " + x.labelFr() + " » placée après les blocs de la maquette",
                    new LinkedHashMap<>(Map.of("code", x.code(), "labelFr", x.labelFr(), "labelEn", Objects.toString(x.labelEn(), x.labelFr()), "repeating", x.repeating(),
                            "minRows", x.minRows(), "sortOrder", ordre)),
                    "Le formulaire suit l'ordre de la maquette ; cette section n'y figure pas (ses champs éventuellement repris par la maquette y ont été déplacés).",
                    x.labelFr(), "generation", 0.8));
        }
        return "depuis la maquette « " + principal.getKey() + " » (" + ecrans.size() + " variante(s)) : " + rapproches + " / " + total
                + " rapproché(s) d'un attribut du modèle de données" + (modeles.isEmpty() ? "" : " (" + String.join(", ", modeles.stream().map(ModeleDonnees::nom).toList()) + ")");
    }

    // ------------------------------------------------------------------ composition depuis les modèles seuls
    private String depuisModeles() {
        String proc = entiteProcedure();
        if (proc == null) {
            constats.add("Aucune entité « PROCEDURE_" + v.procedure().code() + " » dans les modèles de données joints : joignez la maquette de l'écran "
                    + "de saisie, ou nommez l'entité de la procédure ainsi, pour composer le formulaire.");
            return "(aucune entité propre à la procédure)";
        }
        ModeleDonnees m = modeleDe(proc);
        String racine = m.parent(proc);
        List<String> entites = new ArrayList<>();
        if (racine != null) entites.add(racine);
        entites.add(proc);
        if (racine != null) entites.addAll(m.enfants(racine).stream().filter(ImportDonnees::filleUtile).toList());
        for (String e : entites) {
            ModeleDonnees.Entite en = m.entites().get(e);
            boolean fille = racine != null && !e.equals(racine) && !e.equals(proc);
            String titre = libelleEntite(en);
            String section = section(titre, fille, 0, m.nom() + " › " + e);
            for (ModeleDonnees.Attribut a : en.attributs())
                if (!ModeleDonnees.technique(a))
                    champ(section, null, new Source(m, en, a, true), false, null, m.nom() + " › " + e);
        }
        return "depuis le modèle de données « " + m.nom() + " » (" + String.join(", ", entites) + ")";
    }

    private static String libelleEntite(ModeleDonnees.Entite e) {
        Matcher m = Pattern.compile("[-–—]\\s*(.+?)(\\.|$)").matcher(e.description());
        return m.find() ? m.group(1).strip() : e.nom();
    }

    // ------------------------------------------------------------------ rapprochement libellé ↔ attribut
    private static Set<String> mots(String s) {
        Set<String> r = new LinkedHashSet<>();
        for (String m : DocumentAgent.normaliser(s.replaceAll("\\(FK\\)", "")).replace("fcfa", "cfa").replace("pro forma", "proforma").split("\\s+"))
            if (!m.isBlank() && !VIDES.contains(m)) r.add(m.equals("devise") || m.equals("dvise") ? "devise" : m);
        return r;
    }

    private static Source rapprocher(String libelle, List<Source> sources) {
        Set<String> l = mots(libelle);
        if (l.isEmpty()) return null;
        Source meilleur = null;
        double score = 0;
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            Set<String> a = mots(s.attribut().libelle());
            if (a.isEmpty()) continue;
            Set<String> inter = new HashSet<>(l);
            inter.retainAll(a);
            Set<String> union = new HashSet<>(l);
            union.addAll(a);
            double j = (double) inter.size() / union.size() - (s.prefere() ? 0 : 0.25) - i * 1e-6;   // entités de la procédure d'abord
            if (j > score) {
                score = j;
                meilleur = s;
            }
        }
        return score >= 0.5 ? meilleur : null;
    }

    // ------------------------------------------------------------------ propositions
    private String section(String titre, boolean repetitive, int minimum, String origine) {
        List<String> m = new ArrayList<>();
        for (String x : DocumentAgent.normaliser(titre).split("\\s+"))
            if (x.length() > 2 && !Set.of("informations", "information", "donnees", "des", "les", "relatives", "concernant").contains(x)) m.add(x);
        String code = String.join("_", m).toUpperCase(Locale.ROOT);
        if (code.isEmpty()) code = "SECTION_" + (ordreSection + 10);
        if (code.length() > 20) code = code.substring(0, 20).replaceAll("_+$", "");
        if (repetitive) repetitives.add(code);
        if (sections.add(code)) {
            ordreSection += 10;
            Section ex = sectionsExistantes.get(code);
            if (ex != null && ex.labelFr().equals(titre) && ex.repeating() == repetitive && ex.sortOrder() == ordreSection) return code;
            props.add(new Proposition("SECTION", code, "Section « " + titre + " »" + (repetitive ? " (répétitive)" : ""),
                    new LinkedHashMap<>(Map.of("code", code, "labelFr", titre, "labelEn", titre, "repeating", repetitive, "minRows", repetitive ? Math.max(minimum, 0) : 0,
                            "sortOrder", ordreSection)),
                    origine + (repetitive ? " : groupe répétitif (tableau / relation 1 — N)." : "."), titre, "extraction", 0.85));
        }
        return code;
    }

    private void champ(String section, Maquette.Champ c, Source s, boolean partie, String restriction, String origine) {
        ModeleDonnees.Attribut a = s == null ? null : s.attribut();
        String libelle = c != null ? c.libelle() : a.libelle().replaceAll("\\s*\\(FK\\)", "").strip();
        String[] prerempli = null;
        if (partie && c != null)
            for (var e : PREREMPLI.entrySet()) if (DocumentAgent.normaliser(libelle).startsWith(e.getKey())) { prerempli = e.getValue(); break; }
        String code = prerempli != null ? prerempli[1] : a != null ? a.code() : codeLibre(libelle);
        // Dans un groupe répétitif, un attribut déjà porté par le dossier (ex. valeur FOB) reçoit un code propre à la ligne
        if (codes.contains(code) && repetitives.contains(section) && !DocumentAgent.normaliser(libelles.getOrDefault(code, "")).equals(DocumentAgent.normaliser(libelle)) || codes.contains(code) && repetitives.contains(section) && !section.equals(sectionDe.get(code)))
            code = codeLibre(section.toLowerCase(Locale.ROOT).replace('_', ' ')) + Character.toUpperCase(code.charAt(0)) + code.substring(1);
        if (!codes.add(code)) {
            if (libelles.containsKey(code) && !DocumentAgent.normaliser(libelles.get(code)).equals(DocumentAgent.normaliser(libelle)))
                constats.add("« " + libelle + " » rapproché du même attribut (" + code + ") que « " + libelles.get(code) + " » : champ non dupliqué, à vérifier.");
            return;
        }
        libelles.put(code, libelle);
        sectionDe.put(code, section);
        // Type, longueur, liste
        String type = "TEXT", table = null, format = null;
        Integer longueur = null;
        List<Map<String, Object>> valeurs = List.of();
        String liste = a == null ? null : a.liste();
        String fmt = a == null ? null : a.format();
        Matcher f = fmt == null ? null : FORMAT.matcher(fmt);
        if (f != null && f.matches()) {
            int n = Integer.parseInt(f.group(3));
            if (f.group(1).equalsIgnoreCase("n")) type = f.group(4) != null ? "DECIMAL" : "NUMBER";
            else {
                longueur = n;
                if (f.group(2) == null) format = "^.{" + n + "}$";
            }
        }
        String nl = DocumentAgent.normaliser(Objects.toString(liste, ""));
        if (liste != null && liste.toUpperCase(Locale.ROOT).contains("REF_")) {
            table = referentiel != null ? referentiel.table(liste) : Referentiel.synonyme(liste);
            if (table != null) type = "CODE";
            else constats.add("« " + libelle + " » : la liste « " + liste + " » n'existe pas dans le Référentiel Commun ; champ proposé en texte (ajoutez la table ou une liste locale).");
        } else if (nl.contains("iso 8601") || DocumentAgent.normaliser(libelle).startsWith("date")) {
            type = "DATE";
            longueur = null;
            format = null;
        } else if (nl.equals("oui non") || nl.contains("vrai faux") || DocumentAgent.normaliser(Objects.toString(fmt, "")).startsWith("booleen")) {
            type = "BOOLEAN";
            longueur = null;
        } else if (liste != null && liste.contains(",")) {
            valeurs = valeursLocales(Arrays.asList(liste.split("\\s*,\\s*")));
            type = "CODE";
        }
        if (a == null && c != null) {
            // Champ sans attribut : la maquette décide
            if (!c.options().isEmpty()) { type = "CODE"; valeurs = valeursLocales(c.options()); }
            else if (c.format() != null && c.format().matches("(?i)jj/mm/aaaa.*")) type = "DATE";
            else if (c.format() != null && c.format().matches("(?i)n\\.\\..*")) type = "DECIMAL";
            else {
                Matcher an = Pattern.compile("(?i)an?\\.\\.(\\d+)").matcher(Objects.toString(c.format(), ""));
                longueur = an.find() ? Integer.parseInt(an.group(1)) : 200;
            }
        }
        if (prerempli != null && "taxIdentifier".equals(code)) { type = "TEXT"; table = null; valeurs = List.of(); longueur = 35; }
        boolean obligatoire = c != null ? c.obligatoire() && !c.lectureSeule() : a.obligatoire();
        boolean calcule = c != null && c.format() != null && DocumentAgent.normaliser(c.format()).contains("calcule");
        ordreChamp += 10;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("code", code);
        p.put("sectionCode", section);
        p.put("labelFr", libelle);
        p.put("labelEn", null);
        p.put("dataType", type);
        p.put("maxLength", type.equals("TEXT") || type.equals("CODE") && table == null ? longueur : null);
        p.put("pattern", format);
        p.put("mandatory", obligatoire ? "OUI" : "NON");
        p.put("conditionText", restriction);
        p.put("refdataTable", table);
        p.put("codeValues", valeurs);
        p.put("untdedRef", a == null ? null : a.untded());
        p.put("wcoRef", a == null ? null : a.wco());
        p.put("cclDen", a == null ? null : a.den());
        p.put("cclUid", a == null ? null : a.uid());
        p.put("searchable", code.toLowerCase(Locale.ROOT).matches(".*(number|reference|identifier)$") || "taxIdentifier".equals(code));
        p.put("prefill", prerempli == null ? null : prerempli[0]);
        p.put("amendable", prerempli == null);
        String aide = Objects.toString(c == null ? null : c.aide(), "");
        if (calcule) aide = (aide + " Valeur calculée : règle de calcul à écrire (DRL).").strip();
        p.put("helpFr", aide.isBlank() ? (a == null || a.description().isBlank() ? null : tronquer(a.description(), 250)) : tronquer(aide, 250));
        p.put("sortOrder", ordreChamp);
        String justification = origine + (s != null ? " ; attribut " + s.modele().nom() + " › " + s.entite().nom() + "." + a.code()
                + (a.format() == null || a.format().isBlank() ? "" : " (" + a.format() + ")") + (liste == null ? "" : ", liste " + liste + (table != null && !liste.contains(table) ? " → " + table : ""))
                + (a.den() == null ? "" : ", DEN « " + a.den() + " »") : " ; aucun attribut du modèle de données ne correspond : type déduit de la maquette")
                + (prerempli != null ? " ; pré-rempli depuis le registre des opérateurs (" + prerempli[0] + ")." : ".");
        // Champ déjà présent dans la version (gabarit, import précédent) : mis à jour à sa place dans la maquette, en gardant
        // ce que la source n'apporte pas (pré-remplissage, liste, références, aide)
        Field ex = existants.get(code);
        String titre = "Champ « " + libelle + " » (" + type + (table != null ? " " + table : "") + ", section " + section + ")";
        if (ex != null) {
            if (p.get("prefill") == null) p.put("prefill", ex.prefill());
            if (p.get("refdataTable") == null && ex.refdataTable() != null) { p.put("refdataTable", ex.refdataTable()); p.put("dataType", ex.dataType()); }
            for (String k : List.of("untdedRef", "wcoRef", "cclDen", "cclUid")) if (p.get(k) == null) p.put(k, switch (k) {
                case "untdedRef" -> ex.untdedRef(); case "wcoRef" -> ex.wcoRef(); case "cclDen" -> ex.cclDen(); default -> ex.cclUid(); });
            if (p.get("helpFr") == null) p.put("helpFr", ex.helpFr());
            boolean identique = ex.sectionCode().equals(section) && ex.labelFr().equals(libelle) && ex.sortOrder() == ordreChamp
                    && Objects.equals(ex.mandatory(), p.get("mandatory")) && Objects.equals(ex.conditionText(), restriction) && Objects.equals(ex.dataType(), p.get("dataType"));
            if (identique) return;
            boolean deplace = !ex.sectionCode().equals(section);
            titre = "Champ « " + libelle + " » " + (deplace ? "déplacé de « " + ex.sectionCode() + " » vers « " + section + " »" : "mis à jour (section " + section + ")");
            justification = justification + " Champ existant : " + (deplace ? "placé dans le bloc de la maquette ; " : "")
                    + "ordre, libellé, obligation et condition alignés sur la maquette ; pré-remplissage, liste, références et chemin SCRDM conservés.";
        }
        props.add(new Proposition("FIELD", code, titre, p,
                justification, c != null ? c.libelle() + (c.format() == null ? "" : " [" + c.format() + "]") : a.code() + " — " + a.libelle(),
                "extraction", s != null ? 0.85 : 0.65));
    }

    /** Code du type de demande d'une variante : type existant de même libellé, sinon premier mot significatif. */
    private String codeType(String libelle) {
        String n = DocumentAgent.normaliser(libelle);
        for (RequestType t : v.requestTypes()) if (DocumentAgent.normaliser(t.labelFr()).equals(n)) return t.code();
        List<String> mots = new ArrayList<>();
        for (String m : n.split("\\s+")) if (m.length() > 2 && !Set.of("des", "les", "importation", "exportation").contains(m)) mots.add(m);
        String code = (mots.isEmpty() ? "STD" : mots.getFirst()).toUpperCase(Locale.ROOT);
        return code.length() > 10 ? code.substring(0, 10) : code;
    }

    private void typesDemande(List<String> variantes, String origine) {
        // Seul le type générique du gabarit (STD) existe : les variantes de l'écran le remplacent
        boolean generique = v.requestTypes().size() == 1 && "STD".equals(v.requestTypes().getFirst().code());
        if (!v.requestTypes().isEmpty() && !generique || props.stream().anyMatch(p -> p.kind().equals("REQUEST_TYPE"))) return;
        if (generique)
            constats.add("La version n'a que le type de demande générique STD (gabarit) : les variantes de l'écran sont proposées comme types de demande ; "
                    + "supprimez STD dans l'onglet Général si elles le remplacent.");
        String proc = v.procedure().code().replaceAll("[^A-Z]", "");
        String prefixe = proc.length() >= 2 ? proc.substring(0, 2) : (proc + "XX").substring(0, 2);
        Set<String> pris = new HashSet<>();
        int rang = 0;
        for (String libelle : variantes) {
            String code = codeType(libelle);
            if (!pris.add(code)) continue;
            String cur = prefixe + code.charAt(0);
            rang += 10;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("code", code);
            p.put("labelFr", libelle);
            p.put("labelEn", null);
            p.put("curCode", cur);
            p.put("natures", List.of("INITIAL", "AMENDMENT", "CANCELLATION"));
            p.put("description", null);
            p.put("sortOrder", rang);
            props.add(new Proposition("REQUEST_TYPE", code, "Type de demande « " + libelle + " » (CUR " + cur + ")", p,
                    origine + " ; les champs propres à une variante sont signalés dans leur aide. Code CUR à confirmer.", libelle, "extraction", 0.75));
        }
    }

    private static List<Map<String, Object>> valeursLocales(List<String> options) {
        List<Map<String, Object>> r = new ArrayList<>();
        Set<String> vus = new HashSet<>();
        for (String o : options) {
            String l = o.strip();
            if (l.isEmpty()) continue;
            String code = DocumentAgent.normaliser(l).replace(' ', '_').toUpperCase(Locale.ROOT);
            if (code.isEmpty() || !vus.add(code)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code.length() > 20 ? code.substring(0, 20) : code);
            m.put("labelFr", l);
            m.put("labelEn", null);
            r.add(m);
        }
        return r;
    }

    private static String codeLibre(String libelle) {
        StringBuilder s = new StringBuilder();
        for (String m : DocumentAgent.normaliser(libelle).split("\\s+")) {
            if (m.isBlank() || VIDES.contains(m)) continue;
            s.append(s.isEmpty() ? m : Character.toUpperCase(m.charAt(0)) + m.substring(1));
        }
        String c = s.isEmpty() ? "champ" : s.toString();
        return c.length() > 40 ? c.substring(0, 40) : c;
    }

    private static String tronquer(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
