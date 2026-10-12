package com.nexora.procedures.agents;

import com.nexora.procedures.studio.Modele.*;

import java.util.*;

/**
 * Génère le workflow BPMN 2.0 exécutable d'une procédure de guichet unique, avec son diagramme (BPMN DI) :
 * dépôt → frais (règles) → avis de paiement et attente du paiement (ou délai dépassé) → cotation → traitement
 * (complément / validation pour signature / rejet) → signature (validation / retour pour réexamen) → délivrance.
 *
 * <p>Le XML utilise les extensions {@code camunda:} (Camunda Modeler, bpmn-js) que le moteur Operaton du Runtime
 * exécute. Les tâches de service appellent les délégués génériques du Runtime :
 * {@code ${regles}}, {@code ${paiement}}, {@code ${cotation}}, {@code ${message}}, {@code ${delivrance}}, {@code ${statut}}.
 */
public final class BpmnGenerateur {

    /** Délégués exposés par le Runtime (eservices) : le Compliance Agent refuse tout autre délégué. */
    public static final Set<String> DELEGUES = Set.of("regles", "paiement", "cotation", "message", "delivrance", "statut");
    /** Messages BPMN corrélés par le Runtime. */
    public static final Set<String> MESSAGES_BPMN = Set.of("PAIEMENT_CONFIRME");
    /** Statuts utilisés par le modèle générique. */
    public static final List<String> STATUTS = List.of("EN_ATTENTE_PAIEMENT", "A_COTER", "EN_ATTENTE_TRAITEMENT", "COMPLEMENT_DEMANDE",
            "EN_ATTENTE_SIGNATURE", "VALIDE", "REJETE", "ANNULE");

    private record Noeud(String id, String type, double cx, double cy) {
        double w() { return type.equals("task") ? 120 : type.equals("gateway") ? 50 : 36; }
        double h() { return type.equals("task") ? 80 : type.equals("gateway") ? 50 : 36; }
        double x() { return cx - w() / 2; }
        double y() { return cy - h() / 2; }
        double droite() { return cx + w() / 2; }
        double gauche() { return cx - w() / 2; }
        double haut() { return cy - h() / 2; }
        double bas() { return cy + h() / 2; }
    }

    private record Arc(String id, String source, String cible, String nom, String condition, List<double[]> points) {}

    private final StringBuilder process = new StringBuilder();
    private final Map<String, Noeud> noeuds = new LinkedHashMap<>();
    private final List<Arc> arcs = new ArrayList<>();
    private final Map<String, List<String>> entrants = new HashMap<>(), sortants = new HashMap<>();

    private BpmnGenerateur() {}

    /** Choix des messages du catalogue pour chaque étape (par type et par destinataire). */
    public record Choix(String avisPaiement, String complement, String rejet, List<String> delivrance, String livrable, boolean paiement,
                        boolean signature) {}

    public static Choix choisir(VersionComplete v) {
        Map<String, String> typeActeur = new HashMap<>();
        v.actors().forEach(a -> typeActeur.put(a.code(), a.kind()));
        Map<String, FlowMessage> usage = new HashMap<>();
        v.flows().forEach(f -> f.messages().forEach(m -> usage.putIfAbsent(m.messageCode(), m)));
        String avis = null, complement = null, rejet = null;
        List<String> delivrance = new ArrayList<>();
        for (MessageDef m : v.messages()) {
            FlowMessage u = usage.get(m.code());
            String emetteur = u == null ? "" : typeActeur.getOrDefault(u.senderActor(), "");
            String destinataire = u == null ? "" : typeActeur.getOrDefault(u.receiverActor(), "");
            switch (m.messageType()) {
                case "PAYMENT" -> { if (avis == null && !emetteur.equals("BANQUE")) avis = m.code(); }
                case "NOTIFICATION" -> {
                    if (complement == null && destinataire.equals("DEMANDEUR")) complement = m.code();
                    else if (!destinataire.equals("DEMANDEUR")) delivrance.add(m.code());
                }
                case "DECISION" -> { if (rejet == null) rejet = m.code(); }
                case "DOCUMENT" -> delivrance.add(0, m.code());
                default -> { }
            }
        }
        boolean signature = v.actors().stream().anyMatch(a -> "AGENT_SIGNATAIRE".equals(a.authRole()) || a.code().contains("SIGNATAIRE"));
        String livrable = v.deliverables().isEmpty() ? null : v.deliverables().getFirst().code();
        return new Choix(avis, complement, rejet, delivrance, livrable, !v.fees().isEmpty(), signature);
    }

