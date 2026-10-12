-- =============================================================================================
-- NEXORA · Microservice « eservices » — Runtime du guichet unique (SW AI Runtime)
-- Moteur de workflow des échanges (WFE) d'après « NEXORA_WFE_SCRDM_Modele_Donnees_PG17 » v1.0 et le dossier de
-- conception v4.2 (§10.12, §10.14) : procédures déployées (paquets publiés par le studio), dossiers identifiés par
-- leur CUR, critères de recherche normalisés, flux, messages (JSON, XML, CSV, TXT) et leur contenu, pièces jointes,
-- avis de paiement, livrables, cotation. Le moteur BPMN Operaton crée ses propres tables ACT_* (schéma public).
-- =============================================================================================
CREATE SCHEMA IF NOT EXISTS wfe;

CREATE TYPE wfe.direction AS ENUM ('IN', 'OUT', 'INTERNAL');
CREATE TYPE wfe.lifecycle AS ENUM ('DRAFT', 'ACTIVE', 'CLOSED', 'CANCELLED', 'ARCHIVED');

-- ---------------------------------------------------------------------------------------------
-- 1. Référentiels du moteur
-- ---------------------------------------------------------------------------------------------
CREATE TABLE wfe.partner_type (
    partner_type_code VARCHAR(20)  PRIMARY KEY,
    label             VARCHAR(250) NOT NULL
);
INSERT INTO wfe.partner_type VALUES
('DEMANDEUR', 'Opérateur économique (importateur, exportateur, CDA)'), ('ADMINISTRATION', 'Administration'),
('PARTENAIRE', 'Organisme partenaire'), ('BANQUE', 'Banque'), ('SYSTEME', 'Système d''information');

-- Partenaires des échanges (W03, W16) : identifiant d'échange + qualifiant UNTDID 0007 + tenant
CREATE TABLE wfe.partner (
    partner_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         VARCHAR(40)  NOT NULL DEFAULT 'CM',
    edi_id            VARCHAR(35)  NOT NULL,
    edi_id_qualifier  VARCHAR(4)   NOT NULL DEFAULT 'ZZZ',
    name              VARCHAR(200) NOT NULL,
    partner_type_code VARCHAR(20)  NOT NULL REFERENCES wfe.partner_type,
    country_code      CHAR(2)      CHECK (country_code ~ '^[A-Z]{2}$'),
    is_active         BOOLEAN      NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, edi_id_qualifier, edi_id)
);

-- États des messages (W05) et transitions autorisées
CREATE TABLE wfe.message_state (
    state_code VARCHAR(16)  PRIMARY KEY,
    direction  wfe.direction NOT NULL,
    label      VARCHAR(200) NOT NULL,
    is_final   BOOLEAN      NOT NULL DEFAULT false
);
INSERT INTO wfe.message_state VALUES
('IN_RECEIVED', 'IN', 'Reçu', false), ('IN_VALID', 'IN', 'Contenu valide', false), ('IN_REJECTED', 'IN', 'Rejeté (contenu invalide ou doublon)', true),
('IN_PROCESSED', 'IN', 'Traité', true), ('OUT_CREATED', 'OUT', 'Créé', false), ('OUT_SENT', 'OUT', 'Émis', false),
('OUT_ACKED', 'OUT', 'Accusé de réception', true), ('OUT_ERROR', 'OUT', 'Erreur d''émission', false), ('ARCHIVED', 'INTERNAL', 'Archivé', true);
CREATE TABLE wfe.message_state_transition (
    from_state VARCHAR(16) NOT NULL REFERENCES wfe.message_state,
    to_state   VARCHAR(16) NOT NULL REFERENCES wfe.message_state,
    PRIMARY KEY (from_state, to_state),
    CHECK (from_state <> to_state)
);
INSERT INTO wfe.message_state_transition VALUES
('IN_RECEIVED', 'IN_VALID'), ('IN_RECEIVED', 'IN_REJECTED'), ('IN_VALID', 'IN_PROCESSED'), ('IN_VALID', 'IN_REJECTED'),
('IN_PROCESSED', 'ARCHIVED'), ('OUT_CREATED', 'OUT_SENT'), ('OUT_CREATED', 'OUT_ERROR'), ('OUT_SENT', 'OUT_ACKED'),
('OUT_SENT', 'OUT_ERROR'), ('OUT_ERROR', 'OUT_SENT'), ('OUT_ACKED', 'ARCHIVED');

