package com.nexora.procedures.agents;

import com.nexora.procedures.agents.DrawioSource.*;
import com.nexora.procedures.studio.Modele.*;

import java.util.*;

/**
 * Conversion d'un diagramme BPMN dessiné dans draw.io (souvent la source des figures d'une SFD) en workflow BPMN 2.0
 * exécutable par le Runtime, en conservant le tracé de l'auteur : couloirs (acteurs), tâches, passerelles, événements,
 * libellés et positions. Chaque élément est relié au contrat d'exécution du guichet unique :
 * <ul>
 *   <li>couloir du demandeur : la tâche initiale est réalisée dans eFORCE avant le dépôt (tâche de passage) ; les
 *       autres tâches deviennent des tâches humaines du demandeur (complément) ;</li>
 *   <li>couloir de l'organisme qui décide (celui dont une tâche précède une décision) : tâches humaines de l'agent coté,
 *       précédées d'une cotation automatique ; les libellés des sorties de la décision (accord, rejet, complément)
 *       deviennent les décisions VALIDER, REJETER, COMPLEMENT ;</li>
 *   <li>autres couloirs (douane, SGS, trésor…) : tâches de passage, signalées comme intégrations à prévoir ;</li>
 *   <li>document de type attestation, certificat, autorisation : délivrance du livrable ; autres documents :
 *       événements intermédiaires ;</li>
 *   <li>événements de fin : statut final du dossier déduit du chemin (rejet) ou du libellé.</li>
 * </ul>
 * Tout ce qui ne peut pas être exécuté automatiquement est listé dans les alertes, pour la revue humaine.
 */
public final class BpmnImport {

    /** Livrable délivré par le workflow : code, libellé et code document (numérotation CUR + code document). */
    public record Livrable(String code, String libelle, String docCode) {}

    /** Statut propre au diagramme (libellé d'un événement de fin ou document délivré en cours de processus). */
    public record Statut(String code, String libelle, boolean fin, String ton) {}

    public record Resultat(String xml, List<String> etapes, List<String> alertes, Set<String> statuts, List<Livrable> livrables, List<Statut> statutsPropres,
                           Map<String, String> roles) {}

    private enum Role { DEMANDEUR, TRAITEMENT, EXTERNE }

    private record Forme(String id, String type, String nom, double x, double y, double l, double h, String couloir, String corps) {
        double cx() { return x + l / 2; }
        double cy() { return y + h / 2; }
    }

    private record Flux(String id, String source, String cible, String nom, String condition) {}

    private final DrawioSource d;
    private final VersionComplete v;
    private final Map<String, Noeud> noeuds = new LinkedHashMap<>();
    private final Map<String, List<DrawioSource.Arc>> sortants = new HashMap<>(), entrants = new HashMap<>();
    private final Map<String, Role> roles = new HashMap<>();
    private final Map<String, Forme> formes = new LinkedHashMap<>();
    private final List<Flux> flux = new ArrayList<>();
    private final List<String> etapes = new ArrayList<>(), alertes = new ArrayList<>();
    private final Set<String> statuts = new LinkedHashSet<>();
    private final List<Livrable> livrables = new ArrayList<>();
    private final Map<String, Statut> statutsPropres = new LinkedHashMap<>();

    private BpmnImport(DrawioSource d, VersionComplete v) {
        this.d = d;
        this.v = v;
        d.noeuds().forEach(n -> noeuds.put(n.id(), n));
        for (DrawioSource.Arc a : d.arcs()) {
            sortants.computeIfAbsent(a.source(), k -> new ArrayList<>()).add(a);
            entrants.computeIfAbsent(a.cible(), k -> new ArrayList<>()).add(a);
        }
    }

    public static Resultat convertir(DrawioSource d, VersionComplete v, String cle) {
        return new BpmnImport(d, v).construire(cle);
    }

    // ------------------------------------------------------------------ décisions et rôles
    /** Décision standard du Runtime portée par le libellé d'une sortie de passerelle, ou null. */
    static String decision(String libelle) {
        String n = DocumentAgent.normaliser(libelle);
        if (n.isBlank()) return null;
        if (n.startsWith("oui") || n.startsWith("non ") || n.equals("non")) return null;   // réponse à une question : décision propre
        if (n.contains("rejet") || n.contains("refus") || n.contains("defavorable")) return "REJETER";
        if (n.contains("complement") || n.contains("information") || n.contains("correction") || n.contains("incomplet")) return "COMPLEMENT";
        if (n.contains("accord") || n.contains("valid") || n.contains("approuv") || n.contains("sign") || n.contains("favorable")
                || n.contains("conforme") || n.contains("accept")) return "VALIDER";
        return null;
    }

