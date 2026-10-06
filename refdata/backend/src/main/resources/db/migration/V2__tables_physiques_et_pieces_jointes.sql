-- =============================================================================================
-- 1. Tables physiques REF_* : chaque table de référence existe désormais comme vraie table
--    PostgreSQL referentiel.ref_xxx (ex. referentiel.ref_country), avec colonnes typées et clé
--    primaire sur le code. Elle est tenue à jour en temps réel par trigger depuis ref_entry et
--    reconstruite à chaque changement de structure. Les vues de calcul passent dans le schéma
--    interne referentiel_src.
-- 2. Pièces jointes des métadonnées (normes, recommandations, notes…).
-- 3. Retrait des mentions « (Fichier(s) Joint(s) : …) » héritées du rapport.
-- =============================================================================================
CREATE SCHEMA referentiel_src;

-- Supprime un objet referentiel.<nom>, qu'il s'agisse d'une vue (version 1) ou d'une table.
CREATE FUNCTION ref_retirer_objet(p_schema TEXT, p_nom TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    v_type CHAR;
BEGIN
    SELECT c.relkind INTO v_type FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = p_schema AND c.relname = p_nom;
    IF v_type = 'v' THEN EXECUTE format('DROP VIEW %I.%I', p_schema, p_nom);
    ELSIF v_type = 'r' THEN EXECUTE format('DROP TABLE %I.%I', p_schema, p_nom);
    END IF;
END $$;

CREATE OR REPLACE FUNCTION ref_generer_vue(p_code TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    t      ref_table%ROWTYPE;
    c      ref_column%ROWTYPE;
    v_nom  TEXT := lower(p_code);
    v_cols TEXT := '';
    v_cle  TEXT;
    v_expr TEXT;
BEGIN
    PERFORM ref_retirer_objet('referentiel', v_nom);
    PERFORM ref_retirer_objet('referentiel_src', v_nom);
    SELECT * INTO t FROM ref_table WHERE code = p_code;
    IF NOT FOUND OR t.status = 'ARCHIVED' THEN RETURN; END IF;

    -- Vue de calcul : conversion des attributs JSONB en colonnes typées
    FOR c IN SELECT * FROM ref_column WHERE table_id = t.id ORDER BY sort_order, id LOOP
        v_expr := CASE c.role
            WHEN 'CODE' THEN 'e.code'
            WHEN 'LABEL_FR' THEN 'e.label_fr'
            WHEN 'LABEL_EN' THEN 'e.label_en'
            ELSE CASE c.data_type
                WHEN 'INTEGER' THEN format('(e.attributes ->> %L)::bigint', c.key)
                WHEN 'DECIMAL' THEN format('(e.attributes ->> %L)::numeric', c.key)
                WHEN 'DATE'    THEN format('(e.attributes ->> %L)::date', c.key)
                WHEN 'BOOLEAN' THEN format('(e.attributes ->> %L)::boolean', c.key)
                ELSE format('e.attributes ->> %L', c.key) END
            END;
        v_cols := v_cols || format(', %s AS %I', v_expr, lower(c.key));
        IF c.role = 'CODE' THEN v_cle := lower(c.key); END IF;
    END LOOP;
    EXECUTE format(
        'CREATE VIEW referentiel_src.%I AS SELECT e.id AS ref_id%s, e.parent_code AS ref_parent_code, '
        'e.valid_from AS ref_valid_from, e.valid_to AS ref_valid_to, e.status AS ref_status, '
        'e.updated_at AS ref_updated_at FROM public.ref_entry e WHERE e.table_id = %s',
        v_nom, v_cols, t.id);

    -- Table physique, alimentée depuis la vue
    EXECUTE format('CREATE TABLE referentiel.%I AS SELECT * FROM referentiel_src.%I', v_nom, v_nom);
    EXECUTE format('ALTER TABLE referentiel.%I ADD PRIMARY KEY (%I)', v_nom, coalesce(v_cle, 'ref_id'));
    EXECUTE format('CREATE UNIQUE INDEX ON referentiel.%I (ref_id)', v_nom);
    EXECUTE format('COMMENT ON TABLE referentiel.%I IS %L', v_nom,
                   upper(p_code) || ' — ' || t.name_fr || ' (tenue à jour par le microservice refdata ; ne pas modifier directement)');
END $$;

-- Synchronisation ligne à ligne ref_entry → referentiel.ref_xxx
CREATE FUNCTION ref_synchroniser() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_nom TEXT;
    v_id  BIGINT := coalesce(NEW.id, OLD.id);
BEGIN
    IF coalesce(current_setting('nexora.sync', true), 'on') = 'off' THEN RETURN NULL; END IF;
    SELECT lower(code) INTO v_nom FROM ref_table WHERE id = coalesce(NEW.table_id, OLD.table_id) AND status <> 'ARCHIVED';
    IF v_nom IS NULL OR to_regclass(format('referentiel.%I', v_nom)) IS NULL THEN RETURN NULL; END IF;
    EXECUTE format('DELETE FROM referentiel.%I WHERE ref_id = $1', v_nom) USING v_id;
    IF TG_OP <> 'DELETE' THEN
        EXECUTE format('INSERT INTO referentiel.%I SELECT * FROM referentiel_src.%I WHERE ref_id = $1', v_nom, v_nom) USING v_id;
    END IF;
    RETURN NULL;
END $$;

CREATE TRIGGER tr_ref_entry_synchro AFTER INSERT OR UPDATE OR DELETE ON ref_entry
    FOR EACH ROW EXECUTE FUNCTION ref_synchroniser();

-- ---------------------------------------------------------------------------------------------
-- Pièces jointes des métadonnées
-- ---------------------------------------------------------------------------------------------
CREATE TABLE ref_attachment (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    table_id      BIGINT       NOT NULL REFERENCES ref_table (id) ON DELETE CASCADE,
    file_name     VARCHAR(300) NOT NULL,
    content_type  VARCHAR(150),
    size_bytes    BIGINT       NOT NULL,
    description   VARCHAR(500),
    data          BYTEA        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(100) NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_ref_attachment_table ON ref_attachment (table_id);

ALTER TABLE ref_history DROP CONSTRAINT ref_history_entity_type_check;
ALTER TABLE ref_history ADD CONSTRAINT ref_history_entity_type_check
    CHECK (entity_type IN ('CATEGORY', 'TABLE', 'COLUMN', 'ENTRY', 'ATTACHMENT'));

-- Journalisation : le contenu binaire des pièces jointes n'est jamais copié dans l'historique
CREATE OR REPLACE FUNCTION ref_journaliser() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_type   TEXT  := TG_ARGV[0];
    v_old    JSONB := CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) - 'version' - 'data' END;
    v_new    JSONB := CASE WHEN TG_OP <> 'DELETE' THEN to_jsonb(NEW) - 'version' - 'data' END;
    v_ligne  JSONB := coalesce(v_new, v_old);
    v_diff   JSONB;
    v_op     TEXT;
    v_table  TEXT;
    v_code   TEXT;
BEGIN
    IF coalesce(current_setting('nexora.history', true), 'on') = 'off' THEN
        RETURN coalesce(NEW, OLD);
    END IF;

    IF TG_OP = 'UPDATE' THEN
        SELECT jsonb_object_agg(n.key, jsonb_build_array(o.value, n.value)) INTO v_diff
          FROM jsonb_each(v_new) n JOIN jsonb_each(v_old) o USING (key)
         WHERE n.value IS DISTINCT FROM o.value AND n.key NOT IN ('updated_at');
        IF v_diff IS NULL THEN RETURN NEW; END IF;
        FOREACH v_code IN ARRAY ARRAY['attributes', 'metadata'] LOOP
            IF v_diff ? v_code THEN
                v_diff := (v_diff - v_code) || coalesce((
                    SELECT jsonb_object_agg(v_code || '.' || k, jsonb_build_array(v_old -> v_code -> k, v_new -> v_code -> k))
                      FROM (SELECT jsonb_object_keys(coalesce(v_old -> v_code, '{}')) AS k
                            UNION SELECT jsonb_object_keys(coalesce(v_new -> v_code, '{}'))) cles
                     WHERE (v_old -> v_code -> k) IS DISTINCT FROM (v_new -> v_code -> k)), '{}');
            END IF;
        END LOOP;
        v_op := CASE
            WHEN v_diff ? 'status' AND v_new ->> 'status' IN ('INVALID', 'ARCHIVED') THEN 'INVALIDATION'
            WHEN v_diff ? 'status' AND v_old ->> 'status' IN ('INVALID', 'ARCHIVED') THEN 'REACTIVATION'
            ELSE 'MODIFICATION' END;
    ELSE
        v_op := CASE TG_OP WHEN 'INSERT' THEN 'CREATION' ELSE 'SUPPRESSION' END;
    END IF;

    IF v_type IN ('ENTRY', 'COLUMN', 'ATTACHMENT') THEN
        SELECT code INTO v_table FROM ref_table WHERE id = (v_ligne ->> 'table_id')::BIGINT;
        v_code := CASE v_type WHEN 'ENTRY' THEN v_ligne ->> 'code' WHEN 'COLUMN' THEN v_ligne ->> 'key' ELSE v_ligne ->> 'file_name' END;
    ELSIF v_type = 'TABLE' THEN
        v_table := v_ligne ->> 'code';
        v_code  := v_ligne ->> 'code';
    ELSE
        v_code := v_ligne ->> 'code';
    END IF;

    INSERT INTO ref_history (entity_type, entity_id, table_code, entity_code, operation, changes, snapshot,
                             author, channel, reason, correlation_id)
    VALUES (v_type, (v_ligne ->> 'id')::BIGINT, v_table, v_code, v_op, v_diff, v_ligne,
            coalesce(nullif(current_setting('nexora.user', true), ''), session_user),
            coalesce(nullif(current_setting('nexora.channel', true), ''), 'SQL'),
            nullif(current_setting('nexora.reason', true), ''),
            nullif(current_setting('nexora.correlation', true), ''));
    RETURN coalesce(NEW, OLD);
END $$;

CREATE TRIGGER tr_ref_attachment_historique AFTER INSERT OR UPDATE OR DELETE ON ref_attachment
    FOR EACH ROW EXECUTE FUNCTION ref_journaliser('ATTACHMENT');

-- ---------------------------------------------------------------------------------------------
-- Données existantes : mentions de fichiers joints retirées, tables physiques générées
-- ---------------------------------------------------------------------------------------------
SELECT set_config('nexora.user', 'systeme', true), set_config('nexora.channel', 'SQL', true),
       set_config('nexora.reason', 'Migration V2 : retrait des mentions « Fichier(s) joint(s) » (pièces jointes gérées à part)', true);

UPDATE ref_table
   SET standards = nullif(btrim(regexp_replace(standards, '\s*\(\s*Fichiers?\s+Joints?\s*:[^)]*\)', '', 'gi'), E' \n;,'), ''),
       description = nullif(btrim(regexp_replace(description, '\s*\(\s*Fichiers?\s+Joints?\s*:[^)]*\)', '', 'gi'), E' \n;,'), '')
 WHERE standards ~* 'Fichiers?\s+Joints?' OR description ~* 'Fichiers?\s+Joints?';

DO $$
DECLARE r RECORD;
BEGIN
    FOR r IN SELECT code FROM ref_table LOOP
        PERFORM ref_generer_vue(r.code);
    END LOOP;
END $$;
