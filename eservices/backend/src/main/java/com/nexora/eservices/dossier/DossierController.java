package com.nexora.eservices.dossier;

import com.nexora.eservices.audit.Journal;
import com.nexora.eservices.cotation.CotationService;
import com.nexora.eservices.deploiement.DeploiementService;
import com.nexora.eservices.deploiement.Paquet;
import com.nexora.eservices.livrable.LivrableService;
import com.nexora.eservices.message.MessageService;
import com.nexora.eservices.moteur.TacheService;
import com.nexora.eservices.paiement.PaiementService;
import com.nexora.eservices.security.Habilitations;
import com.nexora.eservices.security.SecurityConfig;
import com.nexora.eservices.web.EservicesException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** eFORCE / eGOV : catalogue des procédures, dossiers, pièces, estimation, dépôt et consultation détaillée. */
@RestController
@RequestMapping("/api/v1")
public class DossierController {

    private final DossierService dossiers;
    private final DeploiementService deploiements;
    private final CotationService cotation;
    private final MessageService messages;
    private final PaiementService paiements;
    private final LivrableService livrables;
    private final TacheService taches;
    private final Journal journal;

    public DossierController(DossierService dossiers, DeploiementService deploiements, CotationService cotation, MessageService messages,
                             PaiementService paiements, LivrableService livrables, TacheService taches, Journal journal) {
        this.dossiers = dossiers;
        this.deploiements = deploiements;
        this.cotation = cotation;
        this.messages = messages;
        this.paiements = paiements;
        this.livrables = livrables;
        this.taches = taches;
        this.journal = journal;
    }

