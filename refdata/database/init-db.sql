-- Crée l'utilisateur applicatif et la base du microservice refdata (une base par microservice).
-- Lancer database\creer-base.cmd, qui lit REFDATA_DB_PASSWORD dans .env.local (variable psql "mdp").
CREATE ROLE nexora_refdata LOGIN PASSWORD :'mdp';
CREATE DATABASE nexora_refdata OWNER nexora_refdata ENCODING 'UTF8' TEMPLATE template0;
