-- =============================================================================================
-- NEXORA · Microservice « auth » — authentification unique (SSO) et habilitations
-- Utilisateurs, groupes, rôles, permissions (droits par microservice), sessions SSO, clés de
-- signature des jetons et journal de sécurité.
-- =============================================================================================

-- Droits élémentaires, rattachés à une application de la plateforme
CREATE TABLE auth_permission (
    code         VARCHAR(80)  PRIMARY KEY CHECK (code ~ '^[a-z][a-z0-9_]*\.[a-z][a-z0-9_.]*$'),
    application  VARCHAR(40)  NOT NULL,
    label_fr     VARCHAR(200) NOT NULL,
    label_en     VARCHAR(200),
    description  TEXT,
    sort_order   INT          NOT NULL DEFAULT 0
);

CREATE TABLE auth_role (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         VARCHAR(40)  NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    label_fr     VARCHAR(200) NOT NULL,
    label_en     VARCHAR(200),
    description  TEXT,
    system       BOOLEAN      NOT NULL DEFAULT false,     -- rôle livré avec la plateforme (non supprimable)
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE auth_role_permission (
    role_id          BIGINT      NOT NULL REFERENCES auth_role (id) ON DELETE CASCADE,
    permission_code  VARCHAR(80) NOT NULL REFERENCES auth_permission (code) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_code)
);

CREATE TABLE auth_user (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username              VARCHAR(60)  NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9][a-z0-9._-]{1,59}$'),
    full_name             VARCHAR(150) NOT NULL,
    email                 VARCHAR(200),
    organisation          VARCHAR(200),
    password_hash         VARCHAR(100) NOT NULL,
    active                BOOLEAN      NOT NULL DEFAULT true,
    must_change_password  BOOLEAN      NOT NULL DEFAULT false,
    failed_attempts       INT          NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    language              VARCHAR(2)   NOT NULL DEFAULT 'fr' CHECK (language IN ('fr', 'en')),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE auth_group (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         VARCHAR(40)  NOT NULL UNIQUE CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    label_fr     VARCHAR(200) NOT NULL,
    label_en     VARCHAR(200),
    description  TEXT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Habilitations : rôles attribués directement à un utilisateur ou hérités de ses groupes
CREATE TABLE auth_user_group (
    user_id   BIGINT NOT NULL REFERENCES auth_user (id) ON DELETE CASCADE,
    group_id  BIGINT NOT NULL REFERENCES auth_group (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, group_id)
);
CREATE TABLE auth_user_role (
    user_id  BIGINT NOT NULL REFERENCES auth_user (id) ON DELETE CASCADE,
    role_id  BIGINT NOT NULL REFERENCES auth_role (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);
CREATE TABLE auth_group_role (
    group_id  BIGINT NOT NULL REFERENCES auth_group (id) ON DELETE CASCADE,
    role_id   BIGINT NOT NULL REFERENCES auth_role (id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, role_id)
);

-- Sessions SSO (cookie HttpOnly sur le domaine du service auth)
CREATE TABLE auth_session (
    id          VARCHAR(64)  PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES auth_user (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    ip          VARCHAR(60),
    user_agent  VARCHAR(300)
);
CREATE INDEX ix_auth_session_user ON auth_session (user_id);

-- Clés RSA de signature des jetons (exposées publiquement en JWKS)
CREATE TABLE auth_key (
    kid          VARCHAR(40)  PRIMARY KEY,
    private_pem  TEXT         NOT NULL,
    public_pem   TEXT         NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Journal de sécurité : connexions réussies / échouées, verrouillages, administration des comptes
CREATE TABLE auth_event (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    type         VARCHAR(30)  NOT NULL,
    username     VARCHAR(60),
    actor        VARCHAR(60),
    ip           VARCHAR(60),
    details      TEXT
);
CREATE INDEX ix_auth_event_date ON auth_event (occurred_at DESC);
CREATE INDEX ix_auth_event_user ON auth_event (username, occurred_at DESC);

-- ---------------------------------------------------------------------------------------------
-- Droits et rôles livrés avec la plateforme
-- ---------------------------------------------------------------------------------------------
INSERT INTO auth_permission (code, application, label_fr, label_en, description, sort_order) VALUES
('refdata.lire',        'refdata', 'Consulter le référentiel',              'View the reference data',          'Catalogue, tables, codes, métadonnées, historique', 10),
('refdata.export',      'refdata', 'Exporter les listes de codes',          'Export code lists',                'Exports CSV, JSON, Excel', 20),
('refdata.donnees',     'refdata', 'Gérer les codes',                       'Manage codes',                     'Créer, modifier, invalider, réactiver des codes ; charger des fichiers', 30),
('refdata.metadonnees', 'refdata', 'Gérer les métadonnées',                 'Manage metadata',                  'Fiche ISO 19115, pièces jointes', 40),
('refdata.structure',   'refdata', 'Gérer la structure des tables',         'Manage table structures',          'Créer des tables, modifier leurs colonnes, archiver, gérer les catégories', 50),
('refdata.api',         'refdata', 'Accéder aux informations API et SQL',   'Access API and SQL information',   'Onglet « API & SQL » : points d''accès, tables physiques, dictionnaire de données', 60),
('auth.administration', 'auth',    'Administrer les utilisateurs et les droits', 'Administer users and rights', 'Utilisateurs, groupes, rôles, habilitations, journal de sécurité', 100);

INSERT INTO auth_role (code, label_fr, label_en, description, system) VALUES
('ADMINISTRATEUR', 'Administrateur', 'Administrator', 'Tous les droits sur la plateforme, y compris l''administration des utilisateurs', true),
('GESTIONNAIRE',   'Gestionnaire',   'Manager',       'Gestion des codes, des chargements et des métadonnées du référentiel', true),
('CONSULTATION',   'Consultation',   'Viewer',        'Consultation et export du référentiel, sans structure ni API', true);

INSERT INTO auth_role_permission (role_id, permission_code)
SELECT r.id, p.code FROM auth_role r CROSS JOIN auth_permission p WHERE r.code = 'ADMINISTRATEUR';
INSERT INTO auth_role_permission (role_id, permission_code)
SELECT r.id, p FROM auth_role r, unnest(ARRAY['refdata.lire', 'refdata.export', 'refdata.donnees', 'refdata.metadonnees']) p WHERE r.code = 'GESTIONNAIRE';
INSERT INTO auth_role_permission (role_id, permission_code)
SELECT r.id, p FROM auth_role r, unnest(ARRAY['refdata.lire', 'refdata.export']) p WHERE r.code = 'CONSULTATION';

INSERT INTO auth_group (code, label_fr, label_en, description) VALUES
('ADMINISTRATEURS', 'Administrateurs de la plateforme', 'Platform administrators', 'Équipe d''administration NEXORA'),
('GESTIONNAIRES_REFERENTIEL', 'Gestionnaires du référentiel', 'Reference data managers', 'Autorités de mise à jour des tables de référence'),
('CONSULTANTS', 'Utilisateurs en consultation', 'Viewers', 'Administrations et partenaires en lecture seule');
INSERT INTO auth_group_role (group_id, role_id) SELECT g.id, r.id FROM auth_group g JOIN auth_role r ON
    (g.code, r.code) IN (('ADMINISTRATEURS', 'ADMINISTRATEUR'), ('GESTIONNAIRES_REFERENTIEL', 'GESTIONNAIRE'), ('CONSULTANTS', 'CONSULTATION'));
