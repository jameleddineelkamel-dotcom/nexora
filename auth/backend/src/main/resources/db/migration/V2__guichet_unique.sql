-- =============================================================================================
-- NEXORA · auth — droits et rôles du guichet unique (Single Window)
--   registration : registre des opérateurs économiques (enrôlement, NIU, pièces)
--   procedures   : studio de modélisation des procédures (Single Window AI Studio) et agents IA
--   eservices    : exécution des procédures (eFORCE opérateurs, eGOV administrations, e-PAYMENT)
-- =============================================================================================
INSERT INTO auth_permission (code, application, label_fr, label_en, description, sort_order) VALUES
('registration.lire',       'registration', 'Consulter le registre des opérateurs',  'View the operator registry',       'Fiches des opérateurs économiques (NIU, RCCM, adresses, pièces)', 200),
('registration.gerer',      'registration', 'Gérer le registre des opérateurs',       'Manage the operator registry',     'Enrôler, modifier, suspendre un opérateur ; rattacher des utilisateurs', 210),
('registration.completer',  'registration', 'Compléter une fiche après une procédure','Complete a record after a procedure','Mise à jour des données manquantes d''un opérateur à la validation d''une procédure (ex. FIMEX)', 220),
('procedures.lire',         'procedures',   'Consulter les procédures',              'View procedures',                  'Procédures, versions, formulaires, messages, workflow, règles, paquets publiés', 300),
('procedures.modeliser',    'procedures',   'Modéliser les procédures',              'Model procedures',                 'Modifier une version brouillon, lancer les agents IA, revoir leurs propositions', 310),
('procedures.approuver',    'procedures',   'Approuver les procédures',              'Approve procedures',               'Approuver ou renvoyer une version soumise (l''auteur ne peut être l''unique approbateur)', 320),
('procedures.publier',      'procedures',   'Publier les procédures',                'Publish procedures',               'Publier une version approuvée, la retirer', 330),
('eservices.deposer',       'eservices',    'Déposer des demandes',                  'Submit requests',                  'eFORCE : initier, compléter, payer et suivre ses demandes ; télécharger ses livrables', 400),
('eservices.mandataire',    'eservices',    'Agir pour des tiers (CDA)',             'Act for third parties (customs broker)', 'Initier des demandes pour le compte d''autres opérateurs', 410),
('eservices.traiter',       'eservices',    'Traiter les dossiers',                  'Process cases',                    'eGOV : traiter les dossiers cotés (complément, validation pour signature, rejet)', 420),
('eservices.signer',        'eservices',    'Signer les livrables',                  'Sign deliverables',                'eGOV : valider et signer, ou retourner pour réexamen', 430),
('eservices.encaisser',     'eservices',    'Confirmer les paiements',               'Confirm payments',                 'e-PAYMENT : confirmation des encaissements par les banques', 440),
('eservices.superviser',    'eservices',    'Superviser',                            'Supervise',                        'Tous les dossiers, messages et encaissements par bénéficiaire', 450),
('eservices.administrer',   'eservices',    'Administrer les e-Services',            'Administer e-Services',            'Déploiement des procédures publiées, lieux de traitement, unités, agents et cotation', 460);

-- L'administrateur reçoit tous les droits, y compris ceux ajoutés ici
INSERT INTO auth_role_permission (role_id, permission_code)
SELECT r.id, p.code FROM auth_role r CROSS JOIN auth_permission p WHERE r.code = 'ADMINISTRATEUR'
ON CONFLICT DO NOTHING;

