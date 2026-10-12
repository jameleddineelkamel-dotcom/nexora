-- =============================================================================================
-- NEXORA · Microservice « registration » — registre des opérateurs économiques du guichet unique
-- Fiche de l'opérateur (NIU, raison sociale, immatriculations, adresse, contacts), pièces d'enrôlement réutilisées par
-- les procédures (UC-01 SFD FIMEX : « charge toutes les pièces jointes déjà disponibles »), rattachement des comptes
-- (titulaire, mandataire CDA), historique des modifications et complétion après procédure (RG2 UC-05).
-- =============================================================================================
CREATE SCHEMA IF NOT EXISTS registre;

CREATE TABLE registre.party (
    niu                         VARCHAR(35)  PRIMARY KEY CHECK (niu ~ '^[A-Z0-9]{6,35}$'),   -- numéro d'identification unique (DGI)
    tenant_id                   VARCHAR(40)  NOT NULL DEFAULT 'CM',
    legal_name                  VARCHAR(200) NOT NULL,
    party_type_code             VARCHAR(3)   NOT NULL CHECK (party_type_code IN ('PP', 'PM', 'SUC', 'GIE')),
    nationality                 CHAR(1)      NOT NULL DEFAULT 'C' CHECK (nationality IN ('C', 'E')),
    national_identifier         VARCHAR(35),
    contact_person_name         VARCHAR(100),
    birth_country               VARCHAR(35),
    annual_turnover             NUMERIC(18,3),
    street                      VARCHAR(256),
    address_line2               VARCHAR(256),
    po_box                      VARCHAR(17),
    city                        VARCHAR(35),
    country_code                CHAR(2)      DEFAULT 'CM' CHECK (country_code ~ '^[A-Z]{2}$'),
    email                       VARCHAR(254),
    phone                       VARCHAR(35),
    mobile                      VARCHAR(35),
    fax                         VARCHAR(35),
    tax_card_from               DATE,
    tax_card_to                 DATE,
    registration_number         VARCHAR(35),                                   -- patente
    activity_sector_code        VARCHAR(10),
    industry_classification_code VARCHAR(17),
    tax_regime_code             VARCHAR(3),
    rccm                        VARCHAR(35),
    rccm_date                   DATE,
    rccm_place                  VARCHAR(70),
    status                      VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    enrolled_at                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by                  VARCHAR(60),
    version                     BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX ix_party_name ON registre.party (lower(legal_name));

-- Comptes rattachés : titulaire (l'opérateur lui-même) ou mandataire (CDA agissant pour lui)
CREATE TABLE registre.party_user (
    niu         VARCHAR(35)  NOT NULL REFERENCES registre.party,
    username    VARCHAR(60)  NOT NULL CHECK (username ~ '^[a-z0-9][a-z0-9._-]{1,59}$'),
    relation    VARCHAR(12)  NOT NULL CHECK (relation IN ('TITULAIRE', 'MANDATAIRE')),
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(60)  NOT NULL,
    PRIMARY KEY (niu, username)
);
CREATE INDEX ix_party_user ON registre.party_user (username) WHERE is_active;

-- Pièces d'enrôlement (codes alignés sur les pièces des procédures : P1…Pn)
CREATE TABLE registre.party_document (
    document_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    niu          VARCHAR(35)  NOT NULL REFERENCES registre.party,
    doc_code     VARCHAR(10)  NOT NULL,
    label        VARCHAR(300),
    file_name    VARCHAR(255) NOT NULL,
    mime_type    VARCHAR(120) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    sha256       CHAR(64)     NOT NULL,
    content      BYTEA        NOT NULL,
    valid_until  DATE,
    is_active    BOOLEAN      NOT NULL DEFAULT true,
    uploaded_by  VARCHAR(60)  NOT NULL,
    uploaded_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_party_document ON registre.party_document (niu, doc_code) WHERE is_active;

-- Historique des modifications de fiche (auteur, canal, motif ; différences colonne par colonne)
CREATE TABLE registre.party_history (
    history_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    niu          VARCHAR(35)  NOT NULL,
    operation    VARCHAR(12)  NOT NULL,
    actor        VARCHAR(60)  NOT NULL,
    channel      VARCHAR(12)  NOT NULL,
    reason       TEXT,
    changes      JSONB        NOT NULL DEFAULT '{}',
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_party_history ON registre.party_history (niu, occurred_at DESC);

CREATE FUNCTION registre.journaliser() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  avant jsonb := CASE WHEN TG_OP = 'UPDATE' THEN to_jsonb(OLD) - 'updated_at' - 'version' - 'updated_by' ELSE '{}'::jsonb END;
  apres jsonb := to_jsonb(NEW) - 'updated_at' - 'version' - 'updated_by';
  diff  jsonb := '{}'::jsonb;
  k text;
BEGIN
  FOR k IN SELECT jsonb_object_keys(apres) LOOP
    IF avant -> k IS DISTINCT FROM apres -> k THEN
      diff := diff || jsonb_build_object(k, jsonb_build_object('old', avant -> k, 'new', apres -> k));
    END IF;
  END LOOP;
  IF TG_OP = 'UPDATE' AND diff = '{}'::jsonb THEN RETURN NEW; END IF;
  INSERT INTO registre.party_history (niu, operation, actor, channel, reason, changes)
  VALUES (NEW.niu, CASE WHEN TG_OP = 'INSERT' THEN 'ENROLEMENT' ELSE coalesce(nullif(current_setting('nexora.operation', true), ''), 'MODIFICATION') END,
          coalesce(nullif(current_setting('nexora.user', true), ''), 'systeme'), coalesce(nullif(current_setting('nexora.channel', true), ''), 'API'),
          nullif(current_setting('nexora.reason', true), ''), diff);
  RETURN NEW;
END $$;
CREATE TRIGGER party_historique AFTER INSERT OR UPDATE ON registre.party FOR EACH ROW EXECUTE FUNCTION registre.journaliser();
