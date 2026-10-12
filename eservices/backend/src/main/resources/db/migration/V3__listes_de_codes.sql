-- =============================================================================================
-- NEXORA · eservices — projection des listes de codes du Référentiel Commun (dossier v4.2 §5.2, RG-REF-01)
-- Au déploiement d'une procédure, les tables REF_* utilisées par ses champs sont copiées en lecture seule depuis
-- refdata : les formulaires eFORCE proposent ces listes et la validation contrôle les codes saisis, sans que les
-- opérateurs aient besoin d'un accès au Référentiel Commun. La date de chargement trace la version utilisée.
-- =============================================================================================
CREATE TABLE wfe.code_list (
    refdata_table  VARCHAR(60)  PRIMARY KEY CHECK (refdata_table ~ '^REF_[A-Z0-9_]+$'),
    entries        INT          NOT NULL,
    loaded_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    loaded_by      VARCHAR(60)  NOT NULL
);
CREATE TABLE wfe.code_list_value (
    refdata_table  VARCHAR(60)  NOT NULL REFERENCES wfe.code_list ON DELETE CASCADE,
    code           VARCHAR(70)  NOT NULL,
    label_fr       VARCHAR(500),
    label_en       VARCHAR(500),
    parent_code    VARCHAR(70),
    PRIMARY KEY (refdata_table, code)
);
CREATE INDEX ix_code_list_label ON wfe.code_list_value (refdata_table, lower(label_fr));
