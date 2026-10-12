package com.nexora.procedures.studio;

import com.nexora.procedures.agents.Conformite;
import com.nexora.procedures.audit.Journal;
import com.nexora.procedures.studio.Modele.*;
import com.nexora.procedures.web.Json;
import com.nexora.procedures.web.ProceduresException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Cycle de vie des versions de procédure (draft → in_review → approved → published → superseded / retired) et
 * édition par partie d'une version brouillon. La publication fige un paquet JSON (empreinte SHA-256) que le Runtime
 * déploie : une version publiée n'est plus jamais modifiée (RG-GOV-01) ; l'auteur n'en est pas l'approbateur (RG-GOV-04).
 */
@Service
public class VersionService {

    public static final List<String> PARTIES = List.of("settings", "requestTypes", "actors", "statuses", "form", "documents", "fees",
            "messages", "flows", "process", "rules", "deliverables");

    private final StudioRepository repo;
    private final Conformite conformite;
    private final Journal journal;
    private final Json json;
    private final JdbcClient jdbc;

    public VersionService(StudioRepository repo, Conformite conformite, Journal journal, Json json, JdbcClient jdbc) {
        this.repo = repo;
        this.conformite = conformite;
        this.journal = journal;
        this.json = json;
        this.jdbc = jdbc;
    }

    public VersionComplete complete(long id) {
        repo.version(id).orElseThrow(() -> ProceduresException.introuvable("Version introuvable : " + id));
        return repo.complete(id);
    }

    // ------------------------------------------------------------------ procédures
    /**
     * Nouvelle procédure. {@code modele} : « GABARIT » (socle standard du guichet unique, par défaut), « VIDE », ou le code
     * d'une procédure existante dont la dernière version est copiée (ex. partir de FIMEX pour une procédure voisine).
     */
    @Transactional
    public VersionComplete creerProcedure(Procedure p, Map<String, Object> settings, String modele, String curCode) {
        List<String> erreurs = new ArrayList<>();
        if (p.code() == null || !p.code().matches("^[A-Z][A-Z0-9_]{1,29}$")) erreurs.add("Code de procédure invalide (majuscules, chiffres, _).");
        if (p.labelFr() == null || p.labelFr().isBlank()) erreurs.add("Le libellé est obligatoire.");
        if (p.circuitCode() == null || p.circuitCode().isBlank()) erreurs.add("Le circuit GUCE est obligatoire (RG-TERM-01).");
        if (!erreurs.isEmpty()) throw ProceduresException.invalide(erreurs);
        if (repo.procedure(p.code()).isPresent()) throw ProceduresException.conflit("La procédure " + p.code() + " existe déjà.");
        String auteur = Journal.utilisateur();
        repo.creerProcedure(p, auteur);
        Map<String, Object> s = new LinkedHashMap<>(Map.of("country", "CM", "currency", "XAF", "timezone", "Africa/Douala", "paymentDelay", "P30D",
                "slaHours", 48, "cotation", "ALEATOIRE_EQUITABLE"));
        if (settings != null) s.putAll(settings);
        String m = modele == null || modele.isBlank() ? "GABARIT" : modele.strip().toUpperCase();
        VersionComplete source = null;
        if (!m.equals("GABARIT") && !m.equals("VIDE")) {
            List<Version> vs = repo.versions(m);
            if (vs.isEmpty()) throw ProceduresException.introuvable("Procédure modèle introuvable : " + m);
            source = repo.complete(vs.getFirst().id());
            s.putAll(source.version().settings());
            if (settings != null) s.putAll(settings);
        }
        long id = repo.creerVersion(p.code(), 1, s, source == null ? "Version initiale" : "Version initiale, copiée de " + m + " v" + source.version().versionNo(), auteur);
        if (source != null) {
            repo.copier(source, id, auteur);
            // Clé de processus propre à la nouvelle procédure (le BPMN copié est renommé)
            if (source.process() != null) {
                String cle = p.code().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
                String xml = source.process().bpmnXml().replace("id=\"" + source.process().processKey() + "\"", "id=\"" + cle + "\"")
                        .replace("bpmnElement=\"" + source.process().processKey() + "\"", "bpmnElement=\"" + cle + "\"");
                repo.processus(id, new ProcessModel(cle, xml, null, null), auteur);
            }
        } else if (m.equals("GABARIT")) {
            Gabarit.appliquer(repo, id, p, curCode, auteur);
        }
        journal.tracer("PROCEDURE_CREEE", p.code(), id, Map.of("label", p.labelFr(), "circuit", p.circuitCode(), "model", m));
        return repo.complete(id);
    }

    @Transactional
    public Procedure modifierProcedure(String code, Procedure p) {
        Procedure actuelle = repo.procedure(code).orElseThrow(() -> ProceduresException.introuvable("Procédure introuvable : " + code));
        Procedure n = new Procedure(code, p.labelFr() == null ? actuelle.labelFr() : p.labelFr(), p.labelEn(),
                p.circuitCode() == null ? actuelle.circuitCode() : p.circuitCode(), p.authority(), p.description(), p.active(),
                actuelle.createdAt(), actuelle.createdBy());
        repo.modifierProcedure(n);
        journal.tracer("PROCEDURE_MODIFIEE", code, null, Map.of("label", n.labelFr(), "circuit", n.circuitCode()));
        return n;
    }