    public static String generer(VersionComplete v, String cleProcessus) {
        return new BpmnGenerateur().construire(v, cleProcessus, choisir(v));
    }

    private String construire(VersionComplete v, String cle, Choix c) {
        String nomProcessus = v.procedure().labelFr();
        double y = 260;
        // --- Dépôt et frais
        evenement("StartEvent_Depot", "startEvent", "Demande soumise", 80, y, "");
        tache("Task_Frais", "Calculer les frais (règles)", 200, y, "regles", Map.of("point", "frais"), null, false);
        arc("Flow_Depot", "StartEvent_Depot", "Task_Frais", null, null);

        String apresPaiement;
        if (c.paiement()) {
            passerelle("Gateway_Paiement", "exclusiveGateway", "Paiement requis ?", 330, y, "Flow_SansPaiement");
            arc("Flow_VersPaiement", "Task_Frais", "Gateway_Paiement", null, null);
            Map<String, String> champs = new LinkedHashMap<>();
            if (c.avisPaiement() != null) champs.put("message", c.avisPaiement());
            tache("Task_AvisPaiement", "Émettre l'avis de paiement", 460, y, "paiement", champs, "EN_ATTENTE_PAIEMENT", false);
            arc("Flow_PaiementRequis", "Gateway_Paiement", "Task_AvisPaiement", "Oui", "${paiementRequis}");
            passerelle("Gateway_AttentePaiement", "eventBasedGateway", null, 590, y, null);
            arc("Flow_Attente", "Task_AvisPaiement", "Gateway_AttentePaiement", null, null);
            evenement("Event_PaiementConfirme", "intermediateCatchEvent", "Paiement confirmé", 690, y,
                    "<bpmn:messageEventDefinition id=\"Def_Paiement\" messageRef=\"Message_PaiementConfirme\" />");
            arc("Flow_Paye", "Gateway_AttentePaiement", "Event_PaiementConfirme", null, null);
            evenement("Event_DelaiPaiement", "intermediateCatchEvent", "Délai de paiement dépassé", 690, y + 120,
                    "<bpmn:timerEventDefinition id=\"Def_Delai\"><bpmn:timeDuration xsi:type=\"bpmn:tFormalExpression\">${delaiPaiement}</bpmn:timeDuration></bpmn:timerEventDefinition>");
            arcPoints("Flow_Delai", "Gateway_AttentePaiement", "Event_DelaiPaiement", null, null,
                    List.of(new double[]{590, y + 25}, new double[]{590, y + 120}, new double[]{672, y + 120}));
            tache("Task_Annulation", "Annuler la demande", 800, y + 120, "statut", Map.of("code", "ANNULE"), null, false);
            arc("Flow_Annuler", "Event_DelaiPaiement", "Task_Annulation", null, null);
            evenement("End_Annulee", "endEvent", "Demande annulée", 920, y + 120, "");
            arc("Flow_FinAnnulee", "Task_Annulation", "End_Annulee", null, null);
            passerelle("Gateway_JointPaiement", "exclusiveGateway", null, 790, y, null);
            arc("Flow_PayeSuite", "Event_PaiementConfirme", "Gateway_JointPaiement", null, null);
            arcPoints("Flow_SansPaiement", "Gateway_Paiement", "Gateway_JointPaiement", "Non", null,
                    List.of(new double[]{330, y - 25}, new double[]{330, y - 110}, new double[]{790, y - 110}, new double[]{790, y - 25}));
            apresPaiement = "Gateway_JointPaiement";
        } else {
            apresPaiement = "Task_Frais";
        }

        // --- Cotation et traitement
        tache("Task_Cotation", "Coter le dossier", 910, y, "cotation", Map.of(), "A_COTER", true);
        arc("Flow_VersCotation", apresPaiement, "Task_Cotation", null, null);
        tacheHumaine("Task_Traitement", "Traiter la demande", 1070, y, "${agentTraitement}", "EN_ATTENTE_TRAITEMENT",
                c.signature() ? "COMPLEMENT,VALIDER,REJETER" : "COMPLEMENT,VALIDER,REJETER", "traitement");
        arc("Flow_VersTraitement", "Task_Cotation", "Task_Traitement", null, null);
        passerelle("Gateway_Decision", "exclusiveGateway", "Décision de l'agent", 1210, y, null);
        arc("Flow_Decision", "Task_Traitement", "Gateway_Decision", null, null);

        // Complément d'information : notification puis tâche du demandeur, retour au traitement
        if (c.complement() != null) {
            tache("Task_NotifComplement", "Notifier le complément", 1210, y - 140, "message", Map.of("code", c.complement()), null, false);
            arc("Flow_Complement", "Gateway_Decision", "Task_NotifComplement", "Complément", "${decision == 'COMPLEMENT'}");
            tacheHumaine("Task_Complement", "Compléter le dossier", 1070, y - 140, "${demandeur}", "COMPLEMENT_DEMANDE", "SOUMETTRE", "complement");
            arc("Flow_VersComplement", "Task_NotifComplement", "Task_Complement", null, null);
        } else {
            tacheHumaine("Task_Complement", "Compléter le dossier", 1070, y - 140, "${demandeur}", "COMPLEMENT_DEMANDE", "SOUMETTRE", "complement");
            arcPoints("Flow_Complement", "Gateway_Decision", "Task_Complement", "Complément", "${decision == 'COMPLEMENT'}",
                    List.of(new double[]{1210, y - 25}, new double[]{1210, y - 140}, new double[]{1130, y - 140}));
        }
        arc("Flow_RetourComplement", "Task_Complement", "Task_Traitement", null, null);

        // Rejet : notification de la décision, statut final
        Map<String, String> rejet = c.rejet() == null ? Map.of("code", "") : Map.of("code", c.rejet());
        tache("Task_Rejet", "Notifier le rejet", 1210, y + 160, c.rejet() == null ? "statut" : "message",
                c.rejet() == null ? Map.of("code", "REJETE") : rejet, "REJETE", false);
        arc("Flow_Rejet", "Gateway_Decision", "Task_Rejet", "Rejet", "${decision == 'REJETER'}");
        evenement("End_Rejetee", "endEvent", "Demande rejetée", 1350, y + 160, "");
        arc("Flow_FinRejet", "Task_Rejet", "End_Rejetee", null, null);

        // Signature puis délivrance
        Map<String, String> deliv = new LinkedHashMap<>();
        if (c.livrable() != null) deliv.put("livrable", c.livrable());
        if (!c.delivrance().isEmpty()) deliv.put("messages", String.join(",", c.delivrance()));
        deliv.put("statut", "VALIDE");
        double xDeliv;
        if (c.signature()) {
            tacheHumaine("Task_Signature", "Valider et signer", 1350, y, "${signataire}", "EN_ATTENTE_SIGNATURE", "VALIDER,RETOUR", "signature");
            arc("Flow_Valider", "Gateway_Decision", "Task_Signature", "Validation pour signature", "${decision == 'VALIDER'}");
            passerelle("Gateway_Signature", "exclusiveGateway", "Décision du signataire", 1490, y, null);
            arc("Flow_DecisionSignature", "Task_Signature", "Gateway_Signature", null, null);
            arcPoints("Flow_Retour", "Gateway_Signature", "Task_Traitement", "Retour pour réexamen", "${decisionSignataire == 'RETOUR'}",
                    List.of(new double[]{1490, y + 25}, new double[]{1490, y + 270}, new double[]{1070, y + 270}, new double[]{1070, y + 40}));
            tache("Task_Delivrance", "Délivrer le livrable", 1620, y, "delivrance", deliv, null, false);
            arc("Flow_Signe", "Gateway_Signature", "Task_Delivrance", "Validé", "${decisionSignataire == 'VALIDER'}");
            xDeliv = 1620;
        } else {
            tache("Task_Delivrance", "Délivrer le livrable", 1350, y, "delivrance", deliv, null, false);
            arc("Flow_Valider", "Gateway_Decision", "Task_Delivrance", "Validation", "${decision == 'VALIDER'}");
            xDeliv = 1350;
        }
        evenement("End_Delivree", "endEvent", "Livrable délivré", xDeliv + 140, y, "");
        arc("Flow_Fin", "Task_Delivrance", "End_Delivree", null, null);

        return document(cle, nomProcessus, c.paiement());
    }

