-- =============================================================================================
-- NEXORA · Microservice « procedures » — Single Window AI Studio (conception des procédures)
-- Dossier de conception v4.2 : terminologie GUCE (§10.9), procédure → version → flux → message → champ
-- (§10.3, §10.13), workflow BPMN (Operaton / Camunda 7), règles DRL (Drools), cycle de vie des artefacts
-- (§10.5-10.6), agents IA sous politique d'autonomie (§8.3), propositions IA revues par un humain.
-- Le paquet publié (JSON figé + empreinte SHA-256) est la seule interface avec le Runtime (eservices).
-- =============================================================================================
CREATE SCHEMA IF NOT EXISTS studio;

-- Nomenclatures harmonisées (§10.5)
CREATE TYPE studio.lifecycle_status AS ENUM ('draft', 'in_review', 'approved', 'published', 'superseded', 'retired');
CREATE TYPE studio.ai_review_status AS ENUM ('pending', 'accepted', 'corrected', 'rejected', 'superseded');
CREATE TYPE studio.autonomy_level   AS ENUM ('L1_assist', 'L2_supervised', 'L2_mandatory_review', 'L3_controlled', 'human_only');
CREATE TYPE studio.policy_decision  AS ENUM ('allow', 'require_human', 'deny');

-- ---------------------------------------------------------------------------------------------
-- 1. Terminologie du GUCE : Processus › Phase › Circuit › Procédure › Nature de la demande › Statut
-- ---------------------------------------------------------------------------------------------
CREATE TABLE studio.trade_process (
    code      VARCHAR(30)  PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    label_fr  VARCHAR(200) NOT NULL,
    label_en  VARCHAR(200),
    sort_order INT         NOT NULL DEFAULT 0
);
CREATE TABLE studio.process_phase (
    code          VARCHAR(30)  PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    process_code  VARCHAR(30)  NOT NULL REFERENCES studio.trade_process,
    label_fr      VARCHAR(200) NOT NULL,
    label_en      VARCHAR(200),
    bsp_area      VARCHAR(4)   CHECK (bsp_area IN ('BUY', 'SHIP', 'PAY')),
    sort_order    INT          NOT NULL DEFAULT 0
);
CREATE TABLE studio.circuit (
    code                VARCHAR(30)  PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    phase_code          VARCHAR(30)  NOT NULL REFERENCES studio.process_phase,
    label_fr            VARCHAR(200) NOT NULL,
    label_en            VARCHAR(200),
    shared_requirements TEXT,
    sort_order          INT          NOT NULL DEFAULT 0
);
CREATE TABLE studio.request_nature (
    code       VARCHAR(20)  PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    label_fr   VARCHAR(200) NOT NULL,
    label_en   VARCHAR(200),
    description TEXT,
    sort_order INT          NOT NULL DEFAULT 0
);

-- ---------------------------------------------------------------------------------------------
-- 2. Procédures et versions (le statut de cycle de vie est porté par la version, C21)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE studio.procedure (
    code          VARCHAR(30)  PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    label_fr      VARCHAR(250) NOT NULL,
    label_en      VARCHAR(250),
    circuit_code  VARCHAR(30)  NOT NULL REFERENCES studio.circuit,      -- RG-TERM-01
    authority     VARCHAR(200),                                          -- administration responsable
    description   TEXT,
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    tenant_id     VARCHAR(40)  NOT NULL DEFAULT 'CM',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by    VARCHAR(60)  NOT NULL
);

