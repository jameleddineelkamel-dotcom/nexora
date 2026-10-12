package com.nexora.procedures.agents;

import com.nexora.procedures.rdm.RdmService;
import com.nexora.procedures.studio.Modele.*;
import com.nexora.regles.MoteurRegles;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.*;
import org.operaton.bpm.model.bpmn.instance.Process;
import org.operaton.bpm.model.xml.instance.DomElement;
import org.operaton.bpm.model.xml.instance.ModelElementInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Contrôles du Compliance Agent avant soumission et publication d'une version (dossier v4.2, chap. 11) :
 * terminologie GUCE, types de demande, acteurs, statuts, formulaire et listes de codes du Référentiel Commun,
 * chemins BIE du SCRDM, catalogue de messages et flux, workflow BPMN exécutable, règles DRL compilables, livrables.
 * Les constats « error » bloquent la soumission et la publication.
 */
@Component
public class Conformite {

    private static final Pattern DELEGUE = Pattern.compile("^\\$\\{([A-Za-z]+)}$");

    private final RdmService rdm;
    private final JdbcClient jdbc;

    public Conformite(RdmService rdm, JdbcClient jdbc) {
        this.rdm = rdm;
        this.jdbc = jdbc;
    }

    public List<Constat> controler(VersionComplete v) {
        List<Constat> c = new ArrayList<>();
        Set<String> natures = new HashSet<>(jdbc.sql("select code from studio.request_nature").query(String.class).list());
        Set<String> acteurs = new HashSet<>();
        v.actors().forEach(a -> acteurs.add(a.code()));
        Set<String> statuts = new HashSet<>();
        v.statuses().forEach(s -> statuts.add(s.code()));
        Set<String> champs = new HashSet<>();
        v.fields().forEach(f -> champs.add(f.code()));

        // Terminologie et types de demande
        c.add(Constat.info("RG-TERM-01", v.procedure().code(), "Procédure rattachée au circuit " + v.procedure().circuitCode() + "."));
        if (v.requestTypes().isEmpty()) c.add(Constat.erreur("TYPES", "requestTypes", "Aucun type de demande : définissez au moins une déclinaison (code CUR)."));
        for (RequestType t : v.requestTypes()) {
            if (t.natures().isEmpty()) c.add(Constat.erreur("TYPES", t.code(), "Le type " + t.code() + " n'autorise aucune nature de demande."));
            t.natures().stream().filter(n -> !natures.contains(n))
                    .forEach(n -> c.add(Constat.erreur("RG-TERM-01", t.code(), "Nature de demande inconnue : " + n + ".")));
        }

        // Acteurs et statuts
        if (v.actors().stream().noneMatch(a -> a.kind().equals("DEMANDEUR")))
            c.add(Constat.erreur("ACTEURS", "actors", "Aucun acteur de type DEMANDEUR (émetteur de la demande)."));
        if (v.statuses().stream().noneMatch(CaseStatus::isFinal))
            c.add(Constat.erreur("STATUTS", "statuses", "Aucun statut final : le dossier ne pourrait jamais être clos."));

        // Formulaire
        if (v.fields().isEmpty()) c.add(Constat.erreur("FORMULAIRE", "fields", "Le formulaire ne contient aucun champ."));
        int sansBie = 0;
        for (Field f : v.fields()) {
            if (f.dataType().equals("CODE") && blanc(f.refdataTable()) && (f.codeValues() == null || f.codeValues().isEmpty()))
                c.add(Constat.erreur("RG-REF-01", f.code(), "Le champ « " + f.labelFr() + " » est une liste sans table du Référentiel Commun ni valeurs."));
            if (!blanc(f.refdataTable()) && !f.refdataTable().startsWith("@") && !f.refdataTable().matches("^REF_[A-Z0-9_]+$"))
                c.add(Constat.erreur("RG-REF-01", f.code(), "Table de référence invalide : " + f.refdataTable() + " (attendu REF_xxx)."));
            if ("CONDITIONNEL".equals(f.mandatory()) && blanc(f.conditionText()))
                c.add(Constat.alerte("FORMULAIRE", f.code(), "Champ conditionnel sans condition : « " + f.labelFr() + " »."));
            if (blanc(f.biePath())) sansBie++;
            else if (rdm.resoudre(f.biePath()).isEmpty())
                c.add(Constat.erreur("RG-REF-02", f.code(), "Chemin BIE introuvable dans le SCRDM D23B : " + f.biePath() + "."));
        }
        if (sansBie > 0)
            c.add(Constat.alerte("RG-REF-02", "fields", sansBie + " champ(s) sans chemin BIE SCRDM : lancez le Data Agent pour les rapprocher."));

        // Pièces et frais
        for (FeeItem f : v.fees())
            if (!acteurs.contains(f.beneficiary()))
                c.add(Constat.erreur("FRAIS", f.code(), "Bénéficiaire des frais inconnu parmi les acteurs : " + f.beneficiary() + "."));
        for (RequiredDocument d : v.documents())
            if (!blanc(d.conditionText()) && DrlGenerateur.traduire(d.conditionText()) == null)
                c.add(Constat.alerte("PIECES", d.code(), "Condition de la pièce " + d.code() + " non traduisible en règle : vérifiez le DRL."));

        // Messages et flux
        Set<String> utilises = new HashSet<>();
        for (Flow f : v.flows()) {
            if (f.messages().isEmpty()) c.add(Constat.erreur("RG-FLX-01", f.code(), "Le flux « " + f.labelFr() + " » ne contient aucun message."));
            f.messages().forEach(m -> utilises.add(m.messageCode()));
        }
        for (MessageDef m : v.messages()) {
            if (!utilises.contains(m.code())) c.add(Constat.alerte("RG-MSG-01", m.code(), "Message défini mais utilisé par aucun flux : " + m.code() + "."));
            if (m.fields().isEmpty() && blanc(m.schemaText()))
                c.add(Constat.alerte("RG-MSG-02", m.code(), "Le message " + m.code() + " ne déclare ni champ ni schéma."));
            m.fields().stream().filter(x -> !blanc(x.fieldCode()) && !champs.contains(x.fieldCode()))
                    .forEach(x -> c.add(Constat.erreur("RG-MSG-02", m.code(), "Champ inconnu dans le message " + m.code() + " : " + x.fieldCode() + ".")));
        }

        // Workflow BPMN
        if (v.process() == null || blanc(v.process().bpmnXml())) c.add(Constat.erreur("BPMN", "process", "Aucun workflow BPMN : lancez le Single Window Agent (Process Design)."));
        else controlerBpmn(v, statuts, c);

        // Règles DRL
        if (v.rules() == null || blanc(v.rules().drl())) c.add(Constat.erreur("DRL", "rules", "Aucune règle DRL : lancez le Rules Agent."));
        else {
            List<String> erreurs = MoteurRegles.verifier(v.rules().drl());
            erreurs.forEach(e -> c.add(Constat.erreur("DRL", "rules", e)));
            if (erreurs.isEmpty()) c.add(Constat.info("DRL", "rules", "Les règles DRL compilent sans erreur (Drools)."));
        }

        // Livrables
        for (Deliverable d : v.deliverables())
            d.bodyFields().stream().filter(x -> !champs.contains(x))
                    .forEach(x -> c.add(Constat.erreur("LIVRABLE", d.code(), "Champ inconnu reporté sur le livrable : " + x + ".")));
        return c;
    }

