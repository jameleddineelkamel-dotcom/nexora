-- Crée l'utilisateur applicatif et la base du microservice eservices (une base par microservice).
-- Lancer database\creer-base.cmd, qui lit ESERVICES_DB_PASSWORD dans .env.local (variable psql "mdp_eservices").
-- Idempotent : rien n'est recréé si l'utilisateur ou la base existent déjà.
SELECT format('CREATE ROLE nexora_eservices LOGIN PASSWORD %L', :'mdp_eservices')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexora_eservices') \gexec
SELECT 'CREATE DATABASE nexora_eservices OWNER nexora_eservices ENCODING ''UTF8'' TEMPLATE template0'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'nexora_eservices') \gexec