    // ------------------------------------------------------------------ éléments
    private void evenement(String id, String type, String nom, double cx, double cy, String definition) {
        noeuds.put(id, new Noeud(id, "event", cx, cy));
        process.append("    <bpmn:").append(type).append(" id=\"").append(id).append("\" name=\"").append(xml(nom)).append("\">\n")
                .append("%IO:").append(id).append("%")
                .append(definition.isEmpty() ? "" : "      " + definition + "\n")
                .append("    </bpmn:").append(type).append(">\n");
    }

    private void passerelle(String id, String type, String nom, double cx, double cy, String parDefaut) {
        noeuds.put(id, new Noeud(id, "gateway", cx, cy));
        process.append("    <bpmn:").append(type).append(" id=\"").append(id).append("\"")
                .append(nom == null ? "" : " name=\"" + xml(nom) + "\"")
                .append(parDefaut == null ? "" : " default=\"" + parDefaut + "\"").append(">\n")
                .append("%IO:").append(id).append("%")
                .append("    </bpmn:").append(type).append(">\n");
    }

    private void tache(String id, String nom, double cx, double cy, String delegue, Map<String, String> champs, String statut, boolean asynchrone) {
        noeuds.put(id, new Noeud(id, "task", cx, cy));
        process.append("    <bpmn:serviceTask id=\"").append(id).append("\" name=\"").append(xml(nom)).append("\"")
                .append(asynchrone ? " camunda:asyncBefore=\"true\"" : "")
                .append(" camunda:delegateExpression=\"${").append(delegue).append("}\">\n");
        extensions(champs, statut, null);
        process.append("%IO:").append(id).append("%").append("    </bpmn:serviceTask>\n");
    }

