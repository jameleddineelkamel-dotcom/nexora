package com.nexora.refdata.io;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1")
public class IoController {

    private final ImportService imports;
    private final ExportService exports;
    private final InferenceService inference;

    public IoController(ImportService imports, ExportService exports, InferenceService inference) {
        this.imports = imports;
        this.exports = exports;
        this.inference = inference;
    }

    /** Chargement d'une liste de codes (CSV, JSON, Excel) ; dryRun=true pour simuler sans rien enregistrer. */
    @PostMapping(value = "/tables/{table}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportService.Resultat importer(@PathVariable String table, @RequestParam("file") MultipartFile fichier,
                                           @RequestParam(defaultValue = "MERGE") ImportService.Mode mode,
                                           @RequestParam(defaultValue = "false") boolean dryRun,
                                           @RequestParam(required = false) String reason) throws IOException {
        return imports.importer(table, fichier.getOriginalFilename(), fichier.getBytes(), mode, dryRun, reason);
    }

    @GetMapping("/tables/{table}/export")
    public ResponseEntity<StreamingResponseBody> exporter(@PathVariable String table, @RequestParam(defaultValue = "csv") String format,
                                                          @RequestParam(defaultValue = "ALL") String status) {
        String f = format.toLowerCase(Locale.ROOT);
        String nom = table.toUpperCase(Locale.ROOT) + "_" + LocalDate.now() + "." + f;
        MediaType type = switch (f) {
            case "json" -> MediaType.APPLICATION_JSON;
            case "xlsx" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            default -> new MediaType("text", "csv", StandardCharsets.UTF_8);
        };
        StreamingResponseBody corps = out -> {
            switch (f) {
                case "json" -> exports.json(table, status, out);
                case "xlsx" -> exports.excel(table, status, out);
                default -> exports.csv(table, status, out);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nom).build().toString())
                .contentType(type).body(corps);
    }

    /** Assistant : propose la structure d'une nouvelle table à partir d'un fichier. */
    @PostMapping(value = "/tables/infer", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InferenceService.Proposition proposer(@RequestParam("file") MultipartFile fichier) throws IOException {
        return inference.proposer(fichier.getOriginalFilename(), fichier.getBytes());
    }
}
