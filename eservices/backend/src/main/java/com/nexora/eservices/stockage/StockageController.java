package com.nexora.eservices.stockage;

import com.nexora.eservices.audit.Journal;
import com.nexora.eservices.dossier.DossierRepository;
import com.nexora.eservices.livrable.LivrableService;
import com.nexora.eservices.web.EservicesException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Administration du stockage des fichiers (droit d'administration, règle /api/v1/admin/**) : mode courant et répartition
 * des fichiers entre la base et MinIO ; migration vers MinIO des fichiers encore en base, par lots.
 */
@RestController
@RequestMapping("/api/v1/admin/storage")
public class StockageController {

    private final StockageObjet stockage;
    private final JdbcClient jdbc;
    private final Journal journal;

    public StockageController(StockageObjet stockage, JdbcClient jdbc, Journal journal) {
        this.stockage = stockage;
        this.jdbc = jdbc;
        this.journal = journal;
    }

    @GetMapping
    public Map<String, Object> etat() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", stockage.actif() ? "MINIO" : "DATABASE");
        m.put("bucket", stockage.actif() ? stockage.compartiment() : null);
        m.put("attachments", repartition("wfe.attachment"));
        m.put("documents", repartition("wfe.issued_document"));
        return m;
    }

    /** Déplace vers MinIO jusqu'à {@code lot} pièces et autant de livrables encore en base ; à relancer jusqu'à épuisement. */
    @PostMapping("/migrate")
    public Map<String, Object> migrer(@RequestParam(defaultValue = "200") int lot) {
        if (!stockage.actif())
            throw EservicesException.invalide("MinIO n'est pas configuré (nexora.stockage.minio.url) : rien à migrer.");
        int n = Math.max(1, Math.min(lot, 1000));
        int pieces = 0, livrables = 0;
        for (var p : jdbc.sql("select attachment_id, dossier_id, doc_code, sha256, mime_type, content from wfe.attachment where storage_key is null order by attachment_id limit :n")
                .param("n", n).query((rs, i) -> new Fichier(rs.getLong(1), DossierRepository.clePiece(rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)),
                        rs.getString(5), rs.getBytes(6))).list()) {
            stockage.deposer(p.cle(), p.contenu(), p.type());
            jdbc.sql("update wfe.attachment set storage_key = :k, content = null where attachment_id = :i").param("k", p.cle()).param("i", p.id()).update();
            pieces++;
        }
        for (var d : jdbc.sql("select document_id, doc_no, content from wfe.issued_document where storage_key is null order by document_id limit :n")
                .param("n", n).query((rs, i) -> new Fichier(rs.getLong(1), LivrableService.cleLivrable(rs.getString(2)), "application/pdf", rs.getBytes(3))).list()) {
            stockage.deposer(d.cle(), d.contenu(), d.type());
            jdbc.sql("update wfe.issued_document set storage_key = :k, content = null where document_id = :i").param("k", d.cle()).param("i", d.id()).update();
            livrables++;
        }
        journal.tracer("STOCKAGE_MIGRE", null, null, Map.of("attachments", pieces, "documents", livrables, "bucket", stockage.compartiment()));
        Map<String, Object> m = new LinkedHashMap<>(etat());
        m.put("migrated", Map.of("attachments", pieces, "documents", livrables));
        return m;
    }

    private Map<String, Long> repartition(String table) {
        return jdbc.sql("select count(*) filter (where storage_key is null), count(*) filter (where storage_key is not null) from " + table)
                .query((rs, i) -> Map.of("database", rs.getLong(1), "minio", rs.getLong(2))).single();
    }

    private record Fichier(long id, String cle, String type, byte[] contenu) {}
}