    private void tacheHumaine(String id, String nom, double cx, double cy, String assigne, String statut, String decisions, String formulaire) {
        noeuds.put(id, new Noeud(id, "task", cx, cy));
        process.append("    <bpmn:userTask id=\"").append(id).append("\" name=\"").append(xml(nom)).append("\" camunda:assignee=\"")
                .append(assigne).append("\" camunda:formKey=\"nexora:").append(formulaire).append("\">\n");
        extensions(Map.of(), statut, decisions);
        process.append("%IO:").append(id).append("%").append("    </bpmn:userTask>\n");
    }

    private void extensions(Map<String, String> champs, String statut, String decisions) {
        if (champs.isEmpty() && statut == null && decisions == null) return;
        process.append("      <bpmn:extensionElements>\n");
        if (decisions != null)
            process.append("        <camunda:properties>\n          <camunda:property name=\"decisions\" value=\"").append(decisions)
                    .append("\" />\n        </camunda:properties>\n");
        champs.forEach((k, val) -> process.append("        <camunda:field name=\"").append(k).append("\" stringValue=\"").append(xml(val)).append("\" />\n"));
        if (statut != null)
            process.append("        <camunda:executionListener delegateExpression=\"${statut}\" event=\"start\">\n")
                    .append("          <camunda:field name=\"code\" stringValue=\"").append(statut).append("\" />\n")
                    .append("        </camunda:executionListener>\n");
        process.append("      </bpmn:extensionElements>\n");
    }

    private void arc(String id, String source, String cible, String nom, String condition) {
        Noeud s = noeuds.get(source), t = noeuds.get(cible);
        List<double[]> p = new ArrayList<>();
        if (Math.abs(s.cy - t.cy) < 1) {
            p.add(new double[]{s.cx < t.cx ? s.droite() : s.gauche(), s.cy});
            p.add(new double[]{s.cx < t.cx ? t.gauche() : t.droite(), t.cy});
        } else if (Math.abs(s.cx - t.cx) < 1) {
            p.add(new double[]{s.cx, s.cy < t.cy ? s.bas() : s.haut()});
            p.add(new double[]{t.cx, s.cy < t.cy ? t.haut() : t.bas()});
        } else if (s.type.equals("gateway")) {
            // sortie verticale de la passerelle puis horizontale vers la cible
            p.add(new double[]{s.cx, s.cy < t.cy ? s.bas() : s.haut()});
            p.add(new double[]{s.cx, t.cy});
            p.add(new double[]{s.cx < t.cx ? t.gauche() : t.droite(), t.cy});
        } else {
            p.add(new double[]{s.cx < t.cx ? s.droite() : s.gauche(), s.cy});
            p.add(new double[]{t.cx, s.cy});
            p.add(new double[]{t.cx, s.cy < t.cy ? t.haut() : t.bas()});
        }
        arcPoints(id, source, cible, nom, condition, p);
    }

