package com.nexora.procedures.studio;

import com.nexora.procedures.audit.Journal;
import com.nexora.procedures.studio.Modele.*;
import com.nexora.procedures.web.Json;
import com.nexora.procedures.web.ProceduresException;
import com.nexora.regles.Antecedent;
import com.nexora.regles.Demande;
import com.nexora.regles.MoteurRegles;
import com.nexora.regles.Resultat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.time.Year;
import java.util.*;

/** API du studio : procédures, versions, édition par partie, cycle de vie, contrôles, paquets publiés, terminologie. */
@RestController
@RequestMapping("/api/v1")
public class StudioController {

    private final StudioRepository repo;
    private final VersionService versions;
    private final Journal journal;
    private final JdbcClient jdbc;
    private final Json json;

    public StudioController(StudioRepository repo, VersionService versions, Journal journal, JdbcClient jdbc, Json json) {
        this.repo = repo;
        this.versions = versions;
        this.journal = journal;
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ tableau de bord
    @GetMapping("/dashboard")
    public Map<String, Object> tableauDeBord() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("procedures", jdbc.sql("select count(*) from studio.procedure").query(Long.class).single());
        d.put("versionsByStatus", jdbc.sql("select lifecycle_status::text, count(*) from studio.procedure_version group by 1")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list().stream()
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll));
        d.put("pendingProposals", jdbc.sql("select count(*) from studio.ai_proposal where review_status = 'pending'").query(Long.class).single());
        d.put("aiRuns", jdbc.sql("select count(*) from studio.ai_run").query(Long.class).single());
        d.put("fields", jdbc.sql("""
                select count(*) filter (where f.bie_path is not null), count(*) from studio.form_field f
                  join studio.procedure_version v on v.id = f.version_id where v.lifecycle_status in ('draft', 'in_review', 'approved', 'published')
                """).query((rs, i) -> Map.of("withBie", rs.getLong(1), "total", rs.getLong(2))).single());
        d.put("recent", journal.lire(null, 8));
        return d;
    }

    // ------------------------------------------------------------------ terminologie GUCE
    @GetMapping("/terminology")
    public Map<String, Object> terminologie() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("processes", jdbc.sql("select code, label_fr, label_en, sort_order from studio.trade_process order by sort_order")
                .query((rs, i) -> ligne(rs.getString(1), rs.getString(2), rs.getString(3), "sortOrder", rs.getInt(4))).list());
        t.put("phases", jdbc.sql("select code, label_fr, label_en, process_code, bsp_area, sort_order from studio.process_phase order by sort_order")
                .query((rs, i) -> ligne(rs.getString(1), rs.getString(2), rs.getString(3), "processCode", rs.getString(4), "bspArea",
                        rs.getString(5), "sortOrder", rs.getInt(6))).list());
        t.put("circuits", jdbc.sql("select code, label_fr, label_en, phase_code, shared_requirements, sort_order from studio.circuit order by sort_order")
                .query((rs, i) -> ligne(rs.getString(1), rs.getString(2), rs.getString(3), "phaseCode", rs.getString(4),
                        "sharedRequirements", rs.getString(5), "sortOrder", rs.getInt(6))).list());
        t.put("natures", jdbc.sql("select code, label_fr, label_en, description, sort_order from studio.request_nature order by sort_order")
                .query((rs, i) -> ligne(rs.getString(1), rs.getString(2), rs.getString(3), "description", rs.getString(4), "sortOrder", rs.getInt(5))).list());
        return t;
    }

    public record Circuit(String code, String labelFr, String labelEn, String phaseCode, String sharedRequirements, int sortOrder) {}

    /** Ajout ou mise à jour d'un circuit (les processus, phases et natures sont livrés avec la plateforme). */
    @PutMapping("/terminology/circuits")
    public Map<String, Object> circuit(@RequestBody Circuit c) {
        if (c.code() == null || !c.code().matches("^[A-Z][A-Z0-9_]{1,29}$")) throw ProceduresException.invalide("Code de circuit invalide.");
        jdbc.sql("""
                insert into studio.circuit (code, phase_code, label_fr, label_en, shared_requirements, sort_order) values (:c, :p, :f, :e, :s, :o)
                on conflict (code) do update set phase_code = excluded.phase_code, label_fr = excluded.label_fr, label_en = excluded.label_en,
                  shared_requirements = excluded.shared_requirements, sort_order = excluded.sort_order
                """).param("c", c.code()).param("p", c.phaseCode()).param("f", c.labelFr()).param("e", c.labelEn())
                .param("s", c.sharedRequirements()).param("o", c.sortOrder()).update();
        journal.tracer("CIRCUIT_ENREGISTRE", null, null, Map.of("code", c.code()));
        return terminologie();
    }

    private static Map<String, Object> ligne(String code, String fr, String en, Object... autres) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("labelFr", fr);
        m.put("labelEn", en);
        for (int i = 0; i + 1 < autres.length; i += 2) m.put((String) autres[i], autres[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------ procédures
    @GetMapping("/procedures")
    public List<ProcedureResume> procedures() {
        return repo.procedures();
    }

    /** {@code model} : GABARIT (défaut), VIDE ou code d'une procédure existante à copier ; {@code curCode} : code CUR à 3 lettres. */
    public record NouvelleProcedure(String code, String labelFr, String labelEn, String circuitCode, String authority, String description,
                                    Map<String, Object> settings, String model, String curCode) {}

    @PostMapping("/procedures")
    public VersionComplete creer(@RequestBody NouvelleProcedure n) {
        return versions.creerProcedure(new Procedure(n.code() == null ? null : n.code().strip().toUpperCase(), n.labelFr(), n.labelEn(),
                n.circuitCode(), n.authority(), n.description(), true, null, null), n.settings(), n.model(), n.curCode());
    }

    @GetMapping("/procedures/{code}")
    public Map<String, Object> procedure(@PathVariable String code) {
        Procedure p = repo.procedure(code).orElseThrow(() -> ProceduresException.introuvable("Procédure introuvable : " + code));
        return Map.of("procedure", p, "versions", repo.versions(code));
    }

    @PutMapping("/procedures/{code}")
    public Procedure modifierProcedure(@PathVariable String code, @RequestBody Procedure p) {
        return versions.modifierProcedure(code, p);
    }

    @PostMapping("/procedures/{code}/versions")
    public VersionComplete nouvelleVersion(@PathVariable String code, @RequestBody(required = false) Map<String, String> corps) {
        return versions.nouvelleVersion(code, corps == null ? null : corps.get("changeNote"));
    }

    // ------------------------------------------------------------------ versions
    @GetMapping("/versions/{id}")
    public VersionComplete version(@PathVariable long id) {
        return versions.complete(id);
    }

    @PutMapping("/versions/{id}/parts/{partie}")
    public VersionComplete modifier(@PathVariable long id, @PathVariable String partie, @RequestBody Object corps) {
        return versions.modifier(id, partie, corps);
    }

    @GetMapping("/versions/{id}/checks")
    public List<Constat> controles(@PathVariable long id) {
        return versions.controler(id);
    }

    @PostMapping("/versions/{id}/submit")
    public VersionComplete soumettre(@PathVariable long id) {
        return versions.soumettre(id);
    }

    @PostMapping("/versions/{id}/approve")
    public VersionComplete approuver(@PathVariable long id) {
        return versions.approuver(id);
    }

    @PostMapping("/versions/{id}/reject")
    public VersionComplete renvoyer(@PathVariable long id, @RequestBody(required = false) Map<String, String> corps) {
        return versions.renvoyer(id, corps == null ? null : corps.get("comment"));
    }

    @PostMapping("/versions/{id}/publish")
    public VersionComplete publier(@PathVariable long id) {
        return versions.publier(id);
    }

    @PostMapping("/versions/{id}/retire")
    public VersionComplete retirer(@PathVariable long id) {
        return versions.retirer(id);
    }

    /** Aperçu du paquet (version non publiée) ou paquet figé (version publiée). */
    @GetMapping(value = "/versions/{id}/package", produces = MediaType.APPLICATION_JSON_VALUE)
    public String paquet(@PathVariable long id) {
        return repo.paquet(id).orElseGet(() -> json.ecrire(versions.paquet(versions.complete(id), "(aperçu)")));
    }

    /** Formulaire de la version en code Angular (composant pour e-Services), quel que soit le réglage « rendu ». */
    @GetMapping("/versions/{id}/angular")
    public Map<String, Object> angular(@PathVariable long id) {
        return codeAngular(versions.complete(id));
    }

    /**
     * Code Angular d'une version donnée d'une procédure, pour l'action « Préparer le code généré » d'e-Services. Refusé
     * pour une version qui n'est ni publiée ni retirée, ou dont le rendu n'est pas GENERE.
     */
    @GetMapping("/angular/{code}/{versionNo}")
    public Map<String, Object> angularPublie(@PathVariable String code, @PathVariable int versionNo) {
        Version v = repo.versions(code).stream().filter(x -> x.versionNo() == versionNo).findFirst()
                .orElseThrow(() -> ProceduresException.introuvable("Version introuvable : " + code + " v" + versionNo + "."));
        if (!List.of("published", "superseded", "retired").contains(v.lifecycleStatus()))
            throw ProceduresException.conflit("La version " + code + " v" + versionNo + " n'est pas publiée (statut : " + v.lifecycleStatus() + ").");
        if (!"GENERE".equals(GenerateurAngular.rendu(v.settings())))
            throw ProceduresException.conflit("La procédure " + code + " v" + versionNo + " est en rendu dynamique : aucun code Angular à intégrer.");
        return codeAngular(repo.complete(v.id()));
    }

    private Map<String, Object> codeAngular(VersionComplete v) {
        GenerateurAngular.Resultat r = GenerateurAngular.generer(v);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", GenerateurAngular.FORMAT);
        m.put("procedure", r.procedure());
        m.put("versionNo", r.versionNo());
        m.put("rendu", GenerateurAngular.rendu(v.version().settings()));
        m.put("component", r.composant());
        m.put("selector", r.selecteur());
        m.put("files", r.fichiers().stream().map(f -> Map.of("path", f.chemin(), "content", f.contenu())).toList());
        return m;
    }

    /** Export du BPMN pour Camunda Modeler. */
    @GetMapping(value = "/versions/{id}/bpmn", produces = "application/xml")
    public ResponseEntity<String> bpmn(@PathVariable long id) {
        VersionComplete v = versions.complete(id);
        if (v.process() == null) throw ProceduresException.introuvable("Aucun workflow BPMN pour cette version.");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + v.process().processKey() + "-v"
                + v.version().versionNo() + ".bpmn\"").body(v.process().bpmnXml());
    }

    @GetMapping(value = "/versions/{id}/drl", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> drl(@PathVariable long id) {
        VersionComplete v = versions.complete(id);
        if (v.rules() == null) throw ProceduresException.introuvable("Aucune règle DRL pour cette version.");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + v.procedure().code().toLowerCase()
                + "-v" + v.version().versionNo() + ".drl\"").body(v.rules().drl());
    }

    @GetMapping("/versions/{id}/journal")
    public List<Journal.Evenement> journalVersion(@PathVariable long id) {
        return journal.lire(id, 200);
    }

    @GetMapping("/journal")
    public List<Journal.Evenement> journal(@RequestParam(defaultValue = "200") int limit) {
        return journal.lire(null, Math.min(limit, 1000));
    }

    // ------------------------------------------------------------------ test des règles (Drools)
    public record TestRegles(String drl, String point, String typeDemande, String nature, Map<String, Object> champs,
                             Map<String, List<Map<String, Object>>> groupes, List<String> pieces, boolean mandataire, boolean titulaire,
                             List<Antecedent> antecedents) {}

    /** Compilation seule (Drools) du DRL soumis, ou à défaut du DRL enregistré : diagnostics, règles déclarées, empreinte. */
    @PostMapping("/versions/{id}/rules/compile")
    public Map<String, Object> compilerRegles(@PathVariable long id, @RequestBody(required = false) Map<String, String> corps) {
        VersionComplete v = versions.complete(id);
        String drl = corps != null && corps.get("drl") != null && !corps.get("drl").isBlank() ? corps.get("drl")
                : v.rules() == null ? "" : v.rules().drl();
        List<String> erreurs = drl.isBlank() ? List.of("Le DRL est vide.") : MoteurRegles.verifier(drl);
        List<String> regles = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^\\s*rule\\s+\"([^\"]+)\"").matcher(drl);
        while (m.find()) regles.add(m.group(1));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("compiled", erreurs.isEmpty());
        r.put("errors", erreurs);
        r.put("rules", regles);
        r.put("hash", VersionService.sha256(drl));
        r.put("saved", v.rules() != null && drl.equals(v.rules().drl()));
        return r;
    }

    /** État d'approbation des règles DRL (valable tant que le DRL n'a pas changé). */
    @GetMapping("/versions/{id}/rules/approval")
    public Map<String, Object> approbationRegles(@PathVariable long id) {
        RuleSet r = versions.complete(id).rules();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("approved", r != null && r.approuvees());
        m.put("approvedBy", r == null ? null : r.approvedBy());
        m.put("approvedAt", r == null ? null : r.approvedAt());
        m.put("stale", r != null && r.approvedHash() != null && !r.approuvees());
        m.put("updatedBy", r == null ? null : r.updatedBy());
        m.put("updatedAt", r == null ? null : r.updatedAt());
        return m;
    }

    @PostMapping("/versions/{id}/rules/approve")
    public Map<String, Object> approuverRegles(@PathVariable long id, @RequestBody(required = false) Map<String, String> corps) {
        versions.approuverRegles(id, corps == null ? null : corps.get("note"));
        return approbationRegles(id);
    }

    @PostMapping("/versions/{id}/rules/test")
    public Map<String, Object> testerRegles(@PathVariable long id, @RequestBody TestRegles t) {
        VersionComplete v = versions.complete(id);
        String drl = t.drl() != null && !t.drl().isBlank() ? t.drl() : v.rules() == null ? "" : v.rules().drl();
        List<String> erreurs = MoteurRegles.verifier(drl);
        if (!erreurs.isEmpty()) return Map.of("compiled", false, "errors", erreurs);
        Demande d = new Demande(v.procedure().code(), t.typeDemande(), t.nature() == null ? "INITIAL" : t.nature(), Year.now().getValue());
        d.setChamps(t.champs() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(t.champs()));
        d.setGroupes(t.groupes() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(t.groupes()));
        d.setPieces(t.pieces() == null ? new LinkedHashSet<>() : new LinkedHashSet<>(t.pieces()));
        d.setMandataire(t.mandataire());
        d.setTitulaire(t.titulaire());
        d.setDemandeur(Journal.utilisateur());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("compiled", true);
        for (String point : t.point() == null ? MoteurRegles.POINTS : List.of(t.point())) {
            Resultat res = MoteurRegles.evaluer("test:" + VersionService.sha256(drl), drl, point, d,
                    t.antecedents() == null ? List.of() : t.antecedents());
            r.put(point, Map.of("errors", res.getErreurs(), "fees", res.getFrais(), "total", res.total(), "variables", res.getVariables(),
                    "firedRules", res.getReglesDeclenchees()));
        }
        return r;
    }

    // ------------------------------------------------------------------ paquets publiés (consommés par le Runtime)
    @GetMapping("/published")
    public List<Map<String, Object>> publiees() {
        return jdbc.sql("""
                select p.code, p.label_fr, p.label_en, v.id, v.version_no, v.published_at, v.published_by, v.package_hash
                  from studio.procedure_version v join studio.procedure p on p.code = v.procedure_code
                 where v.lifecycle_status = 'published' order by p.code
                """).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", rs.getString(1));
            m.put("labelFr", rs.getString(2));
            m.put("labelEn", rs.getString(3));
            m.put("versionId", rs.getLong(4));
            m.put("versionNo", rs.getInt(5));
            m.put("publishedAt", rs.getObject(6, java.time.OffsetDateTime.class));
            m.put("publishedBy", rs.getString(7));
            m.put("packageHash", rs.getString(8));
            return m;
        }).list();
    }

    @GetMapping(value = "/published/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> paquetPublie(@PathVariable String code) {
        String p = repo.paquetPublie(code).orElseThrow(() -> ProceduresException.introuvable("Aucune version publiée pour la procédure " + code + "."));
        return ResponseEntity.ok().header("X-Nexora-Package-Hash", VersionService.sha256(p)).body(p);
    }
}