CREATE TABLE studio.procedure_version (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    procedure_code   VARCHAR(30)  NOT NULL REFERENCES studio.procedure,
    version_no       INT          NOT NULL CHECK (version_no > 0),
    lifecycle_status studio.lifecycle_status NOT NULL DEFAULT 'draft',
    -- Paramètres d'exécution : pays (CUR), monnaie, fuseau métier, champ « lieu de traitement », stratégie de cotation…
    settings         JSONB        NOT NULL DEFAULT '{}',
    change_note      TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by       VARCHAR(60)  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by       VARCHAR(60),
    submitted_at     TIMESTAMPTZ,  submitted_by VARCHAR(60),
    approved_at      TIMESTAMPTZ,  approved_by  VARCHAR(60),
    published_at     TIMESTAMPTZ,  published_by VARCHAR(60),
    retired_at       TIMESTAMPTZ,
    package          JSONB,                       -- paquet figé à la publication (immuable, RG-GOV-01)
    package_hash     CHAR(64),
    UNIQUE (procedure_code, version_no),
    CHECK ((lifecycle_status IN ('published', 'superseded', 'retired')) = (package IS NOT NULL) OR lifecycle_status = 'retired'),
    CHECK (approved_by IS NULL OR approved_by <> submitted_by)   -- RG-GOV-04 : l'auteur n'est pas l'unique approbateur
);
-- Une seule version publiée par procédure à un instant donné
CREATE UNIQUE INDEX ux_version_publiee ON studio.procedure_version (procedure_code) WHERE lifecycle_status = 'published';
-- Une seule version de travail (brouillon, en revue ou approuvée) par procédure
CREATE UNIQUE INDEX ux_version_travail ON studio.procedure_version (procedure_code) WHERE lifecycle_status IN ('draft', 'in_review', 'approved');

-- Types de demande (déclinaisons) : code de procédure du CUR-GUCE, natures autorisées
CREATE TABLE studio.request_type (
    version_id   BIGINT       NOT NULL REFERENCES studio.procedure_version ON DELETE CASCADE,
    code         VARCHAR(20)  NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    label_fr     VARCHAR(250) NOT NULL,
    label_en     VARCHAR(250),
    cur_code     VARCHAR(3)   NOT NULL CHECK (cur_code ~ '^[A-Z]{3}$'),     -- ex. IFI, IFE, ASC (CUR = PAYS ANNÉE CODE SÉQ)
    natures      TEXT[]       NOT NULL DEFAULT '{INITIAL}',
    description  TEXT,
    sort_order   INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (version_id, code),
    UNIQUE (version_id, cur_code)
);