-- ---------------------------------------------------------------------------------------------
-- 2. Procédures déployées : paquets publiés par le studio (immuables, empreinte SHA-256)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE wfe.procedure_deployment (
    deployment_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    procedure_code        VARCHAR(30)  NOT NULL,
    version_no            INT          NOT NULL,
    label_fr              VARCHAR(250) NOT NULL,
    label_en              VARCHAR(250),
    package               JSONB        NOT NULL,
    package_hash          CHAR(64)     NOT NULL,
    process_key           VARCHAR(60)  NOT NULL,
    process_definition_id VARCHAR(100) NOT NULL,      -- définition déployée dans Operaton
    engine_deployment_id  VARCHAR(64)  NOT NULL,
    is_active             BOOLEAN      NOT NULL DEFAULT true,
    deployed_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deployed_by           VARCHAR(60)  NOT NULL,
    UNIQUE (procedure_code, version_no)
);
-- Une seule version active par procédure ; les dossiers en cours gardent leur version (RG-TRX-01)
CREATE UNIQUE INDEX ux_deployment_active ON wfe.procedure_deployment (procedure_code) WHERE is_active;

-- Lieux de traitement, unités administratives et agents (cotation, SFD FIMEX v1.3)
CREATE TABLE wfe.admin_unit (
    unit_code   VARCHAR(20)  PRIMARY KEY CHECK (unit_code ~ '^[A-Z][A-Z0-9_]*$'),
    label       VARCHAR(250) NOT NULL,
    authority   VARCHAR(60)  NOT NULL DEFAULT 'MINCOMMERCE',
    is_active   BOOLEAN      NOT NULL DEFAULT true
);
CREATE TABLE wfe.processing_location (
    location_code VARCHAR(20)  PRIMARY KEY CHECK (location_code ~ '^[A-Z][A-Z0-9_]*$'),
    label_fr      VARCHAR(200) NOT NULL,
    label_en      VARCHAR(200),
    unit_code     VARCHAR(20)  NOT NULL REFERENCES wfe.admin_unit,
    sort_order    INT          NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT true
);
CREATE TABLE wfe.unit_member (
    unit_code   VARCHAR(20)  NOT NULL REFERENCES wfe.admin_unit,
    username    VARCHAR(60)  NOT NULL,
    role        VARCHAR(12)  NOT NULL CHECK (role IN ('TRAITEMENT', 'SIGNATAIRE')),
    full_name   VARCHAR(150),
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    PRIMARY KEY (unit_code, username, role)
);

-- Code Unique de Référence (CUR-GUCE) : séquence par (pays, année, code procédure), génération concurrente sûre
CREATE TABLE wfe.cur_sequence (
    country_code CHAR(2)    NOT NULL,
    cur_year     SMALLINT   NOT NULL,
    op_code      VARCHAR(3) NOT NULL,
    last_value   INT        NOT NULL DEFAULT 0 CHECK (last_value <= 999999),
    PRIMARY KEY (country_code, cur_year, op_code)
);

