package com.nexora.eservices.deploiement;

import com.nexora.eservices.audit.Journal;
import com.nexora.eservices.registre.Services;
import com.nexora.eservices.security.Habilitations;
import com.nexora.eservices.web.EservicesException;
import com.nexora.eservices.web.Json;
import com.nexora.regles.MoteurRegles;
import org.operaton.bpm.engine.RepositoryService;
import org.operaton.bpm.engine.repository.Deployment;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Déploiement des procédures publiées par le studio : le paquet est téléchargé, son empreinte SHA-256 vérifiée, son
 * workflow BPMN déployé dans le moteur Operaton, ses règles DRL compilées (Drools) et ses vues métier créées. Une
 * nouvelle version devient active pour les nouveaux dossiers ; les dossiers en cours gardent la leur (RG-TRX-01).
 */
@Service
public class DeploiementService {

    public record Deploiement(long id, String procedureCode, int versionNo, String labelFr, String labelEn, String packageHash, String processKey,
                              String processDefinitionId, boolean active, OffsetDateTime deployedAt, String deployedBy) {}

    private final JdbcClient jdbc;
    private final Json json;
    private final Services services;
    private final RepositoryService depot;
    private final VuesMetier vues;
    private final ListesService listes;
    private final Journal journal;
    private final Map<Long, Paquet> cache = new ConcurrentHashMap<>();

    public DeploiementService(JdbcClient jdbc, Json json, Services services, RepositoryService depot, VuesMetier vues, ListesService listes,
                              Journal journal) {
        this.jdbc = jdbc;
        this.json = json;
        this.services = services;
        this.depot = depot;
        this.vues = vues;
        this.listes = listes;
        this.journal = journal;
    }

    private static final String COLONNES = """
            deployment_id, procedure_code, version_no, label_fr, label_en, package_hash, process_key, process_definition_id, is_active,
            deployed_at, deployed_by
            """;

    private static Deploiement ligne(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Deploiement(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getBoolean(9), rs.getObject(10, OffsetDateTime.class), rs.getString(11));
    }

    public List<Deploiement> deploiements() {
        return jdbc.sql("select " + COLONNES + " from wfe.procedure_deployment order by procedure_code, version_no desc").query((rs, i) -> ligne(rs)).list();
    }

    public List<Deploiement> actifs() {
        return jdbc.sql("select " + COLONNES + " from wfe.procedure_deployment where is_active order by procedure_code").query((rs, i) -> ligne(rs)).list();
    }

    public Deploiement actif(String procedure) {
        return jdbc.sql("select " + COLONNES + " from wfe.procedure_deployment where is_active and procedure_code = :p").param("p", procedure)
                .query((rs, i) -> ligne(rs)).optional()
                .orElseThrow(() -> EservicesException.introuvable("La procédure " + procedure + " n'est pas déployée dans les e-Services."));
    }

    public Deploiement deploiement(long id) {
        return jdbc.sql("select " + COLONNES + " from wfe.procedure_deployment where deployment_id = :i").param("i", id).query((rs, i) -> ligne(rs))
                .optional().orElseThrow(() -> EservicesException.introuvable("Déploiement introuvable : " + id));
    }

    /** Paquet d'un déploiement (mis en cache : il est immuable). */
    public Paquet paquet(long deploymentId) {
        return cache.computeIfAbsent(deploymentId, id -> json.mapper().convertValue(json.map(jdbc.sql(
                "select package::text from wfe.procedure_deployment where deployment_id = :i").param("i", id).query(String.class).single()), Paquet.class));
    }

    /** Procédures publiées dans le studio, avec leur état de déploiement local. */
    public List<Map<String, Object>> publiees() {
        Map<String, Deploiement> locaux = new HashMap<>();
        actifs().forEach(d -> locaux.put(d.procedureCode(), d));
        List<Map<String, Object>> r = new ArrayList<>();
        for (Map<String, Object> p : services.publiees()) {
            Map<String, Object> m = new LinkedHashMap<>(p);
            Deploiement d = locaux.get(String.valueOf(p.get("code")));
            m.put("deployedVersionNo", d == null ? null : d.versionNo());
            m.put("upToDate", d != null && Objects.equals(d.packageHash(), p.get("packageHash")));
            r.add(m);
        }
        return r;
    }

    @Transactional
    public Deploiement deployer(String code) {
        journal.contexte();
        var reponse = services.paquetPublie(code);
        String contenu = reponse.getKey();
        String empreinte = sha256(contenu);
        if (!reponse.getValue().isBlank() && !reponse.getValue().equalsIgnoreCase(empreinte))
            throw EservicesException.conflit("Empreinte du paquet invalide : le contenu reçu ne correspond pas à l'empreinte publiée par le studio.");
        Paquet p = json.mapper().convertValue(json.map(contenu), Paquet.class);
        if (!"nexora.procedure/1".equals(p.format())) throw EservicesException.invalide("Format de paquet non pris en charge : " + p.format());
        Optional<Deploiement> existant = jdbc.sql("select " + COLONNES + " from wfe.procedure_deployment where procedure_code = :p and version_no = :v")
                .param("p", p.procedure().code()).param("v", p.versionNo()).query((rs, i) -> ligne(rs)).optional();
        if (existant.isPresent()) {
            if (!existant.get().active()) {
                jdbc.sql("update wfe.procedure_deployment set is_active = false where procedure_code = :p and is_active").param("p", p.procedure().code()).update();
                jdbc.sql("update wfe.procedure_deployment set is_active = true where deployment_id = :i").param("i", existant.get().id()).update();
                journal.tracer("PROCEDURE_REACTIVEE", null, null, Map.of("procedure", code, "versionNo", p.versionNo()));
            }
            return deploiement(existant.get().id());
        }
        List<String> erreurs = MoteurRegles.verifier(p.rules().drl());
        if (!erreurs.isEmpty()) throw EservicesException.invalide(erreurs.stream().map(e -> "DRL : " + e).toList());

        Deployment d = depot.createDeployment().name(p.procedure().code() + " v" + p.versionNo()).source("NEXORA Studio")
                .addString(p.process().processKey() + ".bpmn", p.process().bpmnXml()).deploy();
        ProcessDefinition def = depot.createProcessDefinitionQuery().deploymentId(d.getId()).singleResult();
        if (def == null) throw EservicesException.invalide("Le BPMN déployé ne contient aucun processus exécutable.");

        jdbc.sql("update wfe.procedure_deployment set is_active = false where procedure_code = :p and is_active").param("p", p.procedure().code()).update();
        long id = jdbc.sql("""
                insert into wfe.procedure_deployment (procedure_code, version_no, label_fr, label_en, package, package_hash, process_key,
                  process_definition_id, engine_deployment_id, deployed_by)
                values (:c, :v, :f, :e, cast(:p as jsonb), :h, :k, :d, :ed, :u) returning deployment_id
                """).param("c", p.procedure().code()).param("v", p.versionNo()).param("f", p.procedure().labelFr()).param("e", p.procedure().labelEn())
                .param("p", contenu).param("h", empreinte).param("k", p.process().processKey()).param("d", def.getId()).param("ed", d.getId())
                .param("u", Habilitations.acteur()).query(Long.class).single();
        List<String> creees = vues.creer(p);
        Map<String, String> echecs = listes.charger(p);
        journal.tracer("PROCEDURE_DEPLOYEE", null, null, Map.of("procedure", code, "versionNo", p.versionNo(), "hash", empreinte,
                "processDefinition", def.getId(), "views", creees, "codeListFailures", echecs));
        return deploiement(id);
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
