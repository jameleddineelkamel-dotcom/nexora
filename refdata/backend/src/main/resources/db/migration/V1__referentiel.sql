-- =============================================================================================
-- NEXORA · Microservice « refdata » — Référentiel Commun du commerce extérieur
-- Moteur de tables de référence : catalogue (catégories, tables REF_*, colonnes, métadonnées
-- ISO 19115), contenu générique (JSONB), historique par trigger, journal des chargements,
-- vues SQL générées « referentiel.ref_xxx » pour chaque table.
-- =============================================================================================
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- Recherche insensible à la casse et aux accents (IMMUTABLE pour pouvoir être indexée).
CREATE FUNCTION ref_texte_recherche(VARIADIC p_parties text[]) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT lower(public.unaccent('public.unaccent'::regdictionary, array_to_string(p_parties, ' ')))
$$;

-- ---------------------------------------------------------------------------------------------
-- Catalogue
-- ---------------------------------------------------------------------------------------------
CREATE TABLE ref_category (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         VARCHAR(40)  NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    parent_code  VARCHAR(40)  REFERENCES ref_category (code) ON UPDATE CASCADE,
    label_fr     VARCHAR(200) NOT NULL,
    label_en     VARCHAR(200),
    description  TEXT,
    source       VARCHAR(20)  NOT NULL CHECK (source IN ('INTERNATIONALE', 'REGIONALE', 'NATIONALE')),
    icon         VARCHAR(40),
    color        VARCHAR(9),
    sort_order   INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version      BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE ref_table (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code              VARCHAR(60)  NOT NULL UNIQUE CHECK (code ~ '^REF_[A-Z0-9_]{2,56}$'),
    number            VARCHAR(12),
    name_fr           VARCHAR(300) NOT NULL,
    name_en           VARCHAR(300),
    description       TEXT,
    category_code     VARCHAR(40)  NOT NULL REFERENCES ref_category (code) ON UPDATE CASCADE,
    source            VARCHAR(20)  NOT NULL CHECK (source IN ('INTERNATIONALE', 'REGIONALE', 'NATIONALE')),
    standards         TEXT,
    producer          TEXT,
    update_authority  TEXT,
    obtention_mode    TEXT,
    update_mode       TEXT,
    bsp_phases        VARCHAR(4)[] NOT NULL DEFAULT '{}',
    parent_table_code VARCHAR(60)  REFERENCES ref_table (code) ON UPDATE CASCADE,
    metadata          JSONB        NOT NULL DEFAULT '{}',   -- fiche ISO 19115 (identification, cycle de vie, organismes)
    source_document   TEXT,
    data_source       TEXT,
    status            VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DRAFT', 'ARCHIVED')),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_ref_table_bsp CHECK (bsp_phases <@ ARRAY['BUY', 'SHIP', 'PAY']::VARCHAR(4)[])
);
CREATE INDEX ix_ref_table_category ON ref_table (category_code);

CREATE TABLE ref_column (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    table_id      BIGINT       NOT NULL REFERENCES ref_table (id) ON DELETE CASCADE,
    key           VARCHAR(60)  NOT NULL CHECK (key ~ '^[A-Za-z][A-Za-z0-9_]*$'),
    label_fr      VARCHAR(300) NOT NULL,
    label_en      VARCHAR(300),
    role          VARCHAR(10)  NOT NULL DEFAULT 'ATTRIBUTE' CHECK (role IN ('CODE', 'LABEL_FR', 'LABEL_EN', 'ATTRIBUTE')),
    data_type     VARCHAR(10)  NOT NULL DEFAULT 'STRING'
                  CHECK (data_type IN ('STRING', 'TEXT', 'INTEGER', 'DECIMAL', 'DATE', 'BOOLEAN', 'CODE_REF')),
    max_length    INT          CHECK (max_length IS NULL OR max_length > 0),
    pattern       VARCHAR(200),
    required      BOOLEAN      NOT NULL DEFAULT false,
    cardinality   VARCHAR(10),
    xml_tag       VARCHAR(80),
    untded        VARCHAR(20),                       -- référence UNTDED / ISO 7372
    unit          VARCHAR(40),
    definition    TEXT,
    comment       TEXT,
    ref_table_code VARCHAR(60) REFERENCES ref_table (code) ON UPDATE CASCADE,
    sort_order    INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_ref_column UNIQUE (table_id, key),
    CONSTRAINT ck_ref_column_ref CHECK ((data_type = 'CODE_REF') = (ref_table_code IS NOT NULL))
);
-- Une seule colonne par rôle structurant
CREATE UNIQUE INDEX ux_ref_column_role ON ref_column (table_id, role) WHERE role <> 'ATTRIBUTE';

-- ---------------------------------------------------------------------------------------------
-- Contenu des tables : un code = une ligne, attributs typés par ref_column (JSONB)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE ref_entry (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    table_id     BIGINT        NOT NULL REFERENCES ref_table (id),
    code         VARCHAR(100)  NOT NULL,
    label_fr     VARCHAR(2000),
    label_en     VARCHAR(2000),
    parent_code  VARCHAR(100),
    attributes   JSONB         NOT NULL DEFAULT '{}',
    valid_from   DATE,
    valid_to     DATE,
    status       VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INVALID')),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_ref_entry UNIQUE (table_id, code),
    CONSTRAINT ck_ref_entry_validite CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX ix_ref_entry_parent ON ref_entry (table_id, parent_code);
CREATE INDEX ix_ref_entry_recherche ON ref_entry
    USING gin (ref_texte_recherche(code, coalesce(label_fr, ''), coalesce(label_en, '')) gin_trgm_ops);
CREATE INDEX ix_ref_entry_attributs ON ref_entry USING gin (attributes jsonb_path_ops);

-- Règle des spécifications (NSID, ERMIS) : un code n'est jamais supprimé, il est invalidé.
CREATE FUNCTION ref_interdire_suppression() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF coalesce(current_setting('nexora.allow_delete', true), 'off') <> 'on' THEN
        RAISE EXCEPTION 'Suppression interdite dans le référentiel : invalidez le code % (statut INVALID, fin de validité).', OLD.code
            USING ERRCODE = 'P0001';
    END IF;
    RETURN OLD;
END $$;
CREATE TRIGGER tr_ref_entry_pas_de_suppression BEFORE DELETE ON ref_entry
    FOR EACH ROW EXECUTE FUNCTION ref_interdire_suppression();

-- ---------------------------------------------------------------------------------------------
-- Historique des modifications et métadonnées de l'historique
-- (auteur, canal, motif, lot) transmises par l'application via set_config('nexora.*', …, true)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE ref_history (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_type    VARCHAR(10)  NOT NULL CHECK (entity_type IN ('CATEGORY', 'TABLE', 'COLUMN', 'ENTRY')),
    entity_id      BIGINT       NOT NULL,
    table_code     VARCHAR(60),
    entity_code    VARCHAR(100),
    operation      VARCHAR(14)  NOT NULL
                   CHECK (operation IN ('CREATION', 'MODIFICATION', 'INVALIDATION', 'REACTIVATION', 'SUPPRESSION')),
    changes        JSONB,                       -- {"champ": [avant, après]} ; attributs : "attributes.Cle"
    snapshot       JSONB        NOT NULL,
    author         VARCHAR(100) NOT NULL,
    channel        VARCHAR(10)  NOT NULL,       -- UI, API, IMPORT, SEED, SQL
    reason         TEXT,
    correlation_id VARCHAR(60),                 -- lot d'import
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_ref_history_entite ON ref_history (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_ref_history_table ON ref_history (table_code, occurred_at DESC);
CREATE INDEX ix_ref_history_date ON ref_history (occurred_at DESC);

CREATE FUNCTION ref_journaliser() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_type   TEXT  := TG_ARGV[0];
    v_old    JSONB := CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) - 'version' END;
    v_new    JSONB := CASE WHEN TG_OP <> 'DELETE' THEN to_jsonb(NEW) - 'version' END;
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
        -- Détail attribut par attribut pour les colonnes JSONB
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

    IF v_type = 'ENTRY' THEN
        SELECT code INTO v_table FROM ref_table WHERE id = (v_ligne ->> 'table_id')::BIGINT;
        v_code := v_ligne ->> 'code';
    ELSIF v_type = 'COLUMN' THEN
        SELECT code INTO v_table FROM ref_table WHERE id = (v_ligne ->> 'table_id')::BIGINT;
        v_code := v_ligne ->> 'key';
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

CREATE TRIGGER tr_ref_category_historique AFTER INSERT OR UPDATE OR DELETE ON ref_category
    FOR EACH ROW EXECUTE FUNCTION ref_journaliser('CATEGORY');
CREATE TRIGGER tr_ref_table_historique AFTER INSERT OR UPDATE OR DELETE ON ref_table
    FOR EACH ROW EXECUTE FUNCTION ref_journaliser('TABLE');
CREATE TRIGGER tr_ref_column_historique AFTER INSERT OR UPDATE OR DELETE ON ref_column
    FOR EACH ROW EXECUTE FUNCTION ref_journaliser('COLUMN');
CREATE TRIGGER tr_ref_entry_historique AFTER INSERT OR UPDATE OR DELETE ON ref_entry
    FOR EACH ROW EXECUTE FUNCTION ref_journaliser('ENTRY');

-- Horodatage et version automatiques
CREATE FUNCTION ref_touch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    NEW.version := OLD.version + 1;
    RETURN NEW;
END $$;
CREATE TRIGGER tr_ref_category_touch BEFORE UPDATE ON ref_category FOR EACH ROW EXECUTE FUNCTION ref_touch();
CREATE TRIGGER tr_ref_table_touch BEFORE UPDATE ON ref_table FOR EACH ROW EXECUTE FUNCTION ref_touch();
CREATE TRIGGER tr_ref_column_touch BEFORE UPDATE ON ref_column FOR EACH ROW EXECUTE FUNCTION ref_touch();
CREATE TRIGGER tr_ref_entry_touch BEFORE UPDATE ON ref_entry FOR EACH ROW EXECUTE FUNCTION ref_touch();

-- ---------------------------------------------------------------------------------------------
-- Journal des chargements (imports de fichiers, synchronisations, chargement initial)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE ref_import (
    id            VARCHAR(60)  PRIMARY KEY,
    table_code    VARCHAR(60)  NOT NULL,
    file_name     VARCHAR(300),
    format        VARCHAR(10)  NOT NULL,
    mode          VARCHAR(10)  NOT NULL CHECK (mode IN ('MERGE', 'REPLACE', 'SEED')),
    total         INT          NOT NULL DEFAULT 0,
    created       INT          NOT NULL DEFAULT 0,
    updated       INT          NOT NULL DEFAULT 0,
    unchanged     INT          NOT NULL DEFAULT 0,
    invalidated   INT          NOT NULL DEFAULT 0,
    rejected      INT          NOT NULL DEFAULT 0,
    errors        JSONB        NOT NULL DEFAULT '[]',
    author        VARCHAR(100) NOT NULL,
    channel       VARCHAR(10)  NOT NULL,
    reason        TEXT,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_ref_import_table ON ref_import (table_code, occurred_at DESC);

-- ---------------------------------------------------------------------------------------------
-- Vues générées : chaque table REF_xxx est exposée en SQL sous referentiel.ref_xxx, avec des
-- colonnes typées nommées d'après les balises XML / clés. Elles servent aux autres microservices
-- et aux extractions DataWarehouse / Big Data (exigence e-Guce+_REF-06).
-- ---------------------------------------------------------------------------------------------
CREATE SCHEMA referentiel;

CREATE FUNCTION ref_generer_vue(p_code TEXT) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    t      ref_table%ROWTYPE;
    c      ref_column%ROWTYPE;
    v_vue  TEXT := lower(p_code);
    v_cols TEXT := '';
    v_expr TEXT;
BEGIN
    EXECUTE format('DROP VIEW IF EXISTS referentiel.%I', v_vue);
    SELECT * INTO t FROM ref_table WHERE code = p_code;
    IF NOT FOUND OR t.status = 'ARCHIVED' THEN RETURN; END IF;
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
    END LOOP;
    EXECUTE format(
        'CREATE VIEW referentiel.%I AS SELECT e.id AS ref_id%s, e.parent_code AS ref_parent_code, '
        'e.valid_from AS ref_valid_from, e.valid_to AS ref_valid_to, e.status AS ref_status, '
        'e.updated_at AS ref_updated_at FROM public.ref_entry e WHERE e.table_id = %s',
        v_vue, v_cols, t.id);
    EXECUTE format('COMMENT ON VIEW referentiel.%I IS %L', v_vue, t.code || ' — ' || t.name_fr);
END $$;
