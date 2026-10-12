package com.nexora.eservices.deploiement;

import com.nexora.eservices.audit.Journal;
import com.nexora.eservices.cotation.CotationService;
import com.nexora.eservices.message.MessageService;
import com.nexora.eservices.moteur.TacheService;
import com.nexora.eservices.paiement.PaiementService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Administration (déploiement des procédures publiées, paramétrage de la cotation, incidents) et supervision. */
@RestController
@RequestMapping("/api/v1")
public class AdministrationController {

    private final DeploiementService deploiements;
    private final ListesService listes;
    private final CotationService cotation;
    private final TacheService taches;
    private final PaiementService paiements;
    private final MessageService messages;
    private final Journal journal;
    private final JdbcClient jdbc;

    public AdministrationController(DeploiementService deploiements, ListesService listes, CotationService cotation, TacheService taches, PaiementService paiements,
                                    MessageService messages, Journal journal, JdbcClient jdbc) {
        this.deploiements = deploiements;
        this.listes = listes;
        this.cotation = cotation;
        this.taches = taches;
        this.paiements = paiements;
        this.messages = messages;
        this.journal = journal;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ déploiements
    @GetMapping("/admin/published")
    public List<Map<String, Object>> publiees() {
        return deploiements.publiees();
    }

    @GetMapping("/admin/deployments")
    public List<DeploiementService.Deploiement> deploiements() {
        return deploiements.deploiements();
    }

    @PostMapping("/admin/deployments")
    public DeploiementService.Deploiement deployer(@RequestBody Map<String, String> corps) {
        return deploiements.deployer(corps.get("code"));
    }

    // ------------------------------------------------------------------ listes de codes (projection du Référentiel Commun)
    @GetMapping("/admin/code-lists")
    public List<ListesService.Liste> listes() {
        return listes.listes();
    }

    @PostMapping("/admin/code-lists/refresh")
    public Map<String, Object> rafraichir(@RequestBody Map<String, String> corps) {
        Paquet p = deploiements.paquet(deploiements.actif(corps.get("code")).id());
        return Map.of("failures", listes.charger(p), "lists", listes.listes());
    }

    /** Valeurs d'une liste projetée (formulaires eFORCE). */
    @GetMapping("/lookup/{table}")
    public List<ListesService.Valeur> valeurs(@PathVariable String table, @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "5000") int limit) {
        return listes.valeurs(table, q, limit);
    }

    // ------------------------------------------------------------------ cotation
    @GetMapping("/admin/locations")
    public List<CotationService.Lieu> lieux() {
        return cotation.lieux(false);
    }

    @PutMapping("/admin/locations")
    public List<CotationService.Lieu> lieu(@RequestBody CotationService.Lieu l) {
        return cotation.enregistrerLieu(l);
    }

    @GetMapping("/admin/units")
    public List<CotationService.Unite> unites() {
        return cotation.unites();
    }

    @PutMapping("/admin/units")
    public List<CotationService.Unite> unite(@RequestBody CotationService.Unite u) {
        return cotation.enregistrerUnite(u);
    }

    @PutMapping("/admin/members")
    public List<CotationService.Unite> membre(@RequestBody CotationService.Membre m) {
        return cotation.enregistrerMembre(m);
    }

    // ------------------------------------------------------------------ incidents du moteur
    @GetMapping("/admin/incidents")
    public List<Map<String, Object>> incidents() {
        return taches.incidents();
    }