    private void arcPoints(String id, String source, String cible, String nom, String condition, List<double[]> points) {
        arcs.add(new Arc(id, source, cible, nom, condition, points));
        sortants.computeIfAbsent(source, k -> new ArrayList<>()).add(id);
        entrants.computeIfAbsent(cible, k -> new ArrayList<>()).add(id);
    }

    private String document(String cle, String nom, boolean paiement) {
        String corps = process.toString();
        for (String id : noeuds.keySet()) {
            StringBuilder io = new StringBuilder();
            entrants.getOrDefault(id, List.of()).forEach(a -> io.append("      <bpmn:incoming>").append(a).append("</bpmn:incoming>\n"));
            sortants.getOrDefault(id, List.of()).forEach(a -> io.append("      <bpmn:outgoing>").append(a).append("</bpmn:outgoing>\n"));
            corps = corps.replace("%IO:" + id + "%", io.toString());
        }
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"")
                .append(" xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\" xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\"")
                .append(" xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\" xmlns:camunda=\"http://camunda.org/schema/1.0/bpmn\"")
                .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" id=\"Definitions_").append(cle)
                .append("\" targetNamespace=\"https://nexora.trade/bpmn\" exporter=\"NEXORA Studio — Single Window Agent\" exporterVersion=\"1.0\">\n");
        x.append("  <bpmn:process id=\"").append(cle).append("\" name=\"").append(xml(nom)).append("\" isExecutable=\"true\" camunda:historyTimeToLive=\"P3650D\">\n");
        x.append(corps);
        for (Arc a : arcs) {
            x.append("    <bpmn:sequenceFlow id=\"").append(a.id).append("\" sourceRef=\"").append(a.source).append("\" targetRef=\"")
                    .append(a.cible).append("\"").append(a.nom == null ? "" : " name=\"" + xml(a.nom) + "\"");
            if (a.condition == null) x.append(" />\n");
            else x.append(">\n      <bpmn:conditionExpression xsi:type=\"bpmn:tFormalExpression\">").append(xml(a.condition))
                    .append("</bpmn:conditionExpression>\n    </bpmn:sequenceFlow>\n");
        }
        x.append("  </bpmn:process>\n");
        if (paiement) x.append("  <bpmn:message id=\"Message_PaiementConfirme\" name=\"PAIEMENT_CONFIRME\" />\n");
        // Diagramme
        x.append("  <bpmndi:BPMNDiagram id=\"Diagram_").append(cle).append("\">\n    <bpmndi:BPMNPlane id=\"Plane_").append(cle)
                .append("\" bpmnElement=\"").append(cle).append("\">\n");
        for (Noeud n : noeuds.values())
            x.append("      <bpmndi:BPMNShape id=\"").append(n.id).append("_di\" bpmnElement=\"").append(n.id).append("\"")
                    .append(n.type.equals("gateway") && corps.contains("<bpmn:exclusiveGateway id=\"" + n.id) ? " isMarkerVisible=\"true\"" : "")
                    .append(">\n        <dc:Bounds x=\"").append(f(n.x())).append("\" y=\"").append(f(n.y())).append("\" width=\"")
                    .append(f(n.w())).append("\" height=\"").append(f(n.h())).append("\" />\n      </bpmndi:BPMNShape>\n");
        for (Arc a : arcs) {
            x.append("      <bpmndi:BPMNEdge id=\"").append(a.id).append("_di\" bpmnElement=\"").append(a.id).append("\">\n");
            for (double[] p : a.points) x.append("        <di:waypoint x=\"").append(f(p[0])).append("\" y=\"").append(f(p[1])).append("\" />\n");
            x.append("      </bpmndi:BPMNEdge>\n");
        }
        x.append("    </bpmndi:BPMNPlane>\n  </bpmndi:BPMNDiagram>\n</bpmn:definitions>\n");
        return x.toString();
    }

    private static String f(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String xml(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
