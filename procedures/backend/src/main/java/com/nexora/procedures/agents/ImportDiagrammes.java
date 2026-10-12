package com.nexora.procedures.agents;

import com.nexora.procedures.agents.Agents.*;
import com.nexora.procedures.studio.Modele.*;

import java.util.*;

/** Import des diagrammes joints à une exécution du Process Agent : draw.io converti par {@link BpmnImport}, ou BPMN 2.0 repris tel quel. */
final class ImportDiagrammes {

    private ImportDiagrammes() {}

    private record Candidat(String nom, DrawioSource drawio, String bpmn, int score) {}

    /** Importe le diagramme principal joint (draw.io converti, ou BPMN 2.0 repris tel quel) ; signale les autres. */
    static Sortie importer(Contexte ctx, Map<String, String[]> libelles) {
        VersionComplete v = ctx.version();
        List<Proposition> props = new ArrayList<>();
        List<Candidat> candidats = new ArrayList<>();
        for (Diagramme d : ctx.diagrammes()) {
            if (d.bpmn()) candidats.add(new Candidat(d.nom(), null, d.contenu(), 1000));
            else
                try {
                    List<DrawioSource> pages = DrawioSource.lire(d.contenu());
                    for (DrawioSource p : pages)
                        candidats.add(new Candidat(d.nom() + (pages.size() > 1 ? " › " + p.page() : ""), p, null, p.score()));
                } catch (IllegalArgumentException e) {
                    props.add(constat("warning", "Diagramme illisible : " + d.nom(), e.getMessage()));
                }
        }
        if (candidats.isEmpty()) return Sortie.de("Aucun diagramme exploitable parmi les fichiers joints.", props);
        // Diagramme principal : BPMN 2.0 fourni, sinon le diagramme draw.io le plus complet (premier fichier en cas d'égalité)
        Candidat principal = candidats.stream().max(Comparator.comparingInt(Candidat::score)).orElseThrow();
        for (Candidat c : candidats)
            if (c != principal)
                props.add(constat("info", "Diagramme non importé : " + c.nom(),
                        "Seul le diagramme principal (« " + principal.nom() + " ») devient le workflow de la procédure. Ce diagramme décrit sans doute une "
                                + "variante (modification, annulation) ou une vue d'ensemble : modélisez-la par une nature de demande (AMENDMENT, CANCELLATION) "
                                + "ou une procédure distincte."));
        String cle = v.process() != null && v.process().processKey() != null ? v.process().processKey() : v.procedure().code().toLowerCase(Locale.ROOT);
        String xml;
        String justification;
        Set<String> statutsRequis = new LinkedHashSet<>();
        if (principal.bpmn() != null) {
            xml = principal.bpmn();
            justification = "Workflow BPMN 2.0 fourni (« " + principal.nom() + " ») repris tel quel : le Compliance Agent vérifiera qu'il respecte le contrat "
                    + "du Runtime (délégués, statuts, responsables des tâches).";
        } else {
            BpmnImport.Resultat r = BpmnImport.convertir(principal.drawio(), v, cle);
            xml = r.xml();
            statutsRequis.addAll(r.statuts());
            StringBuilder j = new StringBuilder("Conversion du diagramme draw.io « " + principal.nom() + " » en workflow exécutable, tracé et couloirs conservés. ");
            if (!r.roles().isEmpty()) {
                j.append("Couloirs : ");
                r.roles().forEach((c, role) -> j.append("« ").append(c).append(" » → ").append(role).append(" ; "));
            }
            j.append("Enchaînement : ").append(String.join(" → ", r.etapes())).append('.');
            justification = j.toString();
            r.alertes().forEach(a -> props.add(constat("warning", "Import du diagramme : point à vérifier", a)));
            for (BpmnImport.Livrable l : r.livrables()) {
                if (v.deliverables().stream().anyMatch(d -> d.code().equals(l.code()))) continue;
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("code", l.code());
                p.put("labelFr", l.libelle());
                p.put("labelEn", null);
                p.put("docCode", l.docCode());
                p.put("validityRule", "AUCUNE");
                p.put("titleFr", l.libelle().toUpperCase(Locale.ROOT));
                p.put("bodyFields", v.fields().stream().filter(f -> List.of("taxIdentifier", "legalName").contains(f.code())).map(Field::code).toList());
                p.put("signatureNote", "Le responsable habilité");
                props.add(new Proposition("DELIVERABLE", l.code(), "Livrable « " + l.libelle() + " » (code document " + l.docCode() + ")", p,
                        "Document délivré par le workflow du diagramme ; son numéro sera le CUR du dossier suivi de « " + l.docCode()
                                + " ». Complétez les champs imprimés et la règle de validité.", l.libelle(), "extraction", 0.8));
            }
            int rang = v.statuses().stream().mapToInt(CaseStatus::sortOrder).max().orElse(0) + 100;
            for (BpmnImport.Statut s : r.statutsPropres()) {
                if (v.statuses().stream().anyMatch(x -> x.code().equals(s.code()))) continue;
                rang += 10;
                props.add(new Proposition("STATUS", s.code(), "Statut « " + s.libelle() + " »" + (s.fin() ? " (final)" : ""),
                        Map.of("code", s.code(), "labelFr", s.libelle(), "labelEn", s.libelle(), "isFinal", s.fin(), "tone", s.ton(), "sortOrder", rang),
                        s.fin() ? "Statut final posé par l'événement de fin « " + s.libelle() + " » du diagramme."
                                : "Statut posé à la délivrance du document en cours de processus (le dossier reste ouvert pour la suite).",
                        s.libelle(), "extraction", 0.85));
            }
        }
        // Statuts utilisés par le workflow importé et absents de la version
        Set<String> existants = new HashSet<>();
        v.statuses().forEach(s -> existants.add(s.code()));
        int ordre = v.statuses().stream().mapToInt(CaseStatus::sortOrder).max().orElse(0);
        for (String s : statutsRequis) {
            if (existants.contains(s) || !libelles.containsKey(s)) continue;
            String[] l = libelles.get(s);
            ordre += 10;
            props.add(new Proposition("STATUS", s, "Statut « " + l[0] + " »",
                    Map.of("code", s, "labelFr", l[0], "labelEn", l[1], "isFinal", List.of("VALIDE", "REJETE", "ANNULE").contains(s), "tone", l[2], "sortOrder", ordre),
                    "Statut posé par le workflow importé.", null, "generation", 0.95));
        }
        boolean identique = v.process() != null && xml.equals(v.process().bpmnXml());
        if (!identique)
            props.add(new Proposition("BPMN", cle, "Workflow BPMN importé de « " + principal.nom() + " »", Map.of("processKey", cle, "bpmnXml", xml),
                    justification, principal.nom(), "extraction", principal.bpmn() != null ? 0.9 : 0.75));
        long alertes = props.stream().filter(p -> p.kind().equals("FINDING") && "warning".equals(p.payload().get("severity"))).count();
        return Sortie.de(identique ? "Le workflow importé de « " + principal.nom() + " » est déjà celui de la version."
                : "Workflow importé de « " + principal.nom() + " » (" + candidats.size() + " diagramme(s) lu(s)) ; " + alertes + " point(s) à vérifier, "
                + props.stream().filter(p -> p.kind().equals("STATUS")).count() + " statut(s) à ajouter.", props);
    }

    private static Proposition constat(String severite, String titre, String message) {
        return new Proposition("FINDING", "bpmn-" + Integer.toHexString((titre + message).hashCode()), titre,Map.of("severity", severite, "rule", "IMPORT-BPMN", "message", message), message, null, "check", 0.9);
    }
}
