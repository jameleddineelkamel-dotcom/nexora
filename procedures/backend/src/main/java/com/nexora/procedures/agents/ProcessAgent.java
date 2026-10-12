package com.nexora.procedures.agents;

import com.nexora.procedures.agents.Agents.*;
import com.nexora.procedures.studio.Modele.*;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Single Window Agent — fonction « Process Design » : génère le workflow BPMN 2.0 exécutable de la procédure à partir
 * de ses acteurs, statuts, frais, messages et livrables, et propose les statuts manquants du modèle générique.
 */
@Component
public class ProcessAgent implements Agent {

    private final LectureImages images;

    public ProcessAgent(LectureImages images) {
        this.images = images;
    }

    static final Map<String, String[]> LIBELLES_STATUTS = Map.of(
            "EN_ATTENTE_PAIEMENT", new String[]{"En attente de paiement", "Awaiting payment", "warn"},
            "A_COTER", new String[]{"À coter", "To be assigned", "info"},
            "EN_ATTENTE_TRAITEMENT", new String[]{"En attente de traitement", "Awaiting processing", "info"},
            "COMPLEMENT_DEMANDE", new String[]{"Complément d'information demandé", "Additional information requested", "warn"},
            "EN_ATTENTE_SIGNATURE", new String[]{"En attente de signature", "Awaiting signature", "info"},
            "VALIDE", new String[]{"Validé — livrable délivré", "Approved — deliverable issued", "ok"},
            "REJETE", new String[]{"Rejeté", "Rejected", "danger"},
            "ANNULE", new String[]{"Annulé", "Cancelled", "neutral"});

    @Override
    public String code() {
        return "PROCESSUS";
    }

    @Override
    public Sortie executer(Contexte ctx) {
        if (!ctx.diagrammes().isEmpty() || images.disponible() && ctx.annexes().stream().anyMatch(Annexe::image)) {
            Sortie s = ImportDiagrammes.importer(ctx, LIBELLES_STATUTS, images);
            if (!s.propositions().isEmpty() || !ctx.diagrammes().isEmpty()) return s;
        }
        VersionComplete v = ctx.version();
        List<Proposition> props = new ArrayList<>();
        Set<String> existants = new HashSet<>();
        v.statuses().forEach(s -> existants.add(s.code()));
        int ordre = v.statuses().stream().mapToInt(CaseStatus::sortOrder).max().orElse(0);
        for (String s : BpmnGenerateur.STATUTS) {
            if (existants.contains(s)) continue;
            String[] l = LIBELLES_STATUTS.get(s);
            ordre += 10;
            props.add(new Proposition("STATUS", s, "Statut « " + l[0] + " »",
                    Map.of("code", s, "labelFr", l[0], "labelEn", l[1], "isFinal", List.of("VALIDE", "REJETE", "ANNULE").contains(s), "tone", l[2], "sortOrder", ordre),
                    "Statut utilisé par le workflow générique du guichet unique (terminologie GUCE : statut du dossier).", null, "generation", 0.95));
        }
        String cle = v.process() != null && v.process().processKey() != null ? v.process().processKey() : v.procedure().code().toLowerCase(Locale.ROOT);
        BpmnGenerateur.Choix c = BpmnGenerateur.choisir(v);
        String xml = BpmnGenerateur.generer(v, cle);
        List<String> etapes = new ArrayList<>(List.of("dépôt", "calcul des frais (règles)"));
        if (c.paiement()) etapes.add("avis de paiement" + (c.avisPaiement() != null ? " (" + c.avisPaiement() + ")" : "") + " puis attente du paiement ou délai dépassé");
        etapes.add("cotation automatique");
        etapes.add("traitement (complément / validation / rejet)");
        if (c.signature()) etapes.add("signature (validation / retour pour réexamen)");
        etapes.add("délivrance" + (c.livrable() != null ? " du livrable " + c.livrable() : ""));
        boolean identique = v.process() != null && xml.equals(v.process().bpmnXml());
        if (!identique)
            props.add(new Proposition("BPMN", cle, "Workflow BPMN « " + v.procedure().labelFr() + " »", Map.of("processKey", cle, "bpmnXml", xml),
                    "Enchaînement généré : " + String.join(" → ", etapes) + ". Tâches de service reliées aux délégués du Runtime "
                            + "(regles, paiement, cotation, message, delivrance, statut) ; tâches humaines assignées à l'agent coté, au demandeur et au signataire.",
                    null, "generation", 0.85));
        return Sortie.de(identique ? "Le workflow BPMN est déjà à jour." : "Workflow BPMN généré (" + etapes.size() + " étapes) ; "
                + props.stream().filter(p -> p.kind().equals("STATUS")).count() + " statut(s) à ajouter.", props);
    }
}
