-- =============================================================================================
-- NEXORA · eservices — V4 : stockage objet (MinIO, API S3) des pièces jointes et des livrables
--   Avec MinIO configuré, le fichier est déposé dans le compartiment et la ligne ne garde que sa clé (storage_key) ;
--   sans MinIO, le contenu reste en base (content). Une ligne a toujours l'un ou l'autre.
-- =============================================================================================
ALTER TABLE wfe.attachment ALTER COLUMN content DROP NOT NULL;
ALTER TABLE wfe.attachment ADD COLUMN storage_key VARCHAR(400);
ALTER TABLE wfe.attachment ADD CONSTRAINT ck_attachment_contenu CHECK (content IS NOT NULL OR storage_key IS NOT NULL);
COMMENT ON COLUMN wfe.attachment.storage_key IS 'Clé de l''objet dans le stockage MinIO (compartiment nexora.stockage.minio.bucket) ; NULL = contenu en base';

ALTER TABLE wfe.issued_document ALTER COLUMN content DROP NOT NULL;
ALTER TABLE wfe.issued_document ADD COLUMN storage_key VARCHAR(400);
ALTER TABLE wfe.issued_document ADD CONSTRAINT ck_issued_document_contenu CHECK (content IS NOT NULL OR storage_key IS NOT NULL);
COMMENT ON COLUMN wfe.issued_document.storage_key IS 'Clé du PDF signé dans le stockage MinIO ; NULL = contenu en base';