    /** Code de décision d'une sortie : décision standard, sinon le premier mot du libellé (ex. « oui — apuré » → OUI). */
    static String codeDecision(String libelle) {
        String d = decision(libelle);
        if (d != null) return d;
        String n = DocumentAgent.normaliser(libelle);
        String mot = n.isBlank() ? "AUTRE" : n.split("\\s+")[0];
        return mot.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "");
    }

    /** Passerelle dont au moins deux sorties portent des décisions standard (accord, rejet, complément). */
    private boolean estDecisionStandard(Noeud g) {
        if (g == null || g.type() != DrawioSource.Type.PASSERELLE) return false;
        Set<String> d = new HashSet<>();
        for (DrawioSource.Arc a : sortants.getOrDefault(g.id(), List.of())) {
            String x = decision(a.libelle());
            if (x != null) d.add(x);
        }
        return d.size() >= 2;
    }

    /**
     * Passerelle de décision : décisions standard, ou toutes les sorties libellées derrière une tâche humaine (demandeur
     * ou organisme instructeur), ex. « Apuré ? » oui / non. Sinon, plusieurs sorties sans décision = embranchement parallèle.
     */
    private boolean estDecision(Noeud g) {
        if (estDecisionStandard(g)) return true;
        if (g == null || g.type() != DrawioSource.Type.PASSERELLE) return false;
        List<DrawioSource.Arc> s = sortants.getOrDefault(g.id(), List.of());
        if (s.size() < 2 || s.stream().anyMatch(a -> a.libelle().isBlank())) return false;
        return entrants.getOrDefault(g.id(), List.of()).stream().map(a -> noeuds.get(a.source()))
                .anyMatch(t -> t.type() == DrawioSource.Type.TACHE && role(t) != Role.EXTERNE);
    }

    private static boolean estDemandeur(String libelle) {
        String n = DocumentAgent.normaliser(libelle);
        for (String m : List.of("importateur", "exportateur", "operateur", "demandeur", "cad", "commissionnaire", "declarant", "usager", "requerant"))
            if ((" " + n + " ").contains(" " + m + " ") || n.startsWith(m)) return true;
        return false;
    }

    private void attribuerRoles() {
        String traitement = null;
        // Couloir de l'organisme qui décide : celui de la tâche qui précède une décision
        for (Noeud g : d.noeuds())
            if (estDecisionStandard(g))
                for (DrawioSource.Arc a : entrants.getOrDefault(g.id(), List.of())) {
                    Noeud t = noeuds.get(a.source());
                    if (t != null && t.type() == DrawioSource.Type.TACHE && t.couloir() != null && traitement == null) traitement = t.couloir();
                }
        for (Couloir c : d.couloirs()) {
            Role r = estDemandeur(c.libelle()) ? Role.DEMANDEUR : c.id().equals(traitement) ? Role.TRAITEMENT : Role.EXTERNE;
            roles.put(c.id(), r);
        }
        if (traitement == null) {
            // Sans décision explicite : le premier couloir non demandeur qui porte une tâche
            d.noeuds().stream().filter(n -> n.type() == DrawioSource.Type.TACHE && n.couloir() != null && roles.get(n.couloir()) == Role.EXTERNE)
                    .findFirst().ifPresent(n -> roles.put(n.couloir(), Role.TRAITEMENT));
            alertes.add("Aucune décision (accord / rejet / complément) n'est lisible sur les sorties des passerelles : les décisions de l'agent sont limitées à VALIDER.");
        }
    }

    private Role role(Noeud n) {
        return n.couloir() == null ? Role.EXTERNE : roles.getOrDefault(n.couloir(), Role.EXTERNE);
    }

    private String libelleCouloir(Noeud n) {
        return n.couloir() == null ? "hors couloir" : d.couloir(n.couloir()).map(Couloir::libelle).orElse("?");
    }

    // ------------------------------------------------------------------ construction
    private Resultat construire(String cle) {
        attribuerRoles();
        String premierTraitement = null;
        for (Noeud n : d.noeuds()) {
            String id = ident(n);
            switch (n.type()) {
                case DEBUT -> {
                    ajouter(id, "startEvent", n.libelle().isBlank() ? "Demande soumise" : n.libelle(), n, "");
                    etapes.add("début (dépôt eFORCE)");
                }
                case TACHE -> tache(n, id);
                case PASSERELLE -> {
                    boolean decision = estDecision(n);
                    int sorties = sortants.getOrDefault(n.id(), List.of()).size();
                    String type = decision || sorties <= 1 ? "exclusiveGateway" : "parallelGateway";
                    ajouter(id, type, n.libelle().isBlank() ? null : n.libelle(), n, "");
                    if (type.equals("parallelGateway")) etapes.add("embranchement parallèle (" + sorties + " branches)");
                }
                case DOCUMENT -> document(n, id);
                case INTERMEDIAIRE -> {
                    ajouter(id, "intermediateThrowEvent", n.libelle(), n, "");
                    etapes.add("événement « " + n.libelle() + " »");
                    if (!n.libelle().isBlank())
                        alertes.add("Événement « " + n.libelle() + " » : franchi sans attente. S'il faut attendre une information extérieure (paiement, "
                                + "message d'un partenaire), un message entrant corrélé est à prévoir.");
                }
                case FIN -> fin(n, id);
            }
            if (n.type() == DrawioSource.Type.TACHE && role(n) == Role.TRAITEMENT && premierTraitement == null) premierTraitement = n.id();
        }
        // Flux du diagramme ; les décisions deviennent des conditions
        for (DrawioSource.Arc a : d.arcs()) {
            Noeud s = noeuds.get(a.source());
            String condition = null;
            if (estDecision(s)) condition = "${decision == '" + codeDecision(a.libelle()) + "'}";
            flux.add(new Flux("Flow_" + ident(a.id()), ident(s), ident(noeuds.get(a.cible())), a.libelle().isBlank() ? null : a.libelle(), condition));
        }
        if (premierTraitement != null) insererCotation(noeuds.get(premierTraitement));
        // Documents et tâches sans suite : fin explicite (fin d'une branche, sans changer le statut)
        for (Noeud n : d.noeuds())
            if (n.type() != DrawioSource.Type.FIN && sortants.getOrDefault(n.id(), List.of()).isEmpty()) {
                Forme f = formes.get(ident(n));
                String fin = ident(n) + "_Fin";
                ajouterForme(new Forme(fin, "endEvent", "Fin de branche", f.x() + f.l() + 40, f.cy() - 18, 36, 36, n.couloir(), ""));
                flux.add(new Flux("Flow_" + fin, ident(n), fin, null, null));
            }
        return new Resultat(document(cle), etapes, alertes, statuts, livrables, List.copyOf(statutsPropres.values()), rolesLisibles());
    }

    private void tache(Noeud n, String id) {
        Role r = role(n);
        boolean initiale = entrants.getOrDefault(n.id(), List.of()).stream().allMatch(a -> noeuds.get(a.source()).type() == DrawioSource.Type.DEBUT);
        if (r == Role.DEMANDEUR && initiale) {
            ajouter(id, "task", n.libelle(), n, documentation("Réalisée dans eFORCE avant le dépôt : le workflow démarre à la soumission de la demande."));
            etapes.add("« " + n.libelle() + " » (eFORCE, avant dépôt)");
        } else if (r == Role.DEMANDEUR) {
            ajouter(id, "userTask", n.libelle(), n, utilisateur("${demandeur}", "complement", "SOUMETTRE", "COMPLEMENT_DEMANDE", List.of()));
            statuts.add("COMPLEMENT_DEMANDE");
            etapes.add("« " + n.libelle() + " » (demandeur)");
        } else if (r == Role.TRAITEMENT) {
            Set<String> decisions = new LinkedHashSet<>();
            for (DrawioSource.Arc a : sortants.getOrDefault(n.id(), List.of())) {
                Noeud g = noeuds.get(a.cible());
                if (estDecision(g)) {
                    List<String> codes = sortants.get(g.id()).stream().map(s -> codeDecision(s.libelle())).toList();
                    for (String x : List.of("COMPLEMENT", "VALIDER", "REJETER")) if (codes.contains(x)) decisions.add(x);
                    decisions.addAll(codes);
                }
            }
            if (decisions.isEmpty()) decisions.add("VALIDER");
            List<String> saisies = saisiesDe(n);
            ajouter(id, "userTask", n.libelle(), n, utilisateur("${agentTraitement}", "traitement", String.join(",", decisions), "EN_ATTENTE_TRAITEMENT", saisies));
            if (!saisies.isEmpty()) etapes.add("saisie par l'agent : " + String.join(", ", saisies));
            etapes.add("« " + n.libelle() + " » (" + libelleCouloir(n) + " : " + String.join(" / ", decisions) + ")");
        } else {
            ajouter(id, "task", n.libelle(), n, documentation("Traitement par « " + libelleCouloir(n) + " » : système partenaire, intégration à prévoir."));
            etapes.add("« " + n.libelle() + " » (" + libelleCouloir(n) + ", passage)");
            alertes.add("« " + n.libelle() + " » (couloir « " + libelleCouloir(n) + " ») : traitement d'un partenaire sans intégration automatique ; "
                    + "la tâche est franchie sans attente. Un échange (message entrant) est à prévoir pour en attendre le résultat.");
        }
    }

    /**
     * Données d'instruction (section INSTRUCTION de la version) saisies dans cette tâche : chaque champ va à la tâche de
     * l'organisme instructeur qui partage le plus de mots avec son libellé (à égalité, la plus tardive dans le diagramme).
     */
    private List<String> saisiesDe(Noeud tache) {
        List<Noeud> taches = d.noeuds().stream().filter(x -> x.type() == DrawioSource.Type.TACHE && role(x) == Role.TRAITEMENT).toList();
        List<String> r = new ArrayList<>();
        for (Field f : v.fields()) {
            if (!"INSTRUCTION".equals(f.sectionCode())) continue;
            Set<String> mots = mots(f.labelFr());
            Noeud meilleur = null;
            int score = 0;
            for (Noeud t : taches) {
                Set<String> commun = new HashSet<>(mots(t.libelle()));
                commun.retainAll(mots);
                if (commun.size() > score || (commun.size() == score && score > 0 && meilleur != null && t.cx() > meilleur.cx())) {
                    score = commun.size();
                    meilleur = t;
                }
            }
            if (meilleur != null && meilleur.id().equals(tache.id())) r.add(f.code());
        }
        return r;
    }

    /** Racines des mots significatifs (5 premières lettres : transfert / transferts, domiciliation / domicilié). */
    private static Set<String> mots(String libelle) {
        Set<String> r = new HashSet<>();
        for (String m : DocumentAgent.normaliser(libelle).split("\\s+"))
            if (m.length() > 3 && !Set.of("dans", "pour", "avec", "demande", "dossier", "donnees").contains(m)) r.add(m.length() > 5 ? m.substring(0, 5) : m);
        return r;
    }

    private static final List<String> LIVRABLES =List.of("attestation", "certificat", "autorisation", "licence", "permis", "titre", "agrement", "livrable");

    private static boolean estLivrable(Noeud n) {
        String l = DocumentAgent.normaliser(n.libelle());
        return n.type() == DrawioSource.Type.DOCUMENT && LIVRABLES.stream().anyMatch(l::contains);
    }

    /** Document délivré : livrable du paquet (un par document) ; statut = celui de la fin qui suit, sinon statut propre non final. */
    private void document(Noeud n, String id) {
        if (!estLivrable(n)) {
            ajouter(id, "intermediateThrowEvent", n.libelle(), n, documentation("Document produit : " + n.libelle() + "."));
            etapes.add("document « " + n.libelle() + " »");
            return;
        }
        Livrable l = livrable(n.libelle());
        String statut = sortants.getOrDefault(n.id(), List.of()).stream().map(a -> noeuds.get(a.cible()))
                .filter(c -> c.type() == DrawioSource.Type.FIN).map(this::statutFin).filter(Objects::nonNull).findFirst()
                .orElseGet(() -> {
                    // Délivré en cours de processus (la suite continue ailleurs) : statut non final
                    String code = ("LIVRE_" + l.docCode());
                    statutsPropres.putIfAbsent(code, new Statut(code, n.libelle() + " délivrée", false, "ok"));
                    return code;
                });
        statuts.add(statut);
        Map<String, String> champs = new LinkedHashMap<>();
        champs.put("livrable", l.code());
        champs.put("statut", statut);
        ajouter(id, "serviceTask", n.libelle(), n, service("delivrance", champs, null));
        etapes.add("délivrance « " + n.libelle() + " » → " + statut);
    }

    /** Livrable d'un document : celui de la version qui porte ce libellé, sinon un nouveau livrable à proposer. */
    private Livrable livrable(String libelle) {
        String n = DocumentAgent.normaliser(libelle);
        for (Deliverable d : v.deliverables())
            if (DocumentAgent.normaliser(d.labelFr()).equals(n) || (v.deliverables().size() == 1 && livrables.isEmpty()))
                return ajouterLivrable(new Livrable(d.code(), d.labelFr(), d.docCode()));
        List<String> mots = new ArrayList<>();
        for (String m : n.split("\\s+")) if (m.length() > 2 && !Set.of("des", "les", "une", "pour", "par").contains(m)) mots.add(m);
        String code = String.join("_", mots).toUpperCase(Locale.ROOT);
        if (code.length() > 30) code = code.substring(0, 30).replaceAll("_+$", "");
        StringBuilder doc = new StringBuilder();
        mots.forEach(m -> doc.append(Character.toUpperCase(m.charAt(0))));
        String dernier = mots.isEmpty() ? "DOC" : mots.getLast().toUpperCase(Locale.ROOT);
        for (int i = 1; doc.length() < 3 && i < dernier.length(); i++) doc.append(dernier.charAt(i));
        String docCode = doc.length() > 3 ? doc.substring(0, 3) : doc.toString();
        Set<String> pris = new HashSet<>();
        livrables.forEach(x -> pris.add(x.docCode()));
        v.deliverables().forEach(x -> pris.add(x.docCode()));
        for (int i = 2; pris.contains(docCode); i++) docCode = docCode.substring(0, 2) + i;
        return ajouterLivrable(new Livrable(code.isEmpty() ? "LIVRABLE" : code, libelle, docCode));
    }

    private Livrable ajouterLivrable(Livrable l) {
        if (livrables.stream().noneMatch(x -> x.code().equals(l.code()))) livrables.add(l);
        return l;
    }

    /** Statut posé par un événement de fin : rejet, annulation, validation, ou statut propre tiré du libellé. */
    private String statutFin(Noeud n) {
        String l = DocumentAgent.normaliser(n.libelle());
        if (l.contains("rejet") || l.contains("refus") || cheminDepuis(n, "REJETER")) return "REJETE";
        if (l.contains("annul")) return "ANNULE";
        if (l.contains("valid") || l.contains("domicili") || l.contains("deliv") || l.contains("accord") || l.contains("approuv") || l.contains("emis")
                || l.contains("termin") || cheminDepuis(n, "VALIDER")) return "VALIDE";
        if (l.isBlank() || l.startsWith("fin")) return null;
        String code = l.replace(' ', '_').toUpperCase(Locale.ROOT);
        if (code.length() > 30) code = code.substring(0, 30).replaceAll("_+$", "");
        statutsPropres.putIfAbsent(code, new Statut(code, n.libelle(), true, l.contains("non ") || l.startsWith("non") ? "warn" : "ok"));
        return code;
    }

    private void fin(Noeud n, String id) {
        String statut = statutFin(n);
        // Après la délivrance d'un document, le statut est posé par la délivrance elle-même
        boolean apresDelivrance = entrants.getOrDefault(n.id(), List.of()).stream().anyMatch(a -> estLivrable(noeuds.get(a.source())));
        if (statut != null) statuts.add(statut);
        ajouter(id, "endEvent", n.libelle(), n, statut == null || apresDelivrance ? "" : ecouteurStatut(statut));
        etapes.add("fin « " + n.libelle() + " »" + (statut == null ? "" : " → " + statut));
    }

    /** Le nœud est atteint directement par la sortie d'une décision portant ce choix. */
    private boolean cheminDepuis(Noeud n, String choix) {
        for (DrawioSource.Arc a : entrants.getOrDefault(n.id(), List.of())) {
            Noeud s = noeuds.get(a.source());
            if (estDecision(s) && choix.equals(decision(a.libelle()))) return true;
        }
        return false;
    }

    /** Cotation automatique avant la première tâche de l'agent (sauf retours de complément et boucles internes). */
    private void insererCotation(Noeud t) {
        Forme cible = formes.get(ident(t));
        String id = "Task_Cotation";
        ajouterForme(new Forme(id, "serviceTask", "Coter le dossier", cible.x() - 140, cible.cy() - 30, 110, 60, t.couloir(),
                service("cotation", Map.of(), "A_COTER")));
        statuts.add("A_COTER");
        statuts.add("EN_ATTENTE_TRAITEMENT");
        List<Flux> nouveaux = new ArrayList<>();
        boolean redirige = false;
        for (Flux f : flux) {
            Forme s = formes.get(f.source());
            boolean retour = s != null && (s.corps().contains("nexora:complement") || (s.couloir() != null && s.couloir().equals(t.couloir())));
            if (f.cible().equals(ident(t)) && !retour) {
                nouveaux.add(new Flux(f.id(), f.source(), id, f.nom(), f.condition()));
                redirige = true;
            } else nouveaux.add(f);
        }
        if (!redirige) alertes.add("Cotation insérée avant « " + t.libelle() + " » sans flux entrant identifié : vérifiez l'enchaînement.");
        nouveaux.add(new Flux("Flow_Cotation", id, ident(t), null, null));
        flux.clear();
        flux.addAll(nouveaux);
        int rang = 0;
        for (int i = 0; i < etapes.size(); i++) if (etapes.get(i).contains(t.libelle())) { rang = i; break; }
        etapes.add(rang, "cotation automatique");
    }

    // ------------------------------------------------------------------ fragments XML
    private static String utilisateur(String assigne, String formulaire, String decisions, String statut, List<String> saisies) {
        return "@assignee=" + assigne + "@form=nexora:" + formulaire + "\n      <bpmn:extensionElements>\n"
                + "        <camunda:properties>\n          <camunda:property name=\"decisions\" value=\"" + decisions + "\" />\n"
                + (saisies.isEmpty() ? "" : "          <camunda:property name=\"fields\" value=\"" + String.join(",", saisies) + "\" />\n")
                + "        </camunda:properties>\n"
                + ecouteur(statut) + "      </bpmn:extensionElements>\n";
    }

    private static String service(String delegue, Map<String, String> champs, String statut) {
        StringBuilder s = new StringBuilder("@delegate=" + delegue + (delegue.equals("cotation") ? "@async" : "") + "\n");
        if (!champs.isEmpty() || statut != null) {
            s.append("      <bpmn:extensionElements>\n");
            champs.forEach((k, val) -> s.append("        <camunda:field name=\"").append(k).append("\" stringValue=\"").append(xml(val)).append("\" />\n"));
            if (statut != null) s.append(ecouteur(statut));
            s.append("      </bpmn:extensionElements>\n");
        }
        return s.toString();
    }

    private static String ecouteur(String statut) {
        return "        <camunda:executionListener delegateExpression=\"${statut}\" event=\"start\">\n"
                + "          <camunda:field name=\"code\" stringValue=\"" + statut + "\" />\n        </camunda:executionListener>\n";
    }

    private static String ecouteurStatut(String statut) {
        return "\n      <bpmn:extensionElements>\n" + ecouteur(statut) + "      </bpmn:extensionElements>\n";
    }

    private static String documentation(String texte) {
        return "\n      <bpmn:documentation>" + xml(texte) + "</bpmn:documentation>\n";
    }

    private void ajouter(String id, String type, String nom, Noeud n, String corps) {
        double l = n.l(), h = n.h();
        if (type.endsWith("Event")) { l = 36; h = 36; }
        else if (type.endsWith("Gateway")) { l = 50; h = 50; }
        else { l = Math.max(100, l); h = Math.max(60, h); }
        ajouterForme(new Forme(id, type, nom, n.cx() - l / 2, n.cy() - h / 2, l, h, n.couloir(), corps));
    }

    private void ajouterForme(Forme f) {
        formes.put(f.id(), f);
    }

    private String document(String cle) {
        Map<String, List<String>> in = new HashMap<>(), out = new HashMap<>();
        for (Flux f : flux) {
            out.computeIfAbsent(f.source(), k -> new ArrayList<>()).add(f.id());
            in.computeIfAbsent(f.cible(), k -> new ArrayList<>()).add(f.id());
        }
        // Passerelle de décision : une sortie sans condition devient la sortie par défaut
        Map<String, String> parDefaut = new HashMap<>();
        for (Forme g : formes.values())
            if (g.type().equals("exclusiveGateway") && out.getOrDefault(g.id(), List.of()).size() > 1)
                flux.stream().filter(f -> f.source().equals(g.id()) && f.condition() == null).findFirst().ifPresent(f -> parDefaut.put(g.id(), f.id()));

        StringBuilder p = new StringBuilder();
        for (Forme f : formes.values()) {
            String corps = f.corps();
            String attributs = "";
            if (corps.startsWith("@assignee=")) {
                String[] t = corps.substring(0, corps.indexOf('\n')).split("@");
                attributs = " camunda:assignee=\"" + t[1].substring("assignee=".length()) + "\" camunda:formKey=\"" + t[2].substring("form=".length()) + "\"";
                corps = corps.substring(corps.indexOf('\n'));
            } else if (corps.startsWith("@delegate=")) {
                String entete = corps.substring(0, corps.indexOf('\n'));
                attributs = (entete.contains("@async") ? " camunda:asyncBefore=\"true\"" : "")
                        + " camunda:delegateExpression=\"${" + entete.substring("@delegate=".length()).replace("@async", "") + "}\"";
                corps = corps.substring(corps.indexOf('\n'));
            }
            String type = f.type();
            p.append("    <bpmn:").append(type).append(" id=\"").append(f.id()).append("\"")
                    .append(f.nom() == null ? "" : " name=\"" + xml(f.nom()) + "\"").append(attributs)
                    .append(parDefaut.containsKey(f.id()) ? " default=\"" + parDefaut.get(f.id()) + "\"" : "").append(">\n");
            if (!corps.isBlank()) p.append(corps.startsWith("\n") ? corps.substring(1) : corps);
            in.getOrDefault(f.id(), List.of()).forEach(a -> p.append("      <bpmn:incoming>").append(a).append("</bpmn:incoming>\n"));
            out.getOrDefault(f.id(), List.of()).forEach(a -> p.append("      <bpmn:outgoing>").append(a).append("</bpmn:outgoing>\n"));
            p.append("    </bpmn:").append(type).append(">\n");
        }
        for (Flux f : flux) {
            p.append("    <bpmn:sequenceFlow id=\"").append(f.id()).append("\" sourceRef=\"").append(f.source()).append("\" targetRef=\"").append(f.cible())
                    .append("\"").append(f.nom() == null ? "" : " name=\"" + xml(f.nom()) + "\"");
            if (f.condition() == null) p.append(" />\n");
            else p.append(">\n      <bpmn:conditionExpression xsi:type=\"bpmn:tFormalExpression\">").append(xml(f.condition()))
                    .append("</bpmn:conditionExpression>\n    </bpmn:sequenceFlow>\n");
        }

        boolean couloirs = !d.couloirs().isEmpty();
        double minX = formes.values().stream().mapToDouble(Forme::x).min().orElse(0) - 60;
        double maxX = formes.values().stream().mapToDouble(f -> f.x() + f.l()).max().orElse(800) + 40;
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"")
                .append(" xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\" xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\"")
                .append(" xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\" xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\"")
                .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" id=\"Definitions_").append(cle)
                .append("\" targetNamespace=\"https://nexora.trade/bpmn\" exporter=\"NEXORA Studio — import draw.io\" exporterVersion=\"1.0\">\n");
        if (couloirs)
            x.append("  <bpmn:collaboration id=\"Collaboration_").append(cle).append("\">\n    <bpmn:participant id=\"Participant_").append(cle)
                    .append("\" name=\"").append(xml(d.titre())).append("\" processRef=\"").append(cle).append("\" />\n  </bpmn:collaboration>\n");
        x.append("  <bpmn:process id=\"").append(cle).append("\" name=\"").append(xml(v.procedure().labelFr()))
                .append("\" isExecutable=\"true\" camunda:historyTimeToLive=\"P3650D\">\n");
        if (couloirs) {
            x.append("    <bpmn:laneSet id=\"LaneSet_").append(cle).append("\">\n");
            for (Couloir c : d.couloirs()) {
                x.append("      <bpmn:lane id=\"Lane_").append(ident(c.id())).append("\" name=\"").append(xml(c.libelle())).append("\">\n");
                formes.values().stream().filter(f -> c.id().equals(f.couloir()))
                        .forEach(f -> x.append("        <bpmn:flowNodeRef>").append(f.id()).append("</bpmn:flowNodeRef>\n"));
                x.append("      </bpmn:lane>\n");
            }
            x.append("    </bpmn:laneSet>\n");
        }
        x.append(p).append("  </bpmn:process>\n");

        // Diagramme : positions de l'auteur
        String plan = couloirs ? "Collaboration_" + cle : cle;
        x.append("  <bpmndi:BPMNDiagram id=\"Diagram_").append(cle).append("\">\n    <bpmndi:BPMNPlane id=\"Plane_").append(cle)
                .append("\" bpmnElement=\"").append(plan).append("\">\n");
        if (couloirs) {
            double y0 = d.couloirs().getFirst().y(), y1 = d.couloirs().getLast().y() + d.couloirs().getLast().h();
            forme(x, "Participant_" + cle, minX - 30, y0, maxX - minX + 30, y1 - y0, true);
            for (Couloir c : d.couloirs()) forme(x, "Lane_" + ident(c.id()), minX, c.y(), maxX - minX, c.h(), true);
        }
        for (Forme f : formes.values()) {
            x.append("      <bpmndi:BPMNShape id=\"").append(f.id()).append("_di\" bpmnElement=\"").append(f.id()).append("\"")
                    .append(f.type().equals("exclusiveGateway") ? " isMarkerVisible=\"true\"" : "").append(">\n        <dc:Bounds x=\"")
                    .append(n(f.x())).append("\" y=\"").append(n(f.y())).append("\" width=\"").append(n(f.l())).append("\" height=\"").append(n(f.h()))
                    .append("\" />\n      </bpmndi:BPMNShape>\n");
        }
        for (Flux f : flux) {
            Forme s = formes.get(f.source()), t = formes.get(f.cible());
            x.append("      <bpmndi:BPMNEdge id=\"").append(f.id()).append("_di\" bpmnElement=\"").append(f.id()).append("\">\n");
            for (double[] pt : trace(s, t)) x.append("        <di:waypoint x=\"").append(n(pt[0])).append("\" y=\"").append(n(pt[1])).append("\" />\n");
            x.append("      </bpmndi:BPMNEdge>\n");
        }
        x.append("    </bpmndi:BPMNPlane>\n  </bpmndi:BPMNDiagram>\n</bpmn:definitions>\n");
        return x.toString();
    }

    private static void forme(StringBuilder x, String id, double px, double py, double l, double h, boolean horizontal) {
        x.append("      <bpmndi:BPMNShape id=\"").append(id).append("_di\" bpmnElement=\"").append(id).append("\"")
                .append(horizontal ? " isHorizontal=\"true\"" : "").append(">\n        <dc:Bounds x=\"").append(n(px)).append("\" y=\"").append(n(py))
                .append("\" width=\"").append(n(l)).append("\" height=\"").append(n(h)).append("\" />\n      </bpmndi:BPMNShape>\n");
    }

    /** Tracé orthogonal simple entre deux formes (ajustable ensuite dans l'éditeur). */
    private static List<double[]> trace(Forme s, Forme t) {
        List<double[]> p = new ArrayList<>();
        if (Math.abs(s.cy() - t.cy()) < 8) {
            boolean droite = s.cx() < t.cx();
            p.add(new double[]{droite ? s.x() + s.l() : s.x(), s.cy()});
            p.add(new double[]{droite ? t.x() : t.x() + t.l(), s.cy()});
        } else if (Math.abs(s.cx() - t.cx()) < 8) {
            boolean bas = s.cy() < t.cy();
            p.add(new double[]{s.cx(), bas ? s.y() + s.h() : s.y()});
            p.add(new double[]{s.cx(), bas ? t.y() : t.y() + t.h()});
        } else {
            boolean bas = s.cy() < t.cy();
            p.add(new double[]{s.cx(), bas ? s.y() + s.h() : s.y()});
            p.add(new double[]{s.cx(), t.cy()});
            p.add(new double[]{s.cx() < t.cx() ? t.x() : t.x() + t.l(), t.cy()});
        }
        return p;
    }

    private Map<String, String> rolesLisibles() {
        Map<String, String> r = new LinkedHashMap<>();
        d.couloirs().forEach(c -> r.put(c.libelle(), switch (roles.getOrDefault(c.id(), Role.EXTERNE)) {
            case DEMANDEUR -> "demandeur (eFORCE)";
            case TRAITEMENT -> "organisme qui instruit et décide (eGOV, agent coté)";
            case EXTERNE -> "partenaire (passage, intégration à prévoir)";
        }));
        return r;
    }

    private String ident(Noeud n) {
        String prefixe = switch (n.type()) {
            case DEBUT -> "StartEvent_";
            case FIN -> "EndEvent_";
            case TACHE -> "Activity_";
            case PASSERELLE -> "Gateway_";
            case DOCUMENT, INTERMEDIAIRE -> "Event_";
        };
        return prefixe + ident(n.id());
    }

    private static String ident(String id) {
        String s = id.replaceAll("[^A-Za-z0-9_]", "_");
        return s.isEmpty() || !Character.isLetter(s.charAt(0)) ? "N" + s : s;
    }

    private static String n(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(Math.round(d * 10) / 10.0);
    }

    private static String xml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