-- Acteurs (rôles d'organismes dans la procédure, C17)
CREATE TABLE studio.procedure_actor (
    version_id     BIGINT       NOT NULL REFERENCES studio.procedure_version ON DELETE CASCADE,
    code           VARCHAR(30)  NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    label_fr       VARCHAR(250) NOT NULL,
    label_en       VARCHAR(250),
    kind           VARCHAR(20)  NOT NULL CHECK (kind IN ('DEMANDEUR', 'ADMINISTRATION', 'PARTENAIRE', 'BANQUE', 'SYSTEME')),
    system_code    VARCHAR(20)  NOT NULL DEFAULT 'EGOV' CHECK (system_code IN ('EFORCE', 'EGOV', 'EPAYMENT', 'EXTERNE')),
    auth_role      VARCHAR(40),                            -- rôle du microservice auth qui incarne l'acteur
    responsibilities TEXT,
    sort_order     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (version_id, code)
);

-- Statuts du dossier (status_model / status_value de la terminologie GUCE)
CREATE TABLE studio.case_status (
    version_id  BIGINT       NOT NULL REFERENCES studio.procedure_version ON DELETE CASCADE,
    code        VARCHAR(30)  NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    label_fr    VARCHAR(200) NOT NULL,
    label_en    VARCHAR(200),
    is_final    BOOLEAN      NOT NULL DEFAULT false,
    tone        VARCHAR(10)  NOT NULL DEFAULT 'neutral' CHECK (tone IN ('neutral', 'info', 'warn', 'ok', 'danger')),
    sort_order  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (version_id, code)
);

-- ---------------------------------------------------------------------------------------------
-- 3. Formulaire et contenu des échanges : champs reliés aux éléments de données (UNTDED, OMD, UN/CCL, SCRDM)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE studio.form_section (
    version_id  BIGINT       NOT NULL REFERENCES studio.procedure_version ON DELETE CASCADE,
    code        VARCHAR(30)  NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    label_fr    VARCHAR(200) NOT NULL,
    label_en    VARCHAR(200),
    repeating   BOOLEAN      NOT NULL DEFAULT false,        -- groupe répétitif (ex. produits)
    min_rows    INT          NOT NULL DEFAULT 0,
    sort_order  INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (version_id, code)
);

CREATE TABLE studio.form_field (
    version_id     BIGINT       NOT NULL REFERENCES studio.procedure_version ON DELETE CASCADE,
    code           VARCHAR(60)  NOT NULL CHECK (code ~ '^[a-z][A-Za-z0-9]*$'),
    section_code   VARCHAR(30)  NOT NULL,
    label_fr       VARCHAR(250) NOT NULL,
    label_en       VARCHAR(250),
    data_type      VARCHAR(12)  NOT NULL CHECK (data_type IN ('TEXT', 'NUMBER', 'DECIMAL', 'DATE', 'YEAR', 'CODE', 'EMAIL', 'PHONE', 'BOOLEAN', 'GEOLOCATION', 'LONGTEXT')),
    max_length     INT          CHECK (max_length > 0),
    pattern        VARCHAR(250),
    mandatory      VARCHAR(12)  NOT NULL DEFAULT 'OUI' CHECK (mandatory IN ('OUI', 'NON', 'CONDITIONNEL')),
    condition_text VARCHAR(500),                            -- condition d'obligation (texte + règle DRL)
    refdata_table  VARCHAR(60),                             -- liste de codes du Référentiel Commun (RG-REF-01)
    code_values    JSONB,                                   -- valeurs locales [{code, labelFr, labelEn}]
    untded_ref     VARCHAR(10),                             -- ISO 7372
    wco_ref        VARCHAR(200),                            -- modèle de données de l'OMD
    ccl_den        VARCHAR(300),                            -- UN/CCL Dictionary Entry Name
    ccl_uid        VARCHAR(20),                             -- Unique UN Assigned ID
    bie_path       VARCHAR(400),                            -- chemin SCRDM (RG-REF-02)
    searchable     BOOLEAN      NOT NULL DEFAULT false,     -- critère de recherche indexé (wfe.dossier_type_criterion)
    prefill        VARCHAR(60),                             -- attribut du registre des opérateurs pour le pré-remplissage
    amendable      BOOLEAN      NOT NULL DEFAULT true,      -- modifiable par une demande d'amendement
    help_fr        TEXT,
    help_en        TEXT,
    sort_order     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (version_id, code),
    FOREIGN KEY (version_id, section_code) REFERENCES studio.form_section ON DELETE CASCADE
);

-- Pièces à fournir (P1…Pn) et conditions
CREATE TABLE studio.required_document (
    version_id     BIGINT       NOT NULL REFERENCES studio.procedure_version ON DELETE CASCADE,
    code           VARCHAR(10)  NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    label_fr       VARCHAR(300) NOT NULL,
    label_en       VARCHAR(300),
    mandatory      BOOLEAN      NOT NULL DEFAULT true,
    condition_text VARCHAR(500),
    request_types  TEXT[]       NOT NULL DEFAULT '{}',      -- vide = tous
    observation    VARCHAR(500),
    sort_order     INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (version_id, code)
);

-- Frais et répartition par bénéficiaire (la règle DRL « frais » en est générée)
CREATE TABLE studio.fee_item (
    version_id     BIGINT        NOT NULL REFERENCES studio.procedure_version ON DELETE CASCADE,
    code           VARCHAR(30)   NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    beneficiary    VARCHAR(30)   NOT NULL,                  -- acteur bénéficiaire (MINCOMMERCE, CNCC, GUCE, DGI…)
    label_fr       VARCHAR(250)  NOT NULL,
    label_en       VARCHAR(250),
    amount         NUMERIC(16,2) NOT NULL CHECK (amount >= 0),
    currency       CHAR(3)       NOT NULL DEFAULT 'XAF',
    request_types  TEXT[]        NOT NULL DEFAULT '{}',     -- vide = tous
    natures        TEXT[]        NOT NULL DEFAULT '{}',     -- vide = toutes
    sort_order     INT           NOT NULL DEFAULT 0,
    PRIMARY KEY (version_id, code)
);

-- ---------------------------------------------------------------------------------------------
-- 4. Catalogue de messages et flux (contrat séparé de son usage, C15-C18)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE studio.message_definition (
    version_id     BIGINT       NOT NULL REFERENCES studio.procedure_version ON DELETE CASCADE,
    code           VARCHAR(40)  NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    label_fr       VARCHAR(250) NOT NULL,
    label_en       VARCHAR(250),
    message_type   VARCHAR(20)  NOT NULL CHECK (message_type IN ('REQUEST', 'RESPONSE', 'NOTIFICATION', 'DECISION', 'PAYMENT', 'ACKNOWLEDGEMENT', 'ERROR', 'REPORT', 'DOCUMENT')),
    content_format VARCHAR(10)  NOT NULL CHECK (content_format IN ('JSON', 'XML', 'CSV', 'TXT', 'EDIFACT', 'PDF')),
    root_element   VARCHAR(80),                              -- élément racine XML / objet JSON
    standard_ref   VARCHAR(200),                             -- ex. SCRDM D23B, WCO DM 4.x, UN/EDIFACT GOVCBR
    schema_text    TEXT,                                     -- JSON Schema / XSD (optionnel)
    sensitivity    VARCHAR(15)  NOT NULL DEFAULT 'internal' CHECK (sensitivity IN ('public', 'internal', 'confidential', 'restricted', 'personal_data')),
    description    TEXT,
    PRIMARY KEY (version_id, code)
);
-- Champs portés par le message (chemin dans le contenu ; le champ de formulaire donne l'élément de données)
CREATE TABLE studio.message_field (
    version_id     BIGINT       NOT NULL,
    message_code   VARCHAR(40)  NOT NULL,
    seq            INT          NOT NULL,
    field_code     VARCHAR(60),                              -- champ du formulaire, ou NULL pour un élément d'enveloppe
    path           VARCHAR(300) NOT NULL,                    -- JSONPath / XPath / colonne CSV / clé TXT
    mandatory      BOOLEAN      NOT NULL DEFAULT false,
    indexed        BOOLEAN      NOT NULL DEFAULT false,      -- copié dans payload_value (RG-TRX-04)
    PRIMARY KEY (version_id, message_code, seq),
    FOREIGN KEY (version_id, message_code) REFERENCES studio.message_definition ON DELETE CASCADE
);

CREATE TABLE studio.procedure_flow (
    version_id   BIGINT       NOT NULL REFERENCES studio.procedure_version ON DELETE CASCADE,
    code         VARCHAR(30)  NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    label_fr     VARCHAR(250) NOT NULL,
    label_en     VARCHAR(250),
    sort_order   INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (version_id, code)
);
CREATE TABLE studio.flow_message (
    version_id          BIGINT       NOT NULL,
    flow_code           VARCHAR(30)  NOT NULL,
    seq                 INT          NOT NULL CHECK (seq > 0),
    message_code        VARCHAR(40)  NOT NULL,
    sender_actor        VARCHAR(30)  NOT NULL,
    receiver_actor      VARCHAR(30)  NOT NULL,
    interaction_pattern VARCHAR(12)  NOT NULL DEFAULT 'ASYNC' CHECK (interaction_pattern IN ('SYNC', 'ASYNC', 'NOTIFY')),
    response_to_seq     INT,
    timeout_hours       INT          CHECK (timeout_hours >= 0),
    PRIMARY KEY (version_id, flow_code, seq),
    FOREIGN KEY (version_id, flow_code) REFERENCES studio.procedure_flow ON DELETE CASCADE,
    FOREIGN KEY (version_id, message_code) REFERENCES studio.message_definition ON DELETE CASCADE,
    FOREIGN KEY (version_id, sender_actor) REFERENCES studio.procedure_actor ON DELETE CASCADE,
    FOREIGN KEY (version_id, receiver_actor) REFERENCES studio.procedure_actor ON DELETE CASCADE,
    FOREIGN KEY (version_id, flow_code, response_to_seq) REFERENCES studio.flow_message (version_id, flow_code, seq),
    CHECK (sender_actor <> receiver_actor)
);

-- ---------------------------------------------------------------------------------------------
-- 5. Workflow (BPMN 2.0, moteur Operaton / Camunda 7), règles (DRL Drools), livrables
-- ---------------------------------------------------------------------------------------------
CREATE TABLE studio.process_model (
    version_id   BIGINT       PRIMARY KEY REFERENCES studio.procedure_version ON DELETE CASCADE,
    process_key  VARCHAR(60)  NOT NULL CHECK (process_key ~ '^[A-Za-z][A-Za-z0-9_-]*$'),
    bpmn_xml     TEXT         NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by   VARCHAR(60)
);
CREATE TABLE studio.rule_set (
    version_id   BIGINT       PRIMARY KEY REFERENCES studio.procedure_version ON DELETE CASCADE,
    drl          TEXT         NOT NULL,
    business_rules JSONB      NOT NULL DEFAULT '[]',        -- règles de gestion en clair (RG / RM du SFD) et leur traduction
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by   VARCHAR(60)
);
CREATE TABLE studio.deliverable (
    version_id     BIGINT       NOT NULL REFERENCES studio.procedure_version ON DELETE CASCADE,
    code           VARCHAR(30)  NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    label_fr       VARCHAR(250) NOT NULL,
    label_en       VARCHAR(250),
    doc_code       VARCHAR(3)   NOT NULL CHECK (doc_code ~ '^[A-Z]{3}$'),   -- numéro = CUR du dossier + code + amendement (RG-CUR-02)
    validity_rule  VARCHAR(30)  NOT NULL DEFAULT 'FIN_ANNEE' CHECK (validity_rule IN ('FIN_ANNEE', 'UN_AN', 'AUCUNE')),
    title_fr       VARCHAR(300),
    body_fields    TEXT[]       NOT NULL DEFAULT '{}',      -- champs du formulaire reportés sur le livrable
    signature_note VARCHAR(300),
    PRIMARY KEY (version_id, code)
);

-- ---------------------------------------------------------------------------------------------
-- 6. Agents IA, politique d'autonomie par action (§8.3), exécutions tracées et propositions revues
-- ---------------------------------------------------------------------------------------------
CREATE TABLE studio.agent (
    code         VARCHAR(30)  PRIMARY KEY,
    label_fr     VARCHAR(200) NOT NULL,
    label_en     VARCHAR(200),
    product      VARCHAR(60)  NOT NULL,
    description  TEXT,
    is_enabled   BOOLEAN      NOT NULL DEFAULT true,          -- coupe-circuit (AC-AG-06)
    sort_order   INT          NOT NULL DEFAULT 0
);
CREATE TABLE studio.agent_policy (
    agent_code   VARCHAR(30)  NOT NULL REFERENCES studio.agent,
    action_code  VARCHAR(40)  NOT NULL,
    max_level    studio.autonomy_level NOT NULL,
    PRIMARY KEY (agent_code, action_code)
);
CREATE TABLE studio.ai_run (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_run_id    BIGINT       REFERENCES studio.ai_run,
    agent_code       VARCHAR(30)  NOT NULL REFERENCES studio.agent,
    version_id       BIGINT       REFERENCES studio.procedure_version ON DELETE CASCADE,
    objective        TEXT         NOT NULL,
    action_code      VARCHAR(40)  NOT NULL,
    autonomy_level   studio.autonomy_level NOT NULL,
    policy_decision  studio.policy_decision NOT NULL,
    model            VARCHAR(80)  NOT NULL,                  -- « heuristique » ou identifiant du modèle IA
    input_hash       CHAR(64),
    status           VARCHAR(12)  NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'DONE', 'FAILED', 'DENIED')),
    summary          TEXT,
    proposals        INT          NOT NULL DEFAULT 0,
    input_tokens     INT, output_tokens INT,
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ended_at         TIMESTAMPTZ,
    started_by       VARCHAR(60)  NOT NULL
);
CREATE INDEX ix_ai_run_version ON studio.ai_run (version_id, started_at DESC);

