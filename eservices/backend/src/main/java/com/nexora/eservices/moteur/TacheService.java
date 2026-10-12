package com.nexora.eservices.moteur;

import com.nexora.eservices.audit.Journal;
import com.nexora.eservices.dossier.Dossier;
import com.nexora.eservices.dossier.DossierRepository;
import com.nexora.eservices.dossier.DossierService;
import com.nexora.eservices.security.Habilitations;
import com.nexora.eservices.security.SecurityConfig;
import com.nexora.eservices.web.EservicesException;
import org.operaton.bpm.engine.ManagementService;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.runtime.Incident;
import org.operaton.bpm.engine.task.Task;
import org.operaton.bpm.model.bpmn.instance.UserTask;
import org.operaton.bpm.model.xml.instance.DomElement;
import org.operaton.bpm.model.xml.instance.ModelElementInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tâches humaines du workflow : traitement (UC-04 : complément / validation pour signature / rejet), signature (UC-05 :
 * validation / retour pour réexamen), complément du demandeur. Une tâche n'est visible et exécutable que par la
 * personne à qui elle est assignée (RG2 UC-04) ; les décisions possibles sont celles déclarées dans le BPMN
 * (propriété « decisions ») ; l'observation est obligatoire pour un complément, un rejet ou un retour.
 */
@Service
public class TacheService {

    public record Tache(String id, String name, String taskKey, String formKey, String assignee, OffsetDateTime created, OffsetDateTime due,
                        List<String> decisions, UUID dossierId, String dossierNo, String procedureCode, String requestType, String requestNature,
                        String applicantName, String status) {}

    public record Decision(String decision, String observation, Map<String, Object> data) {}

    private static final Set<String> OBSERVATION_OBLIGATOIRE = Set.of("COMPLEMENT", "REJETER", "RETOUR");

    private final TaskService taches;
    private final RepositoryService depot;
    private final ManagementService gestion;
    private final RuntimeService moteur;
    private final DossierRepository dossiers;
    private final DossierService dossierService;
    private final Journal journal;
    private final JdbcClient jdbc;
    private final Map<String, List<String>> decisionsCache = new ConcurrentHashMap<>();

    public TacheService(TaskService taches, RepositoryService depot, ManagementService gestion, RuntimeService moteur, DossierRepository dossiers, DossierService dossierService,
                        Journal journal, JdbcClient jdbc) {
        this.taches = taches;
        this.depot = depot;
        this.gestion = gestion;
        this.moteur = moteur;
        this.dossiers = dossiers;
        this.dossierService = dossierService;
        this.journal = journal;
        this.jdbc = jdbc;
    }

    public List<Tache> miennes() {
        return taches.createTaskQuery().taskAssignee(Habilitations.acteur()).active().initializeFormKeys().orderByTaskCreateTime().desc().list()
                .stream().map(this::tache).toList();
    }

    /** Toutes les tâches ouvertes (supervision). */
    public List<Tache> toutes() {
        return taches.createTaskQuery().active().initializeFormKeys().orderByTaskCreateTime().desc().listPage(0, 500).stream().map(this::tache).toList();
    }

    public List<Tache> duDossier(Dossier d) {
        if (d.processInstanceId() == null) return List.of();
        return taches.createTaskQuery().processInstanceId(d.processInstanceId()).active().initializeFormKeys().list().stream().map(this::tache).toList();
    }

    private Tache tache(Task t) {
        String cur = jdbc.sql("select business_key_ from act_ru_execution where id_ = :p").param("p", t.getProcessInstanceId()).query(String.class).optional().orElse(null);
        Dossier d = cur == null ? null : dossiers.parNumero(cur).orElse(null);
        return new Tache(t.getId(), t.getName(), t.getTaskDefinitionKey(), t.getFormKey(), t.getAssignee(),
                t.getCreateTime() == null ? null : t.getCreateTime().toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime(),
                d == null || d.slaDueAt() == null ? null : d.slaDueAt(), decisions(t),
                d == null ? null : d.id(), cur, d == null ? null : d.procedureCode(), d == null ? null : d.requestType(),
                d == null ? null : d.requestNature(), d == null ? null : d.applicantName(), d == null ? null : d.status());
    }