    /** Nouvelle version de travail, copie intégrale de la dernière version. */
    @Transactional
    public VersionComplete nouvelleVersion(String procedure, String note) {
        List<Version> versions = repo.versions(procedure);
        if (versions.isEmpty()) throw ProceduresException.introuvable("Procédure sans version : " + procedure);
        if (versions.stream().anyMatch(v -> List.of("draft", "in_review", "approved").contains(v.lifecycleStatus())))
            throw ProceduresException.conflit("Une version de travail existe déjà pour " + procedure + " : terminez-la avant d'en créer une autre.");
        Version derniere = versions.getFirst();
        VersionComplete source = repo.complete(derniere.id());
        String auteur = Journal.utilisateur();
        long id = repo.creerVersion(procedure, derniere.versionNo() + 1, derniere.settings(), note == null ? "Évolution de la version " + derniere.versionNo() : note, auteur);
        repo.copier(source, id, auteur);
        journal.tracer("VERSION_CREEE", procedure, id, Map.of("from", derniere.versionNo(), "versionNo", derniere.versionNo() + 1));
        return repo.complete(id);
    }

    // ------------------------------------------------------------------ édition par partie (brouillon uniquement)
    @Transactional
    public VersionComplete modifier(long id, String partie, Object corps) {
        Version v = brouillon(id);
        String auteur = Journal.utilisateur();
        switch (partie) {
            case "settings" -> repo.parametres(id, json.mapper().convertValue(corps, Parametres.class), auteur);
            case "requestTypes" -> repo.typesDemande(id, liste(corps, RequestType.class));
            case "actors" -> repo.acteurs(id, liste(corps, Actor.class));
            case "statuses" -> repo.statuts(id, liste(corps, CaseStatus.class));
            case "form" -> {
                Formulaire f = json.mapper().convertValue(corps, Formulaire.class);
                repo.formulaire(id, f.sections() == null ? List.of() : f.sections(), f.fields() == null ? List.of() : f.fields());
            }
            case "documents" -> repo.pieces(id, liste(corps, RequiredDocument.class));
            case "fees" -> repo.frais(id, liste(corps, FeeItem.class));
            case "messages" -> repo.messages(id, liste(corps, MessageDef.class));
            case "flows" -> repo.flux(id, liste(corps, Flow.class));
            case "process" -> repo.processus(id, json.mapper().convertValue(corps, ProcessModel.class), auteur);
            case "rules" -> repo.regles(id, json.mapper().convertValue(corps, RuleSet.class), auteur);
            case "deliverables" -> repo.livrables(id, liste(corps, Deliverable.class));
            default -> throw ProceduresException.introuvable("Partie inconnue : " + partie + " (attendu : " + PARTIES + ").");
        }
        repo.toucher(id, auteur);
        journal.tracer("VERSION_MODIFIEE", v.procedureCode(), id, Map.of("part", partie));
        return repo.complete(id);
    }

    public record Formulaire(List<Section> sections, List<Field> fields) {}

    private <T> List<T> liste(Object corps, Class<T> type) {
        if (!(corps instanceof List<?> l)) throw ProceduresException.invalide("Une liste est attendue.");
        return l.stream().map(x -> json.mapper().convertValue(x, type)).toList();
    }

    private Version brouillon(long id) {
        Version v = repo.version(id).orElseThrow(() -> ProceduresException.introuvable("Version introuvable : " + id));
        if (!v.lifecycleStatus().equals("draft"))
            throw ProceduresException.conflit("Seule une version brouillon est modifiable (statut actuel : " + v.lifecycleStatus() + ").");
        return v;
    }

    // ------------------------------------------------------------------ cycle de vie
    public List<Constat> controler(long id) {
        return conformite.controler(complete(id));
    }

    @Transactional
    public VersionComplete soumettre(long id) {
        Version v = brouillon(id);
        List<Constat> constats = conformite.controler(repo.complete(id));
        if (Conformite.bloquant(constats))
            throw ProceduresException.invalide(constats.stream().filter(c -> c.severity().equals("error")).map(c -> c.rule() + " — " + c.message()).toList());
        transition(id, "draft", "in_review", "submitted_by");
        journal.tracer("VERSION_SOUMISE", v.procedureCode(), id, Map.of("warnings", constats.stream().filter(c -> c.severity().equals("warning")).count()));
        return repo.complete(id);
    }

    @Transactional
    public VersionComplete approuver(long id) {
        Version v = repo.version(id).orElseThrow(() -> ProceduresException.introuvable("Version introuvable : " + id));
        String moi = Journal.utilisateur();
        if (moi.equals(v.submittedBy()))
            throw ProceduresException.conflit("Séparation des tâches (RG-GOV-04) : l'auteur de la soumission ne peut pas approuver sa propre version.");
        transition(id, "in_review", "approved", "approved_by");
        journal.tracer("VERSION_APPROUVEE", v.procedureCode(), id, Map.of("submittedBy", Objects.toString(v.submittedBy(), "")));
        return repo.complete(id);
    }