CREATE TABLE studio.ai_proposal (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id          BIGINT       NOT NULL REFERENCES studio.ai_run ON DELETE CASCADE,
    version_id      BIGINT       NOT NULL REFERENCES studio.procedure_version ON DELETE CASCADE,
    kind            VARCHAR(30)  NOT NULL,                   -- FIELD_MAPPING, DOCUMENT, ACTOR, FEE, BUSINESS_RULE, BPMN, DRL, FINDING…
    target          VARCHAR(120),                            -- objet visé (code de champ, de pièce…)
    title           VARCHAR(300) NOT NULL,
    payload         JSONB        NOT NULL,
    rationale       TEXT,
    source_excerpt  TEXT,                                    -- extrait source (preuve, AP-04)
    proposal_type   VARCHAR(12)  NOT NULL DEFAULT 'inference' CHECK (proposal_type IN ('extraction', 'inference', 'generation', 'check')),
    confidence      NUMERIC(4,3) CHECK (confidence BETWEEN 0 AND 1),
    review_status   studio.ai_review_status NOT NULL DEFAULT 'pending',
    reviewed_by     VARCHAR(60),
    reviewed_at     TIMESTAMPTZ,
    review_comment  TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_ai_proposal_version ON studio.ai_proposal (version_id, review_status);

-- ---------------------------------------------------------------------------------------------
-- 7. Journal d'audit chaîné (append-only, prev_hash / row_hash)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE studio.audit_event (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor          VARCHAR(60)  NOT NULL,
    channel        VARCHAR(10)  NOT NULL DEFAULT 'API',
    action_code    VARCHAR(60)  NOT NULL,
    procedure_code VARCHAR(30),
    version_id     BIGINT,
    ai_run_id      BIGINT,
    policy_decision studio.policy_decision,
    reason         TEXT,
    details        JSONB        NOT NULL DEFAULT '{}',
    prev_hash      CHAR(64),
    row_hash       CHAR(64)     NOT NULL
);
CREATE INDEX ix_audit_version ON studio.audit_event (version_id, occurred_at DESC);

