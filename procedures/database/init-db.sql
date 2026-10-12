-- Crée l'utilisateur applicatif et la base du microservice procedures (une base par microservice).
-- Lancer database\creer-base.cmd, qui lit PROCEDURES_DB_PASSWORD dans .env.local (variable psql "mdp_procedures").
-- Idempotent : rien n'est recréé si l'utilisateur ou la base existent déjà.
SELECT format('CREATE ROLE nexora_procedures LOGIN PASSWORD %L', :'mdp_procedures')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nexora_procedures') \gexec
SELECT 'CREATE DATABASE nexora_procedures OWNER nexora_procedures ENCODING ''UTF8'' TEMPLATE template0'
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'nexora_procedures') \gexec
