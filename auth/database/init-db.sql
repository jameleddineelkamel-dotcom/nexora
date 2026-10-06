-- Cree l'utilisateur applicatif et la base du microservice auth (une base par microservice).
-- Lancer database\creer-base.cmd, qui lit AUTH_DB_PASSWORD dans .env.local (variable psql "mdp").
CREATE ROLE nexora_auth LOGIN PASSWORD :'mdp';
CREATE DATABASE nexora_auth OWNER nexora_auth ENCODING 'UTF8' TEMPLATE template0;