    /** Décisions déclarées dans le BPMN (camunda:property « decisions »). */
    private List<String> decisions(Task t) {
        return decisionsCache.computeIfAbsent(t.getProcessDefinitionId() + "/" + t.getTaskDefinitionKey(), k -> {
            ModelElementInstance e = depot.getBpmnModelInstance(t.getProcessDefinitionId()).getModelElementById(t.getTaskDefinitionKey());
            if (!(e instanceof UserTask u) || u.getExtensionElements() == null) return List.of();
            for (ModelElementInstance x : u.getExtensionElements().getElements())
                if (x.getDomElement().getLocalName().equals("properties"))
                    for (DomElement p : x.getDomElement().getChildElements())
                        if ("decisions".equals(p.getAttribute("name")))
                            return Arrays.stream(p.getAttribute("value").split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            return List.of();
        });
    }

    @Transactional
    public Map<String, Object> completer(String id, Decision dec) {
        Task t = taches.createTaskQuery().taskId(id).initializeFormKeys().singleResult();
        if (t == null) throw EservicesException.introuvable("Tâche introuvable ou déjà traitée.");
        String moi = Habilitations.acteur();
        if (!moi.equals(t.getAssignee())) throw EservicesException.interdit("Cette tâche ne vous est pas assignée (RG2 UC-04).");
        Tache info = tache(t);
        String decision = dec.decision() == null ? "" : dec.decision().strip().toUpperCase(Locale.ROOT);
        if (!info.decisions().isEmpty() && !info.decisions().contains(decision))
            throw EservicesException.invalide("Décision attendue : " + String.join(", ", info.decisions()) + ".");
        String observation = dec.observation() == null ? "" : dec.observation().strip();
        if (OBSERVATION_OBLIGATOIRE.contains(decision) && observation.isEmpty())
            throw EservicesException.invalide("Renseignez l'observation : elle est transmise au destinataire de la décision.");
        String formulaire = Objects.toString(t.getFormKey(), "");
        if (formulaire.endsWith(":traitement") && !Habilitations.utilisateurA(SecurityConfig.TRAITER))
            throw EservicesException.interdit("Le droit « eservices.traiter » est nécessaire.");
        if (formulaire.endsWith(":signature") && !Habilitations.utilisateurA(SecurityConfig.SIGNER))
            throw EservicesException.interdit("Le droit « eservices.signer » est nécessaire.");
        journal.contexte();
        if (formulaire.endsWith(":complement") && dec.data() != null && !dec.data().isEmpty() && info.dossierId() != null)
            dossierService.modifier(info.dossierId(), dec.data());
        Map<String, Object> variables = new HashMap<>();
        variables.put(formulaire.endsWith(":signature") ? "decisionSignataire" : "decision", decision);
        variables.put("decideur", moi);
        variables.put("nomDecideur", Habilitations.nom());
        if (!observation.isEmpty()) variables.put("motifStatut", observation);
        if (info.dossierId() != null && !observation.isEmpty())
            jdbc.sql("update wfe.dossier set last_observation = :o, updated_at = now() where dossier_id = :d").param("o", observation).param("d", info.dossierId()).update();
        journal.tracer("TACHE_TERMINEE", info.dossierId(), info.dossierNo(), Map.of("task", t.getTaskDefinitionKey(), "decision", decision,
                "observation", observation));
        taches.complete(id, variables);
        return Map.of("completed", true, "decision", decision, "dossierNo", Objects.toString(info.dossierNo(), ""));
    }

    // ------------------------------------------------------------------ incidents du moteur (cotation impossible…)
    public List<Map<String, Object>> incidents() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Incident i : moteur.createIncidentQuery().list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId());
            m.put("type", i.getIncidentType());
            m.put("message", i.getIncidentMessage());
            m.put("activityId", i.getActivityId());
            m.put("jobId", i.getConfiguration());
            m.put("processInstanceId", i.getProcessInstanceId());
            m.put("time", i.getIncidentTimestamp());
            m.put("dossierNo", jdbc.sql("select business_key_ from act_ru_execution where id_ = :p").param("p", i.getProcessInstanceId())
                    .query(String.class).optional().orElse(null));
            l.add(m);
        }
        return l;
    }

    /** Relance d'un travail en échec (après correction du paramétrage). */
    public void relancer(String jobId) {
        gestion.setJobRetries(jobId, 1);
        try {
            gestion.executeJob(jobId);
        } catch (RuntimeException e) {
            throw EservicesException.conflit("Nouvel échec : " + Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
        }
    }
}