-- ---------------------------------------------------------------------------------------------
-- 3. Dossiers (classes transaction §10.14) : dossier_no = CUR (W12), critères normalisés (W01, W02)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE wfe.dossier (
    dossier_id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    dossier_no          VARCHAR(35)  UNIQUE CHECK (dossier_no ~ '^[A-Z0-9]{14,35}$'),   -- attribué au dépôt (RG-CUR-01)
    tenant_id           VARCHAR(40)  NOT NULL DEFAULT 'CM',
    deployment_id       BIGINT       NOT NULL REFERENCES wfe.procedure_deployment,
    procedure_code      VARCHAR(30)  NOT NULL,
    version_no          INT          NOT NULL,
    request_type        VARCHAR(20)  NOT NULL,
    request_nature      VARCHAR(20)  NOT NULL DEFAULT 'INITIAL',
    parent_dossier_no   VARCHAR(35)  REFERENCES wfe.dossier (dossier_no),            -- dossier source (renouvellement, amendement)
    applicant_niu       VARCHAR(35),
    applicant_name      VARCHAR(200),
    applicant_partner_id BIGINT      REFERENCES wfe.partner,
    submitted_by        VARCHAR(60)  NOT NULL,                                       -- compte eFORCE (opérateur ou CDA)
    on_behalf           BOOLEAN      NOT NULL DEFAULT false,                          -- dépôt pour un tiers (CDA)
    status              VARCHAR(30)  NOT NULL DEFAULT 'BROUILLON',
    lifecycle           wfe.lifecycle NOT NULL DEFAULT 'DRAFT',
    processing_location VARCHAR(20),
    admin_unit          VARCHAR(20),
    assigned_agent      VARCHAR(60),
    signatory           VARCHAR(60),
    process_instance_id VARCHAR(64),
    form_data           JSONB        NOT NULL DEFAULT '{}',                           -- champs et groupes répétitifs du formulaire
    previous_data       JSONB,                                                        -- valeurs avant amendement (RG2 UC-03)
    search_criteria     JSONB        NOT NULL DEFAULT '{}',                           -- projection des critères (GIN)
    fees                JSONB        NOT NULL DEFAULT '[]',
    total_amount        NUMERIC(16,2) NOT NULL DEFAULT 0,
    currency            CHAR(3)      NOT NULL DEFAULT 'XAF',
    last_observation    TEXT,
    sla_due_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    submitted_at        TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at           TIMESTAMPTZ,
    CHECK ((dossier_no IS NULL) = (lifecycle = 'DRAFT'))
);
CREATE INDEX ix_dossier_procedure ON wfe.dossier (procedure_code, status);
CREATE INDEX ix_dossier_applicant ON wfe.dossier (applicant_niu, procedure_code);
CREATE INDEX ix_dossier_submitter ON wfe.dossier (submitted_by, created_at DESC);
CREATE INDEX ix_dossier_agent ON wfe.dossier (assigned_agent) WHERE lifecycle = 'ACTIVE';
CREATE INDEX ix_dossier_criteria ON wfe.dossier USING gin (search_criteria jsonb_path_ops);

CREATE TABLE wfe.dossier_criterion_value (
    dossier_id      UUID          NOT NULL REFERENCES wfe.dossier ON DELETE CASCADE,
    criterion_code  VARCHAR(60)   NOT NULL,
    occurrence      SMALLINT      NOT NULL DEFAULT 1,
    value_text      VARCHAR(1000),
    PRIMARY KEY (dossier_id, criterion_code, occurrence)
);
CREATE INDEX ix_criterion_lookup ON wfe.dossier_criterion_value (criterion_code, value_text);

-- Projection automatique des critères dans dossier.search_criteria (recherche @> par index GIN)
CREATE FUNCTION wfe.trg_criteria_projection() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE d uuid := COALESCE(NEW.dossier_id, OLD.dossier_id);
BEGIN
  UPDATE wfe.dossier SET search_criteria = COALESCE((
     SELECT jsonb_object_agg(criterion_code, vals) FROM (
       SELECT criterion_code, jsonb_agg(value_text ORDER BY occurrence) AS vals
         FROM wfe.dossier_criterion_value WHERE dossier_id = d GROUP BY criterion_code) s), '{}')
   WHERE dossier_id = d;
  RETURN NULL;
END $$;
CREATE TRIGGER criteria_projection AFTER INSERT OR UPDATE OR DELETE ON wfe.dossier_criterion_value
  FOR EACH ROW EXECUTE FUNCTION wfe.trg_criteria_projection();

