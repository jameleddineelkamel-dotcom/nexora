package com.nexora.refdata.catalogue;

import com.nexora.refdata.catalogue.CatalogueModel.Category;
import com.nexora.refdata.catalogue.CatalogueModel.TableDef;
import com.nexora.refdata.catalogue.CatalogueModel.TableSummary;
import com.nexora.refdata.web.RefdataException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class CatalogueController {

    private final CatalogueService service;

    public CatalogueController(CatalogueService service) {
        this.service = service;
    }

    // ---------- Catégories ----------
    @GetMapping("/categories")
    public List<Category> categories() {
        return service.categories();
    }

    @PostMapping("/categories")
    public ResponseEntity<Category> creerCategorie(@RequestBody Category c) {
        Category cree = service.creerCategorie(c);
        return ResponseEntity.created(URI.create("/api/v1/categories/" + cree.code())).body(cree);
    }

    @PutMapping("/categories/{code}")
    public Category modifierCategorie(@PathVariable String code, @RequestBody Category c) {
        return service.modifierCategorie(code, c);
    }

    // ---------- Tables ----------
    @GetMapping("/tables")
    public List<TableSummary> tables(@RequestParam(required = false) String category, @RequestParam(required = false) String source,
                                     @RequestParam(required = false) String phase, @RequestParam(required = false) String status) {
        return service.resumes(category, source, phase, status);
    }

    @GetMapping("/tables/{code}")
    public TableDef table(@PathVariable String code) {
        return service.table(code);
    }

    /** Création dynamique d'une nouvelle table de référence. */
    @PostMapping("/tables")
    public ResponseEntity<TableDef> creer(@RequestBody TableDef t) {
        TableDef cree = service.creer(t);
        return ResponseEntity.created(URI.create("/api/v1/tables/" + cree.code())).body(cree);
    }

    @PutMapping("/tables/{code}")
    public TableDef modifier(@PathVariable String code, @RequestBody TableDef t, @RequestParam(defaultValue = "false") boolean force) {
        return service.modifier(code, t, force);
    }

    @PatchMapping("/tables/{code}/status")
    public TableDef statut(@PathVariable String code, @RequestBody Map<String, String> corps) {
        return service.changerStatut(code, corps.getOrDefault("status", ""));
    }

    // ---------- Métadonnées du catalogue ----------
    @GetMapping("/catalogue/schema")
    public Map<String, Object> schema() {
        return Map.of("metadata", CatalogueModel.FICHE_ISO_19115, "dataTypes", CatalogueModel.TYPES, "roles", CatalogueModel.ROLES,
                "sources", CatalogueModel.SOURCES, "bspPhases", CatalogueModel.PHASES_BSP, "statuses", CatalogueModel.STATUTS);
    }

    /** Export des définitions (structure + fiches ISO 19115), sans les données. */
    @GetMapping("/catalogue/export")
    public ResponseEntity<Map<String, Object>> exporter() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"nexora-refdata-catalogue.json\"")
                .body(Map.of("format", "nexora-refdata-catalogue/1", "categories", service.categories(), "tables", service.definitions()));
    }

    /** Chargement en masse de définitions de tables (création ou mise à jour). */
    @PostMapping("/catalogue/import")
    public Map<String, Object> importer(@RequestBody List<TableDef> definitions) {
        List<String> creees = new ArrayList<>(), modifiees = new ArrayList<>(), erreurs = new ArrayList<>();
        for (TableDef d : definitions) {
            String code = d.code() == null ? "?" : d.code().toUpperCase();
            try {
                boolean existe;
                try { service.table(code); existe = true; } catch (RefdataException e) { existe = e.getStatus() != HttpStatus.NOT_FOUND; }
                if (existe) { service.modifier(code, d, false); modifiees.add(code); }
                else { service.creer(d); creees.add(code); }
            } catch (RefdataException e) {
                erreurs.add(code + " : " + String.join(" ", e.getErreurs()));
            }
        }
        return Map.of("created", creees, "updated", modifiees, "errors", erreurs);
    }
}
