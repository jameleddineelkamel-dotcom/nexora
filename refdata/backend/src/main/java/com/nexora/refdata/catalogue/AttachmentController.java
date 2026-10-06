package com.nexora.refdata.catalogue;

import com.nexora.refdata.audit.AuditContext;
import com.nexora.refdata.web.RefdataException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

/** Pièces jointes des métadonnées d'une table (normes, recommandations, notes de mise à jour…). */
@RestController
@RequestMapping("/api/v1/tables/{table}/attachments")
public class AttachmentController {

    public record PieceJointe(long id, String fileName, String contentType, long sizeBytes, String description,
                              OffsetDateTime createdAt, String createdBy) {}

    private static final long TAILLE_MAX = 20L * 1024 * 1024;

    private final JdbcClient jdbc;
    private final CatalogueRepository catalogue;
    private final AuditContext audit;

    public AttachmentController(JdbcClient jdbc, CatalogueRepository catalogue, AuditContext audit) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<PieceJointe> lister(@PathVariable String table) {
        return jdbc.sql("""
                select id, file_name, content_type, size_bytes, description, created_at, created_by
                  from ref_attachment where table_id = :t order by created_at desc
                """)
                .param("t", id(table))
                .query((rs, i) -> new PieceJointe(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                        rs.getObject(6, OffsetDateTime.class), rs.getString(7)))
                .list();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public PieceJointe joindre(@PathVariable String table, @RequestParam("file") MultipartFile fichier,
                               @RequestParam(required = false) String description) throws IOException {
        if (fichier.isEmpty()) throw RefdataException.invalide("Le fichier est vide.");
        if (fichier.getSize() > TAILLE_MAX) throw RefdataException.invalide("Pièce jointe limitée à 20 Mo.");
        String nom = fichier.getOriginalFilename() == null ? "piece-jointe" : fichier.getOriginalFilename().replaceAll("[\\\\/]", "_");
        long t = id(table);
        audit.appliquer();
        long id = jdbc.sql("""
                insert into ref_attachment (table_id, file_name, content_type, size_bytes, description, data, created_by)
                values (:t, :nom, :type, :taille, :descr, :data, :auteur) returning id
                """)
                .param("t", t).param("nom", nom.length() > 300 ? nom.substring(0, 300) : nom).param("type", fichier.getContentType())
                .param("taille", fichier.getSize()).param("descr", description == null || description.isBlank() ? null : description.strip())
                .param("data", fichier.getBytes()).param("auteur", audit.utilisateur())
                .query(Long.class).single();
        return lister(table).stream().filter(p -> p.id() == id).findFirst().orElseThrow();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> telecharger(@PathVariable String table, @PathVariable long id) {
        return jdbc.sql("select file_name, content_type, data from ref_attachment where id = :id and table_id = :t")
                .param("id", id).param("t", id(table))
                .query((rs, i) -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(rs.getString(1), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                        .contentType(rs.getString(2) == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(rs.getString(2)))
                        .body(rs.getBytes(3)))
                .optional().orElseThrow(() -> RefdataException.introuvable("Pièce jointe introuvable."));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void retirer(@PathVariable String table, @PathVariable long id) {
        audit.appliquer();
        if (jdbc.sql("delete from ref_attachment where id = :id and table_id = :t").param("id", id).param("t", id(table)).update() == 0)
            throw RefdataException.introuvable("Pièce jointe introuvable.");
    }

    private long id(String table) {
        Long id = catalogue.idDe(table.toUpperCase(java.util.Locale.ROOT));
        if (id == null) throw RefdataException.introuvable("Table « " + table + " » introuvable dans le référentiel.");
        return id;
    }
}