-- Historique des statuts (terminologie GUCE), en ajout seul (RG-TRX-05)
CREATE TABLE wfe.dossier_status_history (
    history_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dossier_id   UUID         NOT NULL REFERENCES wfe.dossier ON DELETE CASCADE,
    from_status  VARCHAR(30),
    to_status    VARCHAR(30)  NOT NULL,
    activity_id  VARCHAR(100),                       -- nœud BPMN
    actor        VARCHAR(60)  NOT NULL,
    reason       TEXT,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_status_history ON wfe.dossier_status_history (dossier_id, occurred_at);
CREATE FUNCTION wfe.ajout_seul() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Table en ajout seul : % (RG-GOV-03).', TG_TABLE_NAME; END $$;
CREATE TRIGGER status_history_ajout_seul BEFORE UPDATE OR DELETE ON wfe.dossier_status_history
  FOR EACH ROW EXECUTE FUNCTION wfe.ajout_seul();

-- Cotation : trace de chaque affectation (RM04)
CREATE TABLE wfe.cotation_history (
    cotation_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dossier_id    UUID         NOT NULL REFERENCES wfe.dossier ON DELETE CASCADE,
    location_code VARCHAR(20)  NOT NULL,
    unit_code     VARCHAR(20)  NOT NULL,
    agent         VARCHAR(60)  NOT NULL,
    signatory     VARCHAR(60),
    candidates    INT          NOT NULL,
    strategy      VARCHAR(30)  NOT NULL,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------------------------
-- 4. Flux et messages (W03, W05, W06, W08, W09) : contenu séparé, empreinte, idempotence, corrélation
-- ---------------------------------------------------------------------------------------------
CREATE TABLE wfe.flux (
    flux_id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dossier_id           UUID         NOT NULL REFERENCES wfe.dossier ON DELETE CASCADE,
    flow_code            VARCHAR(30)  NOT NULL,                    -- flux du paquet (procedure_flow)
    label                VARCHAR(250) NOT NULL,
    status               wfe.lifecycle NOT NULL DEFAULT 'ACTIVE',
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (dossier_id, flow_code)
);

CREATE TABLE wfe.message (
    message_id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),   -- eb:MessageId
    tenant_id           VARCHAR(40)  NOT NULL DEFAULT 'CM',
    flux_id             BIGINT       REFERENCES wfe.flux,
    dossier_id          UUID         REFERENCES wfe.dossier,
    dossier_no          VARCHAR(35),                                          -- corrélation métier (CUR)
    message_code        VARCHAR(40)  NOT NULL,                                -- catalogue (eb:Service + eb:Action)
    message_type        VARCHAR(20)  NOT NULL,
    flow_seq            INT,
    direction           wfe.direction NOT NULL,
    sender_actor        VARCHAR(30),                                          -- rôles de procedure_actor
    recipient_actor     VARCHAR(30),
    sender_partner_id   BIGINT       REFERENCES wfe.partner,                  -- eb:From
    recipient_partner_id BIGINT      REFERENCES wfe.partner,                  -- eb:To
    content_format      VARCHAR(10)  NOT NULL CHECK (content_format IN ('JSON', 'XML', 'CSV', 'TXT', 'EDIFACT', 'PDF')),
    content_type        VARCHAR(80)  NOT NULL,
    state_code          VARCHAR(16)  NOT NULL REFERENCES wfe.message_state,
    correlation_id      UUID         NOT NULL DEFAULT gen_random_uuid(),
    ref_to_message_id   UUID         REFERENCES wfe.message,                  -- eb:RefToMessageId (W09)
    idempotency_key     VARCHAR(100),                                         -- eb:DuplicateElimination
    content_length      INT          CHECK (content_length >= 0),
    content_sha256      CHAR(64),
    validation          JSONB        NOT NULL DEFAULT '{}',
    observation         VARCHAR(1500),
    created_by          VARCHAR(60)  NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ,                                          -- eb:TimeToLive
    UNIQUE NULLS DISTINCT (tenant_id, idempotency_key)
);
CREATE INDEX ix_message_dossier ON wfe.message (dossier_id, created_at);
CREATE INDEX ix_message_code ON wfe.message (message_code, created_at DESC);
CREATE INDEX ix_message_created ON wfe.message USING brin (created_at);

-- Contenu brut séparé et partitionné par mois (W06) ; les journaux ne recopient jamais le contenu (RG-MSG-05)
CREATE TABLE wfe.message_content (
    message_id  UUID         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    encoding    VARCHAR(20)  NOT NULL DEFAULT 'UTF-8',
    payload     BYTEA        NOT NULL,
    PRIMARY KEY (message_id, created_at)
) PARTITION BY RANGE (created_at);
CREATE TABLE wfe.message_content_default PARTITION OF wfe.message_content DEFAULT;

-- Valeurs indexées extraites du contenu (RG-TRX-04 : seuls les champs déclarés indexables)
CREATE TABLE wfe.payload_value (
    message_id   UUID          NOT NULL REFERENCES wfe.message ON DELETE CASCADE,
    path         VARCHAR(300)  NOT NULL,
    occurrence   INT           NOT NULL DEFAULT 1,
    field_code   VARCHAR(60),
    value_text   TEXT,
    value_num    NUMERIC,
    PRIMARY KEY (message_id, path, occurrence)
);
CREATE INDEX ix_payload_value ON wfe.payload_value (path, value_text);

CREATE TABLE wfe.message_state_history (
    history_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id   UUID         NOT NULL REFERENCES wfe.message ON DELETE CASCADE,
    from_state   VARCHAR(16)  REFERENCES wfe.message_state,
    to_state     VARCHAR(16)  NOT NULL REFERENCES wfe.message_state,
    changed_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    changed_by   VARCHAR(100) NOT NULL DEFAULT coalesce(nullif(current_setting('nexora.user', true), ''), current_user),
    reason       TEXT
);
CREATE INDEX ix_message_state_history ON wfe.message_state_history (message_id, changed_at);

-- Transition d'état contrôlée et historisée automatiquement
CREATE FUNCTION wfe.trg_message_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.state_code IS DISTINCT FROM OLD.state_code THEN
    IF NOT EXISTS (SELECT 1 FROM wfe.message_state_transition WHERE from_state = OLD.state_code AND to_state = NEW.state_code) THEN
      RAISE EXCEPTION 'Transition interdite % -> % (message %)', OLD.state_code, NEW.state_code, OLD.message_id;
    END IF;
    INSERT INTO wfe.message_state_history (message_id, from_state, to_state, reason)
    VALUES (NEW.message_id, OLD.state_code, NEW.state_code, nullif(current_setting('nexora.reason', true), ''));
    NEW.updated_at := now();
  ELSIF TG_OP = 'INSERT' THEN
    INSERT INTO wfe.message_state_history (message_id, from_state, to_state) VALUES (NEW.message_id, NULL, NEW.state_code);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER message_state_bu BEFORE UPDATE ON wfe.message FOR EACH ROW EXECUTE FUNCTION wfe.trg_message_state();
CREATE TRIGGER message_state_ai AFTER INSERT ON wfe.message FOR EACH ROW EXECUTE FUNCTION wfe.trg_message_state();

-- Pièces jointes (ex-PIECE_JOINTE), contenu hors des tables chaudes, empreinte
CREATE TABLE wfe.attachment (
    attachment_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dossier_id     UUID          NOT NULL REFERENCES wfe.dossier ON DELETE CASCADE,
    doc_code       VARCHAR(10)   NOT NULL,                       -- pièce du paquet (P1…Pn)
    file_name      VARCHAR(255)  NOT NULL,
    mime_type      VARCHAR(120)  NOT NULL,
    size_bytes     BIGINT        NOT NULL,
    sha256         CHAR(64)      NOT NULL,
    content        BYTEA         NOT NULL,
    source         VARCHAR(12)   NOT NULL DEFAULT 'UPLOAD' CHECK (source IN ('UPLOAD', 'REGISTRY', 'SYSTEM')),
    is_active      BOOLEAN       NOT NULL DEFAULT true,
    uploaded_by    VARCHAR(60)   NOT NULL,
    uploaded_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_attachment_dossier ON wfe.attachment (dossier_id, doc_code) WHERE is_active;
CREATE TABLE wfe.message_attachment (
    message_id    UUID   NOT NULL REFERENCES wfe.message ON DELETE CASCADE,
    attachment_id BIGINT NOT NULL REFERENCES wfe.attachment,
    PRIMARY KEY (message_id, attachment_id)
);

-- ---------------------------------------------------------------------------------------------
-- 5. Paiement (e-PAYMENT) et livrables
-- ---------------------------------------------------------------------------------------------
CREATE TABLE wfe.payment_notice (
    notice_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notice_no          VARCHAR(40)   NOT NULL UNIQUE,              -- numéro d'avis (dérivé du CUR)
    dossier_id         UUID          NOT NULL REFERENCES wfe.dossier,
    status             VARCHAR(10)   NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('DRAFT', 'ISSUED', 'PAID', 'OVERDUE', 'CANCELLED', 'DISPUTED')),
    payee              VARCHAR(35)   NOT NULL DEFAULT 'GUCE',
    payer_niu          VARCHAR(35),
    payer_name         VARCHAR(200),
    total_amount       NUMERIC(16,2) NOT NULL CHECK (total_amount >= 0),
    currency           CHAR(3)       NOT NULL DEFAULT 'XAF',
    issued_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    due_at             TIMESTAMPTZ,
    paid_at            TIMESTAMPTZ,
    payment_reference  VARCHAR(70),                                -- numéro de quittance
    bank_code          VARCHAR(35),
    channel            VARCHAR(20)
);
CREATE INDEX ix_notice_dossier ON wfe.payment_notice (dossier_id);
CREATE TABLE wfe.payment_line (
    notice_id    BIGINT        NOT NULL REFERENCES wfe.payment_notice ON DELETE CASCADE,
    line_no      INT           NOT NULL,
    fee_code     VARCHAR(30)   NOT NULL,
    beneficiary  VARCHAR(30)   NOT NULL,
    label        VARCHAR(250)  NOT NULL,
    amount       NUMERIC(16,2) NOT NULL,
    PRIMARY KEY (notice_id, line_no)
);

CREATE TABLE wfe.issued_document (
    document_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dossier_id       UUID          NOT NULL REFERENCES wfe.dossier,
    deliverable_code VARCHAR(30)   NOT NULL,
    doc_no           VARCHAR(40)   NOT NULL UNIQUE,                -- CUR du dossier parent + code + amendement (RG-CUR-02)
    base_doc_no      VARCHAR(40)   NOT NULL,                       -- numéro d'inscription stable entre amendements
    version_no       INT           NOT NULL DEFAULT 1,
    holder_niu       VARCHAR(35),
    holder_name      VARCHAR(200),
    request_type     VARCHAR(20)   NOT NULL,
    valid_from       DATE          NOT NULL,
    valid_until      DATE,
    signed_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    signed_by        VARCHAR(60)   NOT NULL,
    signer_name      VARCHAR(150),
    qr_text          VARCHAR(2048) NOT NULL,
    seal_text        VARCHAR(2048) NOT NULL,
    file_name        VARCHAR(255)  NOT NULL,
    content          BYTEA         NOT NULL,                       -- PDF signé (cachet électronique visible)
    sha256           CHAR(64)      NOT NULL,
    status           VARCHAR(12)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'REVOKED')),
    UNIQUE (base_doc_no, version_no)
);
CREATE INDEX ix_issued_holder ON wfe.issued_document (holder_niu, request_type, status);

