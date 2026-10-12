-- Crée l'utilisateur applicatif et la base du microservice registration (une base par microservice).
-- Lancer database\creer-base.cmd, qui lit REGISTRATION_DB_PASSWORD dans .env.local (variable psql "mdp_registration").
-- Idempotent : rien n'est recréé si l'utilisateur ou la base existent déjà.
SELECT format('CREATE ROLE nexora_registration LOGIN PASSWORD %L', :'mdp_registration')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexora_registration') \gexec
SELECT 'CREATE DATABASE nexora_registration OWNER nexora_registration ENCODING ''UTF8'' TEMPLATE template0'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'nexora_registration') \gexec