    @PostMapping("/admin/incidents/{job}/retry")
    public ResponseEntity<Void> relancer(@PathVariable String job) {
        taches.relancer(job);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ supervision
    @GetMapping("/supervision/dashboard")
    public Map<String, Object> tableauDeBord(@RequestParam(required = false) String procedure) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("byStatus", jdbc.sql("""
                select procedure_code, status, count(*) from wfe.dossier where dossier_no is not null and (cast(:p as text) is null or procedure_code = :p)
                 group by 1, 2 order by 1, 2
                """).param("p", procedure).query((rs, i) -> Map.<String, Object>of("procedure", rs.getString(1), "status", rs.getString(2), "count", rs.getLong(3))).list());
        m.put("byType", jdbc.sql("""
                select procedure_code, request_type, request_nature, count(*) from wfe.dossier where dossier_no is not null
                   and (cast(:p as text) is null or procedure_code = :p) group by 1, 2, 3 order by 1, 2, 3
                """).param("p", procedure).query((rs, i) -> Map.<String, Object>of("procedure", rs.getString(1), "type", rs.getString(2),
                "nature", rs.getString(3), "count", rs.getLong(4))).list());
        m.put("sla", jdbc.sql("""
                select count(*) filter (where lifecycle = 'ACTIVE' and sla_due_at < now()), count(*) filter (where lifecycle = 'ACTIVE'),
                       coalesce(round(avg(extract(epoch from closed_at - submitted_at) / 3600) filter (where status = 'VALIDE'), 1), 0)
                  from wfe.dossier where dossier_no is not null and (cast(:p as text) is null or procedure_code = :p)
                """).param("p", procedure).query((rs, i) -> Map.<String, Object>of("overdue", rs.getLong(1), "active", rs.getLong(2),
                "avgHoursToIssue", rs.getBigDecimal(3))).single());
        m.put("messagesByFormat", jdbc.sql("select content_format, direction::text, count(*) from wfe.message group by 1, 2 order by 1, 2")
                .query((rs, i) -> Map.<String, Object>of("format", rs.getString(1), "direction", rs.getString(2), "count", rs.getLong(3))).list());
        m.put("payments", paiements.encaissements(procedure, null, null));
        m.put("incidents", taches.incidents().size());
        m.put("deployments", deploiements.actifs());
        return m;
    }

    @GetMapping("/supervision/payments")
    public Map<String, Object> encaissements(@RequestParam(required = false) String procedure, @RequestParam(required = false) String from,
                                             @RequestParam(required = false) String to) {
        return paiements.encaissements(procedure, from, to);
    }

    @PostMapping("/supervision/statements")
    public MessageService.Entete releve(@RequestBody Map<String, String> corps) {
        return paiements.releve(corps.get("procedure"), corps.get("beneficiary"));
    }

    @GetMapping("/supervision/messages")
    public List<MessageService.Entete> messages(@RequestParam(required = false) String code, @RequestParam(required = false) String format,
                                                @RequestParam(required = false) String state, @RequestParam(defaultValue = "200") int limit) {
        return messages.recents(code, format, state, limit);
    }

    @GetMapping("/supervision/tasks")
    public List<TacheService.Tache> taches() {
        return taches.toutes();
    }

    @GetMapping("/supervision/journal")
    public List<Journal.Evenement> journal(@RequestParam(defaultValue = "300") int limit) {
        return journal.lire(null, Math.min(limit, 2000));
    }

    @GetMapping("/supervision/views")
    public List<Map<String, Object>> vues() {
        return jdbc.sql("""
                select table_schema, table_name from information_schema.views where table_schema like 'proc\\_%' order by 1, 2
                """).query((rs, i) -> Map.<String, Object>of("schema", rs.getString(1), "view", rs.getString(2))).list();
    }

    /** Aperçu d'une vue métier (ex. proc_fimex.fimex_request), 50 lignes au plus. */
    @GetMapping("/supervision/views/{schema}/{vue}")
    public List<Map<String, Object>> vue(@PathVariable String schema, @PathVariable String vue) {
        boolean existe = jdbc.sql("select count(*) from information_schema.views where table_schema = :s and table_name = :v and table_schema like 'proc\\_%'")
                .param("s", schema).param("v", vue).query(Long.class).single() > 0;
        if (!existe) throw com.nexora.eservices.web.EservicesException.introuvable("Vue métier inconnue : " + schema + "." + vue);
        return jdbc.sql("select * from " + schema + "." + vue + " limit 50").query().listOfRows();
    }
}
