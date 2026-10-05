package com.nexora.refdata.entry;

import com.nexora.refdata.entry.Entry.LookupItem;
import com.nexora.refdata.entry.Entry.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Contenu des tables. Le code d'une entrée est passé en paramètre « code » (et non dans le chemin)
 * car certaines normes utilisent « / » dans leurs codes (ex. PAYTERMS « CREDOC/nM »).
 */
@RestController
@RequestMapping("/api/v1")
public class EntryController {

    private final EntryService service;

    public EntryController(EntryService service) {
        this.service = service;
    }

    @GetMapping("/tables/{table}/entries")
    public Page<Entry> page(@PathVariable String table, @RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "ALL") String status,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validAt,
                            @RequestParam(required = false) String parent, @RequestParam(defaultValue = "false") boolean roots,
                            @RequestParam(required = false) String attribute, @RequestParam(required = false) String value,
                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
                            @RequestParam(defaultValue = "code") String sort, @RequestParam(defaultValue = "false") boolean desc) {
        return service.page(table, new EntryRepository.Filtre(q, status, validAt, parent, roots, attribute, value), page, size, sort, desc);
    }

    @GetMapping("/tables/{table}/entry")
    public Entry lire(@PathVariable String table, @RequestParam String code) {
        return service.lire(table, code);
    }

    @PostMapping("/tables/{table}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public Entry creer(@PathVariable String table, @RequestBody Entry e) {
        return service.creer(table, e);
    }

    @PutMapping("/tables/{table}/entry")
    public Entry modifier(@PathVariable String table, @RequestParam String code, @RequestBody Entry e) {
        return service.modifier(table, code, e);
    }

    /** Pas de suppression : invalidation avec date de fin de validité et motif (tracés dans l'historique). */
    @PostMapping("/tables/{table}/entry/invalidate")
    public Entry invalider(@PathVariable String table, @RequestParam String code, @RequestBody(required = false) Map<String, String> corps) {
        Map<String, String> c = corps == null ? Map.of() : corps;
        return service.invalider(table, code, c.get("date") == null || c.get("date").isBlank() ? null : LocalDate.parse(c.get("date")), c.get("reason"));
    }

    @PostMapping("/tables/{table}/entry/reactivate")
    public Entry reactiver(@PathVariable String table, @RequestParam String code, @RequestBody(required = false) Map<String, String> corps) {
        return service.reactiver(table, code, corps == null ? null : corps.get("reason"));
    }

    // ---------- Services de requête (autres microservices NEXORA, systèmes tiers) ----------

    /** Codes valides à une date, dans une langue (fr, en) : listes déroulantes, contrôles de saisie. */
    @GetMapping("/lookup/{table}")
    public List<LookupItem> lookup(@PathVariable String table, @RequestParam(defaultValue = "fr") String lang,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validAt,
                                   @RequestParam(required = false) String q, @RequestParam(required = false) String parent,
                                   @RequestParam(defaultValue = "1000") int limit) {
        return service.lookup(table, lang, validAt, q, parent, limit);
    }

    /** Vérifie qu'un code est valide et renvoie son libellé (404 sinon). */
    @GetMapping("/lookup/{table}/resolve")
    public LookupItem resoudre(@PathVariable String table, @RequestParam String code, @RequestParam(defaultValue = "fr") String lang,
                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validAt) {
        return service.resoudre(table, code, lang, validAt);
    }
}