    // ------------------------------------------------------------------ catalogue
    @GetMapping("/catalog")
    public List<Map<String, Object>> catalogue() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (DeploiementService.Deploiement d : deploiements.actifs()) {
            Paquet p = deploiements.paquet(d.id());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", p.procedure().code());
            m.put("labelFr", p.procedure().labelFr());
            m.put("labelEn", p.procedure().labelEn());
            m.put("authority", p.procedure().authority());
            m.put("description", p.procedure().description());
            m.put("versionNo", p.versionNo());
            m.put("terminology", p.terminology());
            m.put("requestTypes", p.requestTypes());
            m.put("natures", p.natures());
            m.put("fees", p.fees());
            m.put("slaHours", p.reglage("slaHours", null));
            l.add(m);
        }
        return l;
    }

    /** Définition du formulaire publié (rendu dynamique côté eFORCE), avec la liste des lieux de traitement actifs. */
    @GetMapping("/catalog/{code}/form")
    public Map<String, Object> formulaire(@PathVariable String code) {
        Paquet p = deploiements.paquet(deploiements.actif(code).id());
        return formulaire(p);
    }

    private Map<String, Object> formulaire(Paquet p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("procedure", p.procedure());
        m.put("versionNo", p.versionNo());
        m.put("requestTypes", p.requestTypes());
        m.put("natures", p.natures());
        m.put("sections", p.sections());
        m.put("fields", p.fields());
        m.put("documents", p.documents());
        m.put("statuses", p.statuses());
        m.put("deliverables", p.deliverables());
        Map<String, Object> reglages = new LinkedHashMap<>(Map.of("processingLocationField", p.reglage("processingLocationField", ""),
                "applicantField", p.reglage("applicantField", ""), "previousField", p.reglage("previousField", ""),
                "renewalYearField", p.reglage("renewalYearField", ""), "currency", p.reglage("currency", "XAF"),
                // Rendu choisi dans le studio : DYNAMIQUE ou GENERE (composant Angular compilé dans e-Services, sinon repli dynamique)
                "rendu", "GENERE".equals(p.reglage("rendu", "")) ? "GENERE" : "DYNAMIQUE"));
        // Gabarit d'IHM conçu dans le studio (disposition, colonnes, densité…) : transmis tel quel au formulaire
        if (p.settings() != null && p.settings().get("ihm") instanceof Map<?, ?> ihm) reglages.put("ihm", ihm);
        m.put("settings", reglages);
        m.put("locations", cotation.lieux(true));
        return m;
    }

    @GetMapping("/catalog/{code}/titles")
    public List<Map<String, Object>> titres(@PathVariable String code) {
        return dossiers.titresValides(code);
    }

    @GetMapping("/catalog/locations")
    public List<CotationService.Lieu> lieux() {
        return cotation.lieux(true);
    }

    // ------------------------------------------------------------------ dossiers
    @PostMapping("/dossiers")
    public Dossier creer(@RequestBody DossierService.NouvelleDemande n) {
        return dossiers.creer(n);
    }

    @GetMapping("/dossiers")
    public List<DossierService.Ligne> liste(@RequestParam(defaultValue = "mine") String scope, @RequestParam(required = false) String procedure,
                                            @RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                            @RequestParam(required = false) String criterion, @RequestParam(defaultValue = "200") int limit) {
        return dossiers.liste(scope, procedure, status, q, criterion, limit);
    }

    /** Vue complète d'un dossier : données, formulaire publié, pièces, statuts, tâches, messages, paiements, livrables, cotation. */
    @GetMapping("/dossiers/{id}")
    public Map<String, Object> detail(@PathVariable UUID id) {
        Dossier d = dossiers.lire(id);
        Paquet p = dossiers.paquet(d);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dossier", d);
        m.put("form", formulaire(p));
        m.put("attachments", dossiers.pieces(id));
        m.put("history", dossiers.historique(id));
        m.put("tasks", taches.duDossier(d));
        m.put("messages", messages.duDossier(id));
        m.put("paymentNotices", paiements.duDossier(id));
        m.put("documents", livrables.duDossier(id));
        m.put("cotation", cotation.historique(id));
        m.put("canEdit", Habilitations.acteur().equals(d.submittedBy()) && ("DRAFT".equals(d.lifecycle()) || "COMPLEMENT_DEMANDE".equals(d.status())));
        m.put("canPreview", Habilitations.utilisateurA(SecurityConfig.TRAITER) || Habilitations.utilisateurA(SecurityConfig.SIGNER)
                || Habilitations.utilisateurA(SecurityConfig.SUPERVISER));
        return m;
    }

    @PutMapping("/dossiers/{id}/data")
    public Dossier modifier(@PathVariable UUID id, @RequestBody Map<String, Object> donnees) {
        return dossiers.modifier(id, donnees);
    }

    @PostMapping(value = "/dossiers/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DossierRepository.Piece piece(@PathVariable UUID id, @RequestParam String docCode, @RequestParam("file") MultipartFile fichier) throws IOException {
        return dossiers.ajouterPiece(id, docCode, Objects.toString(fichier.getOriginalFilename(), docCode), fichier.getContentType(), fichier.getBytes());
    }

    @DeleteMapping("/dossiers/{id}/attachments/{piece}")
    public ResponseEntity<Void> retirer(@PathVariable UUID id, @PathVariable long piece) {
        dossiers.retirerPiece(id, piece);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/dossiers/{id}/attachments/{piece}")
    public ResponseEntity<byte[]> telecharger(@PathVariable UUID id, @PathVariable long piece) {
        var p = dossiers.contenuPiece(id, piece).orElseThrow(() -> EservicesException.introuvable("Pièce introuvable."));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(p.getKey().mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(p.getKey().fileName(), StandardCharsets.UTF_8).build().toString())
                .body(p.getValue());
    }

    /** Montant à payer et contrôles avant dépôt (popup de confirmation, UC-01). */
    @GetMapping("/dossiers/{id}/estimate")
    public Validation.Bilan estimer(@PathVariable UUID id) {
        return dossiers.bilan(dossiers.lire(id));
    }

    @PostMapping("/dossiers/{id}/submit")
    public Dossier deposer(@PathVariable UUID id) {
        return dossiers.deposer(id);
    }

    @GetMapping(value = "/dossiers/{id}/deliverable/preview", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> apercu(@PathVariable UUID id) {
        if (!Habilitations.utilisateurA(SecurityConfig.TRAITER) && !Habilitations.utilisateurA(SecurityConfig.SIGNER)
                && !Habilitations.utilisateurA(SecurityConfig.SUPERVISER))
            throw EservicesException.interdit("L'aperçu du livrable est réservé aux agents.");
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"apercu-brouillon.pdf\"").body(livrables.apercu(id));
    }

    @GetMapping("/dossiers/{id}/journal")
    public List<Journal.Evenement> journal(@PathVariable UUID id) {
        dossiers.lire(id);
        return journal.lire(id, 300);
    }
}
