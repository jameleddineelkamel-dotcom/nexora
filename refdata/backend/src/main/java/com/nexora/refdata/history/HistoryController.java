package com.nexora.refdata.history;

import com.nexora.refdata.entry.Entry.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class HistoryController {

    private final HistoryService service;

    public HistoryController(HistoryService service) {
        this.service = service;
    }

    /** Historique filtrable : par table, type d'objet, code, opération, auteur, canal, période ou lot d'import. */
    @GetMapping("/history")
    public Page<HistoryService.Evenement> historique(@RequestParam(required = false) String table, @RequestParam(required = false) String type,
                                                     @RequestParam(required = false) String code, @RequestParam(required = false) String operation,
                                                     @RequestParam(required = false) String author, @RequestParam(required = false) String channel,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                     @RequestParam(required = false) String batch,
                                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.evenements(new HistoryService.Filtre(table, type, code, operation, author, channel, from, to, batch), page, size);
    }

    @GetMapping("/history/activity")
    public List<HistoryService.Activite> activite(@RequestParam(defaultValue = "30") int days) {
        return service.activite(days);
    }

    @GetMapping("/imports")
    public List<HistoryService.Chargement> chargements(@RequestParam(required = false) String table, @RequestParam(defaultValue = "100") int limit) {
        return service.chargements(table, limit);
    }
}
