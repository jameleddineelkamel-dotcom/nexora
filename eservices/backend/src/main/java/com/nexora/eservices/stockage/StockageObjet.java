package com.nexora.eservices.stockage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Stockage objet des fichiers (pièces jointes, livrables signés) dans MinIO, par l'API S3. Actif dès que l'adresse
 * {@code nexora.stockage.minio.url} est renseignée ; sinon les fichiers restent en base (colonne {@code content}), comme
 * avant la V4. Le compartiment est créé au premier dépôt s'il n'existe pas.
 */
@Component
public class StockageObjet {

    private static final Logger log = LoggerFactory.getLogger(StockageObjet.class);

    private final MinioClient client;
    private final String compartiment;
    private volatile boolean compartimentVerifie;

    public StockageObjet(@Value("${nexora.stockage.minio.url:}") String url,
                         @Value("${nexora.stockage.minio.access-key:}") String cle,
                         @Value("${nexora.stockage.minio.secret-key:}") String secret,
                         @Value("${nexora.stockage.minio.bucket:nexora-eservices}") String compartiment) {
        this.compartiment = compartiment;
        this.client = url == null || url.isBlank() ? null : MinioClient.builder().endpoint(url).credentials(cle, secret).build();
        log.info(client == null ? "Stockage des fichiers : base de données (MinIO non configuré)"
                : "Stockage des fichiers : MinIO {} (compartiment {})", url, compartiment);
    }

    /** MinIO configuré : les nouveaux fichiers y sont déposés. */
    public boolean actif() {
        return client != null;
    }

    public String compartiment() {
        return compartiment;
    }

    /** Dépose un fichier sous la clé donnée et rend cette clé. */
    public String deposer(String cle, byte[] contenu, String type) {
        exiger();
        try {
            verifierCompartiment();
            client.putObject(PutObjectArgs.builder().bucket(compartiment).object(cle)
                    .stream(new ByteArrayInputStream(contenu), contenu.length, -1)
                    .contentType(type == null || type.isBlank() ? "application/octet-stream" : type).build());
            return cle;
        } catch (Exception e) {
            throw new IllegalStateException("Dépôt impossible dans le stockage objet (" + cle + ") : " + e.getMessage(), e);
        }
    }

    public byte[] lire(String cle) {
        exiger();
        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(compartiment).object(cle).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("Lecture impossible dans le stockage objet (" + cle + ") : " + e.getMessage(), e);
        }
    }

    private void exiger() {
        // Une ligne qui porte une clé a été déposée avec MinIO : il faut le reconfigurer pour la relire
        if (client == null) throw new IllegalStateException("Fichier rangé dans MinIO mais stockage objet non configuré (nexora.stockage.minio.url).");
    }

    private void verifierCompartiment() throws Exception {
        if (compartimentVerifie) return;
        synchronized (this) {
            if (compartimentVerifie) return;
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(compartiment).build()))
                client.makeBucket(MakeBucketArgs.builder().bucket(compartiment).build());
            compartimentVerifie = true;
        }
    }
}