-- ---------------------------------------------------------------------------------------------
-- 6. Journal d'audit chaîné (ajout seul, prev_hash / row_hash)
-- ---------------------------------------------------------------------------------------------
CREATE TABLE wfe.audit_event (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor          VARCHAR(60)  NOT NULL,
    channel        VARCHAR(10)  NOT NULL DEFAULT 'API',
    action_code    VARCHAR(60)  NOT NULL,
    dossier_id     UUID,
    dossier_no     VARCHAR(35),
    reason         TEXT,
    details        JSONB        NOT NULL DEFAULT '{}',
    prev_hash      CHAR(64),
    row_hash       CHAR(64)     NOT NULL
);
CREATE INDEX ix_audit_dossier ON wfe.audit_event (dossier_id, occurred_at);
CREATE FUNCTION wfe.audit_chainer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  PERFORM pg_advisory_xact_lock(4243);
  SELECT row_hash INTO NEW.prev_hash FROM wfe.audit_event ORDER BY id DESC LIMIT 1;
  NEW.row_hash := encode(sha256(convert_to(coalesce(NEW.prev_hash, '') || NEW.occurred_at::text || NEW.actor || NEW.action_code
                  || coalesce(NEW.dossier_no, '') || coalesce(NEW.reason, '') || NEW.details::text, 'UTF8')), 'hex');
  RETURN NEW;