CREATE FUNCTION studio.audit_chainer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(4242);
  SELECT row_hash INTO NEW.prev_hash FROM studio.audit_event ORDER BY id DESC LIMIT 1;
  NEW.row_hash := encode(sha256(convert_to(coalesce(NEW.prev_hash, '') || NEW.occurred_at::text || NEW.actor || NEW.action_code
                  || coalesce(NEW.version_id::text, '') || coalesce(NEW.reason, '') || NEW.details::text, 'UTF8')), 'hex');
  RETURN NEW;
END $$;
CREATE TRIGGER audit_chainage BEFORE INSERT ON studio.audit_event FOR EACH ROW EXECUTE FUNCTION studio.audit_chainer();
CREATE FUNCTION studio.audit_immuable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Le journal d''audit est en ajout seul (RG-GOV-03).'; END $$;
CREATE TRIGGER audit_lecture_seule BEFORE UPDATE OR DELETE ON studio.audit_event FOR EACH ROW EXECUTE FUNCTION studio.audit_immuable();

-- Un paquet publié est immuable (RG-GOV-01)
CREATE FUNCTION studio.version_immuable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.package IS NOT NULL AND (NEW.package IS DISTINCT FROM OLD.package OR NEW.package_hash IS DISTINCT FROM OLD.package_hash) THEN
    RAISE EXCEPTION 'Une version publiée est immuable : créez une nouvelle version.';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER version_immuable BEFORE UPDATE ON studio.procedure_version FOR EACH ROW EXECUTE FUNCTION studio.version_immuable();

