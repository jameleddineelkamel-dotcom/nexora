package com.nexora.eservices.dossier;

import com.nexora.eservices.deploiement.ListesService;
import com.nexora.eservices.deploiement.Paquet;
import com.nexora.regles.Antecedent;
import com.nexora.regles.Demande;
import com.nexora.regles.MoteurRegles;
import com.nexora.regles.Resultat;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Year;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Validation d'une demande : contrôles génériques déduits du formulaire publié (obligation, longueur, format, listes,
 * groupes répétitifs, pièces obligatoires) puis règles DRL de la procédure (agenda-group « validation ») ; calcul des
 * frais par les règles (agenda-group « frais »).
 */
@Component
public class Validation {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern TELEPHONE = Pattern.compile("^\\+?[0-9 ().-]{6,35}$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern ANNEE = Pattern.compile("^\\d{4}$");
    private static final Pattern NOMBRE = Pattern.compile("^-?\\d+([.,]\\d+)?$");

    public record Erreur(String champ, String message, Integer ligne) {}

    private final ListesService listes;

    public Validation(ListesService listes) {
        this.listes = listes;
    }

    public record Bilan(List<Erreur> erreurs, List<Resultat.Frais> frais, java.math.BigDecimal total, List<String> regles) {
        public boolean valide() { return erreurs.isEmpty(); }
    }

    /**
     * Section des données saisies par l'organisme instructeur pendant le traitement (ex. référence et date de
     * domiciliation) : absente du formulaire du demandeur, ignorée au dépôt, renseignée dans la tâche de traitement.
     */
    public static final String INSTRUCTION = "INSTRUCTION";

    /** Contrôles génériques du formulaire. {@code lieux} : codes des lieux de traitement actifs (liste dynamique « @LIEUX_TRAITEMENT »). */
    public List<Erreur> formulaire(Paquet p, String type, String nature, Map<String, Object> donnees, Set<String> pieces, Set<String> lieux) {
        List<Erreur> e = new ArrayList<>();
        Map<String, Optional<Set<String>>> codes = new HashMap<>();
        java.util.function.Function<String, Optional<Set<String>>> liste = t -> codes.computeIfAbsent(t, listes::codes);
        Set<String> repetees = new HashSet<>();
        p.sections().stream().filter(Paquet.Section::repeating).forEach(s -> repetees.add(s.code()));
        for (Paquet.Field f : p.fields()) {
            if (repetees.contains(f.sectionCode()) || INSTRUCTION.equals(f.sectionCode()) || !applicable(f, type)) continue;
            controler(f, donnees.get(f.code()), e, null, lieux, liste);
        }
        for (Paquet.Section s : p.sections()) {
            if (!s.repeating() || INSTRUCTION.equals(s.code())) continue;
            List<?> lignes = donnees.get(s.code()) instanceof List<?> l ? l : List.of();
            if (lignes.size() < s.minRows())
                e.add(new Erreur(s.code(), "« " + s.labelFr() + " » : au moins " + s.minRows() + " ligne(s) attendue(s).", null));
            int n = 0;
            for (Object ligne : lignes) {
                n++;
                Map<?, ?> m = ligne instanceof Map<?, ?> x ? x : Map.of();
                for (Paquet.Field f : p.champsDe(s.code())) if (applicable(f, type)) controler(f, m.get(f.code()), e, n, lieux, liste);
            }
        }
        for (Paquet.RequiredDocument d : p.documents()) {
            if (!d.mandatory()) continue;
            if (d.requestTypes() != null && !d.requestTypes().isEmpty() && !d.requestTypes().contains(type)) continue;
            if (!pieces.contains(d.code())) e.add(new Erreur(d.code(), "Pièce obligatoire manquante : " + d.code() + " — " + d.labelFr() + ".", null));
        }
        return e;
    }

    private static final Pattern CONDITION_TYPE = Pattern.compile("^\\s*typeDemande\\s+in\\s*\\(([^)]*)\\)\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * Champ applicable au type de demande : condition « typeDemande in (A, B) » (champ propre à certaines variantes du
     * formulaire, ex. médicaments) ; sans condition de ce type, le champ s'applique à tous les types.
     */
    public static boolean applicable(Paquet.Field f, String type) {
        if (f.conditionText() == null || type == null) return true;
        java.util.regex.Matcher m = CONDITION_TYPE.matcher(f.conditionText());
        if (!m.matches()) return true;
        for (String t : m.group(1).split(",")) if (t.strip().replace("'", "").equalsIgnoreCase(type)) return true;
        return false;
    }

    /**
     * Données saisies par l'agent dans une tâche de traitement : format de chaque champ de la section INSTRUCTION
     * renseigné ; les champs déclarés par la tâche (propriété BPMN « fields ») sont obligatoires si {@code exiger}.
     */
    public List<Erreur> instruction(Paquet p, Map<String, Object> donnees, Collection<String> attendus, boolean exiger) {
        List<Erreur> e = new ArrayList<>();
        Map<String, Optional<Set<String>>> codes = new HashMap<>();
        java.util.function.Function<String, Optional<Set<String>>> liste = t -> codes.computeIfAbsent(t, listes::codes);
        for (Paquet.Field f : p.champsDe(INSTRUCTION)) {
            Object v = donnees.get(f.code());
            boolean vide = v == null || String.valueOf(v).isBlank();
            if (vide) {
                if (exiger && attendus.contains(f.code())) e.add(new Erreur(f.code(), "« " + f.labelFr() + " » est obligatoire.", null));
                continue;
            }
            controler(f, v, e, null, Set.of(), liste);
        }
        return e;
    }

    private static void controler(Paquet.Field f, Object valeur, List<Erreur> e, Integer ligne, Set<String> lieux,
                                  java.util.function.Function<String, Optional<Set<String>>> liste) {
        String v = valeur == null ? "" : String.valueOf(valeur).strip();
        String libelle = "« " + f.labelFr() + " »" + (ligne == null ? "" : " (ligne " + ligne + ")");
        if (v.isEmpty()) {
            if ("OUI".equals(f.mandatory())) e.add(new Erreur(f.code(), libelle + " est obligatoire.", ligne));
            return;
        }
        if (f.maxLength() != null && v.length() > f.maxLength() && !List.of("DECIMAL", "NUMBER").contains(f.dataType()))
            e.add(new Erreur(f.code(), libelle + " : " + f.maxLength() + " caractères au maximum.", ligne));
        switch (f.dataType()) {
            case "EMAIL" -> { if (!EMAIL.matcher(v).matches()) e.add(new Erreur(f.code(), libelle + " : adresse électronique invalide.", ligne)); }
            case "PHONE" -> { if (!TELEPHONE.matcher(v).matches()) e.add(new Erreur(f.code(), libelle + " : numéro de téléphone invalide.", ligne)); }
            case "DATE" -> { if (!DATE.matcher(v).matches()) e.add(new Erreur(f.code(), libelle + " : date attendue (AAAA-MM-JJ).", ligne)); }
            case "YEAR" -> { if (!ANNEE.matcher(v).matches()) e.add(new Erreur(f.code(), libelle + " : année sur 4 chiffres attendue.", ligne)); }
            case "NUMBER", "DECIMAL" -> { if (!NOMBRE.matcher(v.replace(" ", "")).matches()) e.add(new Erreur(f.code(), libelle + " : nombre attendu.", ligne)); }
            case "CODE" -> {
                if (f.codeValues() != null && !f.codeValues().isEmpty() && f.codeValues().stream().noneMatch(c -> c.code().equals(v)))
                    e.add(new Erreur(f.code(), libelle + " : valeur hors liste (" + v + ").", ligne));
                if ("@LIEUX_TRAITEMENT".equals(f.refdataTable()) && !lieux.contains(v))
                    e.add(new Erreur(f.code(), libelle + " : lieu de traitement inconnu ou inactif (" + v + ").", ligne));
                if (f.refdataTable() != null && f.refdataTable().startsWith("REF_")
                        && liste.apply(f.refdataTable()).map(c -> !c.contains(v)).orElse(false))
                    e.add(new Erreur(f.code(), libelle + " : code absent de la liste " + f.refdataTable() + " du Référentiel Commun (" + v + ").", ligne));
            }
            default -> { }
        }
        if (f.pattern() != null && !f.pattern().isBlank() && !Pattern.compile(f.pattern()).matcher(v).matches())
            e.add(new Erreur(f.code(), libelle + " : format invalide.", ligne));
    }

    /** Fait Drools construit à partir des données du dossier. */
    public static Demande demande(Paquet p, Dossier d, Set<String> pieces, boolean mandataire, boolean titulaire) {
        Demande dem = new Demande(p.procedure().code(), d.requestType(), d.requestNature(), Year.now().getValue());
        Set<String> repetees = new HashSet<>();
        p.sections().stream().filter(Paquet.Section::repeating).forEach(s -> repetees.add(s.code()));
        Map<String, Object> champs = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> groupes = new LinkedHashMap<>();
        d.formData().forEach((k, v) -> {
            if (repetees.contains(k) && v instanceof List<?> l) {
                List<Map<String, Object>> lignes = new ArrayList<>();
                for (Object o : l) if (o instanceof Map<?, ?> m) {
                    Map<String, Object> ligne = new LinkedHashMap<>();
                    m.forEach((a, b) -> ligne.put(String.valueOf(a), b));
                    lignes.add(ligne);
                }
                groupes.put(k, lignes);
            } else champs.put(k, v);
        });
        champs.put("requestTypeCode", d.requestType());
        dem.setChamps(champs);
        dem.setGroupes(groupes);
        dem.setPieces(new LinkedHashSet<>(pieces));
        dem.setDemandeur(d.submittedBy());
        dem.setMandataire(mandataire);
        dem.setTitulaire(titulaire);
        return dem;
    }

    /** Antécédents du demandeur (renouvellements, amendements, doublons). */
    public static List<Antecedent> antecedents(List<Dossier> dossiers, Map<String, java.time.LocalDate> expirations, String champAnnee) {
        LocalDate aujourdhui = LocalDate.now();
        List<Antecedent> l = new ArrayList<>();
        for (Dossier x : dossiers) {
            Object a = champAnnee == null ? null : x.formData().get(champAnnee);
            int annee = a != null && String.valueOf(a).matches("\\d{4}") ? Integer.parseInt(String.valueOf(a))
                    : x.submittedAt() == null ? Year.now().getValue() : x.submittedAt().getYear();
            LocalDate exp = expirations.get(x.dossierNo());
            boolean valide = "VALIDE".equals(x.status()) && exp != null && !exp.isBefore(aujourdhui);
            l.add(new Antecedent(x.dossierNo(), x.requestType(), x.requestNature(), x.status(), annee, exp, valide, "ACTIVE".equals(x.lifecycle())));
        }
        return l;
    }

    public Bilan evaluer(Paquet p, long deploiement, Demande demande, List<Antecedent> antecedents, List<Erreur> generiques) {
        String cle = "deploiement:" + deploiement;
        Resultat validation = MoteurRegles.evaluer(cle, p.rules().drl(), "validation", demande, antecedents);
        Resultat frais = MoteurRegles.evaluer(cle, p.rules().drl(), "frais", demande, antecedents);
        List<Erreur> erreurs = new ArrayList<>(generiques);
        Set<String> vus = new HashSet<>();
        for (Resultat.Erreur x : validation.getErreurs())
            if (vus.add(x.champ() + "|" + x.message())) erreurs.add(new Erreur(x.champ(), x.message(), null));
        List<String> regles = new ArrayList<>(validation.getReglesDeclenchees());
        regles.addAll(frais.getReglesDeclenchees());
        return new Bilan(erreurs, frais.getFrais(), frais.total(), regles);
    }
}