END $$;
CREATE TRIGGER audit_chainage BEFORE INSERT ON wfe.audit_event FOR EACH ROW EXECUTE FUNCTION wfe.audit_chainer();
CREATE TRIGGER audit_ajout_seul BEFORE UPDATE OR DELETE ON wfe.audit_event FOR EACH ROW EXECUTE FUNCTION wfe.ajout_seul();

-- ---------------------------------------------------------------------------------------------
-- Données livrées : partenaires institutionnels, lieux de traitement FIMEX (10 régions + administration centrale)
-- ---------------------------------------------------------------------------------------------
INSERT INTO wfe.partner (edi_id, name, partner_type_code, country_code) VALUES
('GUCE', 'Guichet Unique des Opérations du Commerce Extérieur', 'SYSTEME', 'CM'),
('MINCOMMERCE', 'Ministère du Commerce — DCE / SOC', 'ADMINISTRATION', 'CM'),
('CNCC', 'Conseil National des Chargeurs du Cameroun', 'PARTENAIRE', 'CM'),
('DGI', 'Direction Générale des Impôts', 'ADMINISTRATION', 'CM'),
('REGISTRE', 'NEXORA Registration — registre des opérateurs', 'SYSTEME', 'CM');

INSERT INTO wfe.admin_unit (unit_code, label) VALUES
('DCE_SOC', 'DCE / Service des Opérations Commerciales — Bureau central (Yaoundé)'),
('DRC_AD', 'Délégation régionale du Commerce de l''Adamaoua'), ('DRC_CE', 'Délégation régionale du Commerce du Centre'),
('DRC_ES', 'Délégation régionale du Commerce de l''Est'), ('DRC_EN', 'Délégation régionale du Commerce de l''Extrême-Nord'),
('DRC_LT', 'Délégation régionale du Commerce du Littoral'), ('DRC_NO', 'Délégation régionale du Commerce du Nord'),
('DRC_NW', 'Délégation régionale du Commerce du Nord-Ouest'), ('DRC_OU', 'Délégation régionale du Commerce de l''Ouest'),
('DRC_SU', 'Délégation régionale du Commerce du Sud'), ('DRC_SW', 'Délégation régionale du Commerce du Sud-Ouest');
INSERT INTO wfe.processing_location (location_code, label_fr, label_en, unit_code, sort_order) VALUES
('CENTRAL', 'Administration centrale (Bureau central)', 'Central administration (Head office)', 'DCE_SOC', 0),
('AD', 'Adamaoua', 'Adamawa', 'DRC_AD', 10), ('CE', 'Centre', 'Centre', 'DRC_CE', 20), ('ES', 'Est', 'East', 'DRC_ES', 30),
('EN', 'Extrême-Nord', 'Far North', 'DRC_EN', 40), ('LT', 'Littoral', 'Littoral', 'DRC_LT', 50), ('NO', 'Nord', 'North', 'DRC_NO', 60),
('NW', 'Nord-Ouest', 'North-West', 'DRC_NW', 70), ('OU', 'Ouest', 'West', 'DRC_OU', 80), ('SU', 'Sud', 'South', 'DRC_SU', 90),
('SW', 'Sud-Ouest', 'South-West', 'DRC_SW', 100);
