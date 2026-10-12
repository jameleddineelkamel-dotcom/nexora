-- =============================================================================================
-- NEXORA · eservices — fonctions des vues métier par procédure
-- Au déploiement d'une procédure, le Runtime crée un schéma proc_<code> dont les vues exposent les dossiers selon le
-- modèle de données métier (ex. FIMEX_REQUEST, FIMEX_COMMODITY, FIMEX_PAYMENT_NOTICE, FIMEX_CERTIFICATE) à partir des
-- tables génériques (dossier, payment_notice, issued_document). Conversions tolérantes : une valeur invalide donne NULL.
-- =============================================================================================
CREATE FUNCTION wfe.en_nombre(t text) RETURNS numeric LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN replace(replace(trim(t), ' ', ''), ',', '.') ~ '^-?[0-9]+(\.[0-9]+)?$'
              THEN replace(replace(trim(t), ' ', ''), ',', '.')::numeric END
$$;
CREATE FUNCTION wfe.en_date(t text) RETURNS date LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN trim(t) ~ '^\d{4}-\d{2}-\d{2}' THEN substr(trim(t), 1, 10)::date END
$$;
CREATE FUNCTION wfe.en_entier(t text) RETURNS integer LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE WHEN trim(t) ~ '^-?[0-9]{1,9}$' THEN trim(t)::integer END
$$;