    private void controlerBpmn(VersionComplete v, Set<String> statuts, List<Constat> c) {
        BpmnModelInstance m;
        try {
            m = Bpmn.readModelFromStream(new ByteArrayInputStream(v.process().bpmnXml().getBytes(StandardCharsets.UTF_8)));
            Bpmn.validateModel(m);
        } catch (RuntimeException e) {
            c.add(Constat.erreur("BPMN", "process", "BPMN illisible : " + Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName())));
            return;
        }
        Collection<Process> processus = m.getModelElementsByType(Process.class);
        Process p = processus.stream().filter(Process::isExecutable).findFirst().orElse(null);
        if (p == null) {
            c.add(Constat.erreur("BPMN", "process", "Aucun processus exécutable (isExecutable=\"true\")."));
            return;
        }
        if (!p.getId().equals(v.process().processKey()))
            c.add(Constat.erreur("BPMN", "process", "La clé du processus (" + v.process().processKey() + ") diffère de l'identifiant BPMN (" + p.getId() + ")."));
        if (m.getModelElementsByType(StartEvent.class).isEmpty()) c.add(Constat.erreur("BPMN", p.getId(), "Aucun événement de début."));
        if (m.getModelElementsByType(EndEvent.class).isEmpty()) c.add(Constat.erreur("BPMN", p.getId(), "Aucun événement de fin."));
        Set<String> messagesCatalogue = new HashSet<>();
        v.messages().forEach(x -> messagesCatalogue.add(x.code()));
        Set<String> livrables = new HashSet<>();
        v.deliverables().forEach(d -> livrables.add(d.code()));

