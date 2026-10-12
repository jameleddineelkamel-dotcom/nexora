package com.nexora.registration.registre;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** API du registre des opérateurs : fiches, comptes rattachés, pièces d'enrôlement, historique, complétion. */
@RestController
@RequestMapping("/api/v1")
public class RegistreController {

    private final RegistreService registre;

    public RegistreController(RegistreService registre) {
        this.registre = registre;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> tableauDeBord() {
        return registre.tableauDeBord();
    }

    /** Opérateurs rattachés au compte connecté (titulaire ou mandataire). */
    @GetMapping("/me/parties")
    public List<RegistreService.Lien> mesOperateurs() {
        return registre.mesOperateurs();
    }

    @GetMapping("/attributes")
    public List<Map<String, String>> attributs() {
        return RegistreService.ATTRIBUTS.stream().map(a -> Map.of("key", a.cle(), "label", a.libelle(), "type", String.valueOf(a.type()))).toList();
    }

    @GetMapping("/parties")
    public List<Map<String, Object>> rechercher(@RequestParam(required = false) String q, @RequestParam(defaultValue = "200") int limit) {
        return registre.rechercher(q, limit);
    }

    @PostMapping("/parties")
    public Map<String, Object> enroler(@RequestBody Map<String, Object> corps) {
        return registre.enroler(corps);
    }

    @GetMapping("/parties/{niu}")
    public Map<String, Object> lire(@PathVariable String niu) {
        return registre.lire(niu);
    }

    @PutMapping("/parties/{niu}")
    public Map<String, Object> modifier(@PathVariable String niu, @RequestBody Map<String, Object> corps) {
        return registre.modifier(niu, corps);
    }

    @PostMapping("/parties/{niu}/complete")
    public Map<String, Object> completer(@PathVariable String niu, @RequestBody Map<String, Object> corps) {
        return registre.completer(niu, corps);
    }

    @GetMapping("/parties/{niu}/history")
    public List<Map<String, Object>> historique(@PathVariable String niu) {
        return registre.historique(niu);
    }

    @GetMapping("/parties/{niu}/users")
    public List<RegistreService.Compte> comptes(@PathVariable String niu) {
        return registre.comptes(niu);
    }

    @PutMapping("/parties/{niu}/users")
    public List<RegistreService.Compte> rattacher(@PathVariable String niu, @RequestBody RegistreService.Compte c) {
        return registre.rattacher(niu, c);
    }

    @GetMapping("/parties/{niu}/documents")
    public List<RegistreService.Piece> pieces(@PathVariable String niu) {
        return registre.pieces(niu);
    }

    @PostMapping(value = "/parties/{niu}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RegistreService.Piece ajouter(@PathVariable String niu, @RequestParam String docCode, @RequestParam(required = false) String label,
                                         @RequestParam(required = false) LocalDate validUntil, @RequestParam("file") MultipartFile f) throws IOException {
        return registre.ajouterPiece(niu, docCode, label, f.getOriginalFilename(), f.getContentType(), f.getBytes(), validUntil);
    }

    @DeleteMapping("/parties/{niu}/documents/{id}")
    public ResponseEntity<Void> retirer(@PathVariable String niu, @PathVariable long id) {
        registre.retirerPiece(niu, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/parties/{niu}/documents/{id}/content")
    public ResponseEntity<byte[]> contenu(@PathVariable String niu, @PathVariable long id) {
        var c = registre.contenu(niu, id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(c.getKey().mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(c.getKey().fileName(), StandardCharsets.UTF_8).build().toString())
                .body(c.getValue());
    }
}