-- ---------------------------------------------------------------------------------------------
-- Données livrées : terminologie GUCE, agents et politique d'autonomie
-- ---------------------------------------------------------------------------------------------
INSERT INTO studio.trade_process (code, label_fr, label_en, sort_order) VALUES
('IMPORT', 'Importation', 'Import', 10), ('EXPORT', 'Exportation', 'Export', 20), ('TRANSIT', 'Transit', 'Transit', 30),
('TRANSVERSAL', 'Formalités transversales', 'Cross-cutting formalities', 40);
INSERT INTO studio.process_phase (code, process_code, label_fr, label_en, bsp_area, sort_order) VALUES
('PRE_CLEARANCE', 'TRANSVERSAL', 'Préalables (avant opération)', 'Prerequisites (before operation)', 'BUY', 10),
('IMP_PRE_CLEARANCE', 'IMPORT', 'Pré-dédouanement à l''import', 'Import pre-clearance', 'BUY', 20),
('IMP_CLEARANCE', 'IMPORT', 'Dédouanement à l''import', 'Import clearance', 'SHIP', 30),
('IMP_RELEASE', 'IMPORT', 'Enlèvement à l''import', 'Import release', 'SHIP', 40),
('EXP_PRE_CLEARANCE', 'EXPORT', 'Pré-dédouanement à l''export', 'Export pre-clearance', 'BUY', 50),
('EXP_CLEARANCE', 'EXPORT', 'Dédouanement à l''export', 'Export clearance', 'SHIP', 60),
('SETTLEMENT', 'TRANSVERSAL', 'Paiement et règlement', 'Payment and settlement', 'PAY', 70);
INSERT INTO studio.circuit (code, phase_code, label_fr, label_en, shared_requirements, sort_order) VALUES
('PREALABLES', 'PRE_CLEARANCE', 'Circuit Préalables', 'Prerequisites circuit', 'Enrôlement au guichet unique, NIU, RCCM, patente', 10),
('TRANSACTION', 'IMP_PRE_CLEARANCE', 'Circuit Transaction', 'Transaction circuit', 'CUR de transaction, facture, domiciliation', 20),
('EXPEDITION', 'IMP_CLEARANCE', 'Circuit Expédition', 'Shipment circuit', 'Références de transport (BL, LTA, CMR)', 30),
('PRISE_EN_CHARGE', 'IMP_RELEASE', 'Circuit Prise en charge', 'Pick-up circuit', 'Bon à enlever, enlèvement partiel', 40);
INSERT INTO studio.request_nature (code, label_fr, label_en, description, sort_order) VALUES
('INITIAL', 'Demande initiale', 'Initial request', 'Première demande', 10),
('RENEWAL', 'Renouvellement', 'Renewal', 'Renouvellement d''un titre arrivé ou arrivant à expiration', 20),
('AMENDMENT', 'Amendement', 'Amendment', 'Modification d''un titre délivré', 30),
('EXTENSION', 'Prorogation', 'Extension', 'Prolongation de la validité', 40),
('CANCELLATION', 'Annulation', 'Cancellation', 'Annulation d''une demande ou d''un titre', 50);