INSERT INTO auth_role (code, label_fr, label_en, description, system) VALUES
('CONCEPTEUR_PROCEDURES',  'Concepteur de procédures',  'Procedure designer',     'Modélise les procédures dans le studio, avec les agents IA', true),
('APPROBATEUR_PROCEDURES', 'Approbateur de procédures', 'Procedure approver',     'Approuve et publie les versions de procédures', true),
('OPERATEUR_ECONOMIQUE',   'Opérateur économique',      'Economic operator',      'Importateur / exportateur : dépose et suit ses demandes', true),
('COMMISSIONNAIRE_AGREE',  'Commissionnaire agréé (CDA)','Licensed customs broker','Dépose des demandes pour son compte et pour des tiers', true),
('AGENT_TRAITEMENT',       'Agent de traitement',       'Processing officer',     'Traite les dossiers qui lui sont cotés', true),
('AGENT_SIGNATAIRE',       'Agent signataire',          'Signing officer',        'Valide et signe les livrables', true),
('SUPERVISEUR',            'Superviseur',               'Supervisor',             'Suivi des dossiers, messages et encaissements', true),
('BANQUE',                 'Banque',                    'Bank',                   'Confirme les encaissements', true),
('ADMINISTRATEUR_ESERVICES','Administrateur e-Services','e-Services administrator','Déploie les procédures et paramètre la cotation', true);

INSERT INTO auth_role_permission (role_id, permission_code)
SELECT r.id, p FROM
  (VALUES ('CONCEPTEUR_PROCEDURES',  ARRAY['procedures.lire', 'procedures.modeliser', 'refdata.lire']),
          ('APPROBATEUR_PROCEDURES', ARRAY['procedures.lire', 'procedures.approuver', 'procedures.publier', 'refdata.lire']),
          ('OPERATEUR_ECONOMIQUE',   ARRAY['eservices.deposer']),
          ('COMMISSIONNAIRE_AGREE',  ARRAY['eservices.deposer', 'eservices.mandataire', 'registration.lire']),
          ('AGENT_TRAITEMENT',       ARRAY['eservices.traiter', 'registration.lire']),
          ('AGENT_SIGNATAIRE',       ARRAY['eservices.signer', 'registration.lire', 'registration.completer']),
          ('SUPERVISEUR',            ARRAY['eservices.superviser', 'registration.lire', 'procedures.lire']),
          ('BANQUE',                 ARRAY['eservices.encaisser']),
          ('ADMINISTRATEUR_ESERVICES', ARRAY['eservices.administrer', 'eservices.superviser', 'procedures.lire', 'registration.lire', 'registration.gerer', 'refdata.lire'])
  ) AS m(role, perms)
  JOIN auth_role r ON r.code = m.role
  CROSS JOIN LATERAL unnest(m.perms) AS p;

INSERT INTO auth_group (code, label_fr, label_en, description) VALUES
('CONCEPTEURS_PROCEDURES', 'Concepteurs de procédures', 'Procedure designers', 'Équipe de modélisation du guichet unique'),
('OPERATEURS_ECONOMIQUES', 'Opérateurs économiques', 'Economic operators', 'Importateurs et exportateurs enrôlés'),
('COMMISSIONNAIRES_AGREES', 'Commissionnaires agréés en douane', 'Licensed customs brokers', 'CDA agissant pour des tiers'),
('MINCOMMERCE_TRAITEMENT', 'MINCOMMERCE — agents de traitement', 'MINCOMMERCE — processing officers', 'DCE / SOC et délégations régionales'),
('MINCOMMERCE_SIGNATAIRES', 'MINCOMMERCE — signataires', 'MINCOMMERCE — signing officers', 'Signataires des attestations'),
('BANQUES', 'Banques partenaires', 'Partner banks', 'Encaissement des frais');
INSERT INTO auth_group_role (group_id, role_id) SELECT g.id, r.id FROM auth_group g JOIN auth_role r ON
    (g.code, r.code) IN (('CONCEPTEURS_PROCEDURES', 'CONCEPTEUR_PROCEDURES'), ('OPERATEURS_ECONOMIQUES', 'OPERATEUR_ECONOMIQUE'),
                         ('COMMISSIONNAIRES_AGREES', 'COMMISSIONNAIRE_AGREE'), ('MINCOMMERCE_TRAITEMENT', 'AGENT_TRAITEMENT'),
                         ('MINCOMMERCE_SIGNATAIRES', 'AGENT_SIGNATAIRE'), ('BANQUES', 'BANQUE'));