        // Tâches sans comportement (bpmn:task) : franchies automatiquement par le moteur
        for (Task t : m.getModelElementsByType(Task.class))
            if (t.getElementType().getTypeName().equals("task"))
                c.add(Constat.alerte("BPMN", t.getId(), "Tâche « " + t.getName() + " » sans comportement : elle est franchie sans attente "
                        + "(acceptable pour une étape réalisée hors workflow ; sinon, en faire une tâche humaine ou de service)."));
        for (ServiceTask t : m.getModelElementsByType(ServiceTask.class)) {
            String expr = t.getOperatonDelegateExpression();
            Matcher d = expr == null ? null : DELEGUE.matcher(expr);
            if (d == null || !d.matches() || !BpmnGenerateur.DELEGUES.contains(d.group(1))) {
                c.add(Constat.erreur("BPMN", t.getId(), "Tâche « " + t.getName() + " » : délégué inconnu du Runtime (" + expr + "). Délégués : "
                        + String.join(", ", new TreeSet<>(BpmnGenerateur.DELEGUES)) + "."));
                continue;
            }
            Map<String, String> champs = champs(t);
            switch (d.group(1)) {
                case "message" -> verifierMessages(champs.get("code"), messagesCatalogue, t, c);
                case "statut" -> verifierStatut(champs.get("code"), statuts, t, c);
                case "regles" -> {
                    if (!MoteurRegles.POINTS.contains(champs.getOrDefault("point", "")))
                        c.add(Constat.erreur("BPMN", t.getId(), "Point de règles inconnu : " + champs.get("point") + " (attendu : " + MoteurRegles.POINTS + ")."));
                }
                case "paiement" -> verifierMessages(champs.get("message"), messagesCatalogue, t, c);
                case "delivrance" -> {
                    if (champs.containsKey("livrable") && !livrables.contains(champs.get("livrable")))
                        c.add(Constat.erreur("BPMN", t.getId(), "Livrable inconnu : " + champs.get("livrable") + "."));
                    verifierMessages(champs.get("messages"), messagesCatalogue, t, c);
                    verifierStatut(champs.get("statut"), statuts, t, c);
                }
                default -> { }
            }
        }
        // Statuts posés par les écouteurs d'exécution
        for (DomElement e : tous(m.getDocument().getRootElement())) {
            if (!e.getLocalName().equals("executionListener")) continue;
            for (DomElement f : e.getChildElements())
                if (f.getLocalName().equals("field") && "code".equals(f.getAttribute("name"))) verifierStatut(f.getAttribute("stringValue"), statuts, null, c);
        }
        for (UserTask u : m.getModelElementsByType(UserTask.class))
            if (blanc(u.getOperatonAssignee()) && blanc(u.getOperatonCandidateGroups()) && blanc(u.getOperatonCandidateUsers()))
                c.add(Constat.erreur("BPMN", u.getId(), "Tâche humaine « " + u.getName() + " » sans responsable (assignee ou groupes candidats, RG-PRC-02)."));
        for (Message msg : m.getModelElementsByType(Message.class))
            if (!BpmnGenerateur.MESSAGES_BPMN.contains(msg.getName()))
                c.add(Constat.alerte("BPMN", msg.getId(), "Message BPMN non corrélé par le Runtime : " + msg.getName() + "."));
        c.add(Constat.info("BPMN", p.getId(), "Workflow BPMN lisible : " + m.getModelElementsByType(FlowNode.class).size() + " nœuds, "
                + m.getModelElementsByType(SequenceFlow.class).size() + " transitions."));
    }

    private static void verifierMessages(String codes, Set<String> catalogue, ServiceTask t, List<Constat> c) {
        if (blanc(codes)) return;
        for (String code : codes.split(","))
            if (!catalogue.contains(code.strip()))
                c.add(Constat.erreur("BPMN", t.getId(), "Message absent du catalogue : " + code.strip() + " (tâche « " + t.getName() + " »)."));
    }

    private static void verifierStatut(String code, Set<String> statuts, ServiceTask t, List<Constat> c) {
        if (blanc(code)) return;
        if (!statuts.contains(code))
            c.add(Constat.erreur("BPMN", t == null ? "process" : t.getId(), "Statut de dossier inconnu : " + code + "."));
    }

    /** Champs injectés (camunda:field) d'une tâche de service. */
    private static Map<String, String> champs(ServiceTask t) {
        Map<String, String> r = new HashMap<>();
        ExtensionElements ext = t.getExtensionElements();
        if (ext == null) return r;
        for (ModelElementInstance e : ext.getElements()) {
            DomElement d = e.getDomElement();
            if (d.getLocalName().equals("field")) {
                String v = d.getAttribute("stringValue");
                if (v == null) v = d.getChildElements().stream().filter(x -> x.getLocalName().equals("string")).map(DomElement::getTextContent).findFirst().orElse(null);
                r.put(d.getAttribute("name"), v);
            }
        }
        return r;
    }

    private static List<DomElement> tous(DomElement racine) {
        List<DomElement> l = new ArrayList<>();
        Deque<DomElement> pile = new ArrayDeque<>(List.of(racine));
        while (!pile.isEmpty()) {
            DomElement e = pile.pop();
            l.add(e);
            pile.addAll(e.getChildElements());
        }
        return l;
    }

    private static boolean blanc(String s) {
        return s == null || s.isBlank();
    }

    public static boolean bloquant(List<Constat> constats) {
        return constats.stream().anyMatch(x -> x.severity().equals("error"));
    }
}
