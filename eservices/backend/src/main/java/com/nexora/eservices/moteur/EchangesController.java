package com.nexora.eservices.moteur;

import com.nexora.eservices.dossier.DossierService;
import com.nexora.eservices.livrable.LivrableService;
import com.nexora.eservices.message.MessageService;
import com.nexora.eservices.paiement.PaiementService;
import com.nexora.eservices.security.Habilitations;
import com.nexora.eservices.security.SecurityConfig;
import com.nexora.eservices.web.EservicesException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Tâches, messages (consultation et réception), avis de paiement, livrables et vérification publique. */
@RestController
@RequestMapping("/api/v1")
public class EchangesController {

    private final TacheService taches;
    private final MessageService messages;
    private final PaiementService paiements;
    private final LivrableService livrables;
    private final DossierService dossiers;

    public EchangesController(TacheService taches, MessageService messages, PaiementService paiements, LivrableService livrables, DossierService dossiers) {
        this.taches = taches;
        this.messages = messages;
        this.paiements = paiements;
        this.livrables = livrables;
        this.dossiers = dossiers;
    }

    // ------------------------------------------------------------------ tâches
    @GetMapping("/tasks")
    public List<TacheService.Tache> mesTaches() {
        return taches.miennes();
    }

    @PostMapping("/tasks/{id}/complete")
    public Map<String, Object> completer(@PathVariable String id, @RequestBody TacheService.Decision d) {
        return taches.completer(id, d);
    }

    // ------------------------------------------------------------------ messages
    private MessageService.Entete accessible(UUID id) {
        MessageService.Entete e = messages.entete(id);
        boolean supervision = Habilitations.utilisateurA(SecurityConfig.SUPERVISER) || Habilitations.utilisateurA(SecurityConfig.ADMINISTRER);
        if (e.dossierId() == null ? !supervision : !supervision && !dossiers.peutVoir(dossiers.lire(e.dossierId())))
            throw EservicesException.interdit("Ce message ne vous est pas accessible.");
        return e;
    }

    @GetMapping("/messages/{id}")
    public Map<String, Object> message(@PathVariable UUID id) {
        MessageService.Entete e = accessible(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", e);
        m.put("values", messages.valeursIndexees(id));
        m.put("states", messages.historiqueEtats(id));
        return m;
    }

    @GetMapping("/messages/{id}/content")
    public ResponseEntity<byte[]> contenu(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean download) {
        MessageService.Entete e = accessible(id);
        String ext = switch (e.format()) { case "JSON" -> "json"; case "XML" -> "xml"; case "CSV" -> "csv"; default -> "txt"; };
        String nom = e.code() + "-" + Objects.toString(e.dossierNo(), e.id().toString().substring(0, 8)) + "." + ext;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(e.contentType() + ";charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                        .filename(nom, StandardCharsets.UTF_8).build().toString())
                .body(messages.contenu(id));
    }

    /**
     * Réception d'un message d'un partenaire (banque, administration) : le corps est le contenu brut (JSON, XML, CSV, TXT)
     * du message du catalogue ; « Idempotency-Key » élimine les doublons (AC-AG-03).
     */
    @PostMapping("/messages/inbound")
    public MessageService.Entete recevoir(@RequestParam String code, @RequestBody byte[] corps,
                                          @RequestHeader(value = "Idempotency-Key", required = false) String cle,
                                          @RequestHeader(value = "X-Nexora-Partner", required = false) String partenaire) {
        return messages.recevoir(code, corps, cle, partenaire);
    }

    // ------------------------------------------------------------------ paiements
    @GetMapping("/payment-notices/{numero}")
    public PaiementService.Avis avis(@PathVariable String numero) {
        PaiementService.Avis a = paiements.avis(numero);
        dossiers.lire(a.dossierId());
        return a;
    }

    public record Reglement(String bank, String channel) {}

    @PostMapping("/payment-notices/{numero}/pay")
    public MessageService.Entete payer(@PathVariable String numero, @RequestBody(required = false) Reglement r) {
        return paiements.payer(numero, r == null ? null : r.bank(), r == null ? null : r.channel());
    }

    // ------------------------------------------------------------------ livrables
    @GetMapping(value = "/documents/{numero}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable String numero, @RequestParam(defaultValue = "false") boolean download) {
        var f = livrables.fichier(numero);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(f.getKey().fileName(), StandardCharsets.UTF_8).build().toString()).body(f.getValue());
    }

    /** Vérification publique d'un livrable (cible du code QR), sans authentification. */
    @GetMapping("/public/verify/{numero}")
    public Map<String, Object> verifier(@PathVariable String numero) {
        return livrables.verifier(numero);
    }
}