    @Transactional
    public VersionComplete renvoyer(long id, String commentaire) {
        Version v = repo.version(id).orElseThrow(() -> ProceduresException.introuvable("Version introuvable : " + id));
        transition(id, "in_review", "draft", null);
        jdbc.sql("update studio.procedure_version set submitted_at = null, submitted_by = null where id = :id").param("id", id).update();
        journal.tracer("VERSION_RENVOYEE", v.procedureCode(), id, Map.of("comment", Objects.toString(commentaire, "")));
        return repo.complete(id);
    }

    @Transactional
    public VersionComplete publier(long id) {
        Version v = repo.version(id).orElseThrow(() -> ProceduresException.introuvable("Version introuvable : " + id));
        if (!v.lifecycleStatus().equals("approved")) throw ProceduresException.conflit("Seule une version approuvée peut être publiée.");
        VersionComplete complete = repo.complete(id);
        List<Constat> constats = conformite.controler(complete);
        if (Conformite.bloquant(constats))
            throw ProceduresException.invalide(constats.stream().filter(c -> c.severity().equals("error")).map(c -> c.rule() + " — " + c.message()).toList());
        String auteur = Journal.utilisateur();
        Map<String, Object> paquet = paquet(complete, auteur);
        String empreinte = repo.publier(id, json.ecrire(paquet), auteur);
        journal.tracer("VERSION_PUBLIEE", v.procedureCode(), id, Map.of("hash", empreinte, "versionNo", v.versionNo()));
        return repo.complete(id);
    }

    @Transactional
    public VersionComplete retirer(long id) {
        Version v = repo.version(id).orElseThrow(() -> ProceduresException.introuvable("Version introuvable : " + id));
        transition(id, "published", "retired", null);
        jdbc.sql("update studio.procedure_version set retired_at = now() where id = :id").param("id", id).update();
        journal.tracer("VERSION_RETIREE", v.procedureCode(), id, Map.of());
        return repo.complete(id);
    }

    private void transition(long id, String de, String vers, String colonne) {
        if (repo.statut(id, de, vers, colonne, Journal.utilisateur()) == 0)
            throw ProceduresException.conflit("Transition impossible : la version n'est pas au statut « " + de + " ».");
    }

    // ------------------------------------------------------------------ paquet publié
    /**
     * Paquet de procédure (format nexora.procedure/1) : tout ce dont le Runtime a besoin pour exécuter la procédure,
     * sans dépendre du studio — terminologie, types de demande, acteurs, statuts, formulaire, pièces, frais,
     * catalogue de messages, flux, BPMN, DRL, livrables et paramètres.
     */
    public Map<String, Object> paquet(VersionComplete v, String publiePar) {
        Map<String, Object> terminologie = jdbc.sql("""
                select c.code, c.label_fr, c.label_en, ph.code, ph.label_fr, ph.bsp_area, tp.code, tp.label_fr
                  from studio.circuit c join studio.process_phase ph on ph.code = c.phase_code join studio.trade_process tp on tp.code = ph.process_code
                 where c.code = :c
                """).param("c", v.procedure().circuitCode()).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("circuit", Map.of("code", rs.getString(1), "labelFr", rs.getString(2), "labelEn", Objects.toString(rs.getString(3), "")));
            m.put("phase", Map.of("code", rs.getString(4), "labelFr", rs.getString(5), "bspArea", Objects.toString(rs.getString(6), "")));
            m.put("process", Map.of("code", rs.getString(7), "labelFr", rs.getString(8)));
            return m;
        }).single();
        List<Map<String, Object>> natures = jdbc.sql("select code, label_fr, label_en from studio.request_nature order by sort_order")
                .query((rs, i) -> Map.<String, Object>of("code", rs.getString(1), "labelFr", rs.getString(2), "labelEn", Objects.toString(rs.getString(3), ""))).list();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("format", "nexora.procedure/1");
        p.put("procedure", v.procedure());
        p.put("terminology", terminologie);
        p.put("version", Map.of("id", v.version().id(), "versionNo", v.version().versionNo(), "publishedAt", OffsetDateTime.now().toString(),
                "publishedBy", publiePar, "createdBy", v.version().createdBy(), "approvedBy", Objects.toString(v.version().approvedBy(), ""),
                "changeNote", Objects.toString(v.version().changeNote(), "")));
        p.put("settings", v.version().settings());
        p.put("natures", natures);
        p.put("requestTypes", v.requestTypes());
        p.put("actors", v.actors());
        p.put("statuses", v.statuses());
        p.put("sections", v.sections());
        p.put("fields", v.fields());
        p.put("documents", v.documents());
        p.put("fees", v.fees());
        p.put("messages", v.messages());
        p.put("flows", v.flows());
        p.put("process", Map.of("processKey", v.process().processKey(), "bpmnXml", v.process().bpmnXml()));
        p.put("rules", Map.of("drl", v.rules().drl(), "businessRules", v.rules().businessRules()));
        p.put("deliverables", v.deliverables());
        return p;
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