INSERT INTO studio.agent (code, label_fr, label_en, product, description, sort_order) VALUES
('ORCHESTRATEUR', 'AI Orchestrator', 'AI Orchestrator', 'Plateforme', 'Décompose l''objectif, enchaîne les agents, applique la politique d''autonomie et trace chaque exécution.', 10),
('DOCUMENT', 'Document Agent', 'Document Agent', 'Agent technique', 'Extrait d''une SFD ou d''un texte réglementaire les pièces, acteurs, frais, délais et règles de gestion, avec l''extrait source.', 20),
('DONNEES', 'Data Agent', 'Data Agent', 'Trade Data Mapper', 'Rapproche chaque champ du SCRDM D23B (chemin BIE, DEN) et des listes de codes du Référentiel Commun.', 30),
('PROCESSUS', 'Single Window Agent — Process Design', 'Single Window Agent — Process Design', 'Single Window AI', 'Génère le workflow BPMN 2.0 exécutable (Operaton / Camunda 7) à partir des acteurs, statuts, frais et décisions.', 40),
('REGLES', 'Single Window Agent — Rules', 'Single Window Agent — Rules', 'Single Window AI', 'Traduit les frais, pièces conditionnelles et règles de gestion en règles DRL (Drools) testables.', 50),
('CONFORMITE', 'Compliance Agent', 'Compliance Agent', 'Transversal', 'Contrôle la version avant soumission et publication (terminologie, listes de codes, flux, messages, BPMN, DRL, séparation des tâches).', 60);
-- Les agents proposent (L2) ; appliquer une proposition exige une revue humaine ; publier est réservé à l'humain.
INSERT INTO studio.agent_policy (agent_code, action_code, max_level)
SELECT a.code, p.action, p.level::studio.autonomy_level
FROM studio.agent a CROSS JOIN (VALUES ('proposer', 'L2_supervised'), ('appliquer', 'L2_mandatory_review'),
                                       ('soumettre', 'human_only'), ('approuver', 'human_only'), ('publier', 'human_only')) AS p(action, level);
UPDATE studio.agent_policy SET max_level = 'L3_controlled' WHERE agent_code = 'CONFORMITE' AND action_code = 'proposer';
