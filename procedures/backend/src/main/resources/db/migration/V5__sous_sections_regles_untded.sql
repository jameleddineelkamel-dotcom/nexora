-- =============================================================================================
-- NEXORA · procedures — V5
--   1. Sous-sections du formulaire (form_section.parent_code)
--   2. Approbation des règles DRL (RG-GOV-04 : l'approbateur n'est pas le dernier auteur des règles)
--   3. Dictionnaire UNTDED 2005 (ISO 7372) — chargé au démarrage depuis seed/reference/untded2005.json
--   4. Rôles des parties et lieux (UN/CEFACT RSM SCRDM v1.0.0.2, tables 1 et 2) avec leur chemin BIE SCRDM D23B
-- =============================================================================================

-- 1. Sous-sections : une section peut être rattachée à une section parente (un seul niveau).
ALTER TABLE studio.form_section ADD COLUMN parent_code VARCHAR(30);
ALTER TABLE studio.form_section ADD CONSTRAINT fk_section_parent
    FOREIGN KEY (version_id, parent_code) REFERENCES studio.form_section (version_id, code) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE studio.form_section ADD CONSTRAINT ck_section_parent CHECK (parent_code IS NULL OR parent_code <> code);

-- 2. Approbation des règles : valable tant que l'empreinte SHA-256 du DRL approuvé égale celle du DRL courant.
ALTER TABLE studio.rule_set ADD COLUMN approved_by   VARCHAR(60);
ALTER TABLE studio.rule_set ADD COLUMN approved_at   TIMESTAMPTZ;
ALTER TABLE studio.rule_set ADD COLUMN approved_hash CHAR(64);
ALTER TABLE studio.rule_set ADD COLUMN approval_note VARCHAR(500);

-- 3. UNTDED 2005 : Trade Data Elements Directory (tag à 4 chiffres, nom normalisé, description, représentation).
CREATE TABLE rdm.untded_element (
  tag            VARCHAR(4)   PRIMARY KEY CHECK (tag ~ '^[0-9]{4}$'),
  name           VARCHAR(200) NOT NULL,
  description    TEXT,
  representation VARCHAR(12)
);
COMMENT ON TABLE rdm.untded_element IS 'UN Trade Data Elements Directory 2005 (ISO 7372) : référence untdedRef des champs.';
CREATE INDEX ix_untded_name ON rdm.untded_element (lower(name));

-- 4. Rôles des parties et lieux (RSM SCRDM, §4.1 et §4.2).
CREATE TABLE rdm.party_role (
  code        VARCHAR(40)  PRIMARY KEY,
  category    VARCHAR(12)  NOT NULL CHECK (category IN ('TRADE', 'TRANSPORT', 'CUSTOMS', 'SETTLEMENT', 'LOCATION')),
  label_en    VARCHAR(120) NOT NULL,
  label_fr    VARCHAR(120) NOT NULL,
  definition  TEXT         NOT NULL,
  bie_path    VARCHAR(300) NOT NULL,                 -- chemin principal depuis scrdmMaster
  mapped_to   TEXT         NOT NULL,                 -- DEN « Can Be Mapped To » du RSM
  keywords    TEXT[]       NOT NULL DEFAULT '{}',    -- termes (fr/en) reconnus dans les libellés de champs
  sort_order  INT          NOT NULL DEFAULT 0
);
COMMENT ON TABLE rdm.party_role IS 'Rôles des parties et lieux du Buy-Ship-Pay (UN/CEFACT RSM SCRDM v1.0.0.2, tables 1 et 2).';

INSERT INTO rdm.party_role (code, category, label_en, label_fr, definition, bie_path, mapped_to, keywords, sort_order) VALUES
('SELLER', 'TRADE', 'Seller', 'Vendeur', 'The party selling goods or services as stipulated in a Sales Order Contract.',
  'tradeTransaction/applicableHeaderTradeAgreement/seller',
  'Header_ Trade Agreement. Seller. Trade_ Party ; Line_ Trade Agreement. Seller. Trade_ Party ; Supply Chain_ Consignment. Consignor. Trade_ Party ; Header_ Trade Delivery. Ship From. Trade_ Party',
  '{vendeur,seller,fournisseur,supplier}', 10),
('BUYER', 'TRADE', 'Buyer', 'Acheteur', 'The party to whom goods are sold or services provided as stipulated in a Sales Order Contract.',
  'tradeTransaction/applicableHeaderTradeAgreement/buyer',
  'Header_ Trade Agreement. Buyer. Trade_ Party ; Line_ Trade Agreement. Buyer. Trade_ Party ; Header_ Trade Delivery. Ship To. Trade_ Party',
  '{acheteur,buyer,client}', 20),
('TRANSPORT_SERVICE_BUYER', 'TRANSPORT', 'Transport Service Buyer', 'Acheteur du service de transport',
  'The buyer of transport services as stipulated in a Transport Service Contract (consignor or consignee depending on the delivery terms).',
  'specifiedConsignment/transportServicesBuyer',
  'Supply Chain_ Consignment. Consignee. Trade_ Party ; Supply Chain_ Consignment. Consignor. Trade_ Party',
  '{donneur d''ordre transport,transport service buyer}', 30),
('TRANSPORT_SERVICE_PROVIDER', 'TRANSPORT', 'Transport Service Provider', 'Prestataire de transport',
  'The provider, i.e. seller, of transport services (carrier or freight forwarder) as stipulated in a Transport Service Contract.',
  'specifiedConsignment/carrier',
  'Line_ Trade Agreement. Carrier. Trade_ Party ; Supply Chain_ Consignment. Carrier. Trade_ Party ; Supply Chain_ Consignment. Freight Forwarder. Trade_ Party',
  '{prestataire de transport,transport service provider}', 40),
('CONSIGNOR', 'TRANSPORT', 'Consignor', 'Expéditeur', 'The party consigning goods as stipulated in a Transport Service Contract.',
  'specifiedConsignment/consignor', 'Supply Chain_ Consignment. Consignor. Trade_ Party',
  '{expediteur,expéditeur,chargeur,consignor,shipper}', 50),
('CONSIGNEE', 'TRANSPORT', 'Consignee', 'Destinataire', 'The party receiving a consignment of goods as stipulated in a Transport Service Contract.',
  'specifiedConsignment/consignee', 'Supply Chain_ Consignment. Consignee. Trade_ Party',
  '{destinataire,receptionnaire,réceptionnaire,consignee}', 60),
('CARRIER', 'TRANSPORT', 'Carrier', 'Transporteur', 'The party which provides transport services.',
  'specifiedConsignment/carrier', 'Line_ Trade Agreement. Carrier. Trade_ Party ; Supply Chain_ Consignment. Carrier. Trade_ Party',
  '{transporteur,armateur,compagnie aerienne,compagnie aérienne,carrier}', 70),
('FREIGHT_FORWARDER', 'TRANSPORT', 'Freight Forwarder', 'Transitaire',
  'The party undertaking the forwarding of goods by provision of transport, logistics and associated formalities services.',
  'specifiedConsignment/freightForwarder', 'Supply Chain_ Consignment. Freight Forwarder. Trade_ Party',
  '{transitaire,commissionnaire de transport,freight forwarder}', 80),
('IMPORTER', 'CUSTOMS', 'Importer', 'Importateur',
  'The party who makes, or on whose behalf a customs clearing agent or other authorized person makes, an import declaration.',
  'tradeTransaction/applicableHeaderTradeAgreement/buyer',
  'Header_ Trade Agreement. Buyer. Trade_ Party ; Header_ Trade Delivery. Ship To. Trade_ Party ; Supply Chain_ Consignment. Customs Import Agent. Trade_ Party',
  '{importateur,importer}', 90),
('EXPORTER', 'CUSTOMS', 'Exporter', 'Exportateur',
  'The party who makes, or on whose behalf the export declaration is made, and who is the owner of the goods or has similar rights of disposal over them.',
  'tradeTransaction/applicableHeaderTradeAgreement/seller',
  'Header_ Trade Agreement. Seller. Trade_ Party ; Header_ Trade Delivery. Ship From. Trade_ Party ; Supply Chain_ Consignment. Customs Export Agent. Trade_ Party',
  '{exportateur,exporter}', 100),
('INTERMEDIARY', 'CUSTOMS', 'Intermediary', 'Intermédiaire en douane',
  'The party acting as an agent for, or on behalf of, the consignee with respect to the customs import or export procedures.',
  'specifiedConsignment/customsImportAgent',
  'Supply Chain_ Consignment. Customs Import Agent. Trade_ Party ; Supply Chain_ Consignment. Customs Export Agent. Trade_ Party',
  '{declarant,déclarant,commissionnaire en douane,cda,intermediaire,intermédiaire,customs agent,customs broker}', 110),
('INVOICEE', 'SETTLEMENT', 'Invoicee', 'Destinataire de la facture', 'The party to whom an invoice is issued.',
  'tradeTransaction/applicableHeaderTradeSettlement/invoicee',
  'Header_ Trade Settlement. Invoicee. Trade_ Party ; Line_ Trade Settlement. Invoicee. Trade_ Party',
  '{facture a,facturé à,invoicee}', 120),
('INVOICE_ISSUER', 'SETTLEMENT', 'Invoice issuer', 'Émetteur de la facture', 'The party who issues an invoice.',
  'exchangedDocument/issuer', 'Exchanged_ Document. Issuer. Trade_ Party', '{emetteur de la facture,émetteur de la facture,invoice issuer}', 130),
('DESPATCH_PARTY', 'LOCATION', 'Pick-up place / Despatch party', 'Lieu d''enlèvement',
  'The party where goods are collected or taken over by the transport services provider (operational term: pick-up place).',
  'tradeTransaction/applicableHeaderTradeDelivery/actualPickUpEvent', 'Line_ Trade Delivery. Actual_ Pick-Up. Supply Chain_ Event',
  '{lieu d''enlevement,lieu d''enlèvement,pick-up,pickup}', 140),
('DELIVERY_PARTY', 'LOCATION', 'Place of positioning / Delivery party', 'Lieu de livraison',
  'The party to which goods should be delivered by the transport services provider (operational term: place of positioning).',
  'tradeTransaction/applicableHeaderTradeDelivery/actualDeliveryEvent', 'Line_ Trade Delivery. Confirmed_ Delivery. Supply Chain_ Event',
  '{lieu de livraison,place of delivery}', 150),
('SHIP_FROM', 'LOCATION', 'Ship From (original despatch party)', 'Expédié de',
  'The party from whom goods will be or have been originally shipped.',
  'tradeTransaction/applicableHeaderTradeDelivery/shipFrom',
  'Header_ Trade Delivery. Ship From. Trade_ Party ; Line_ Trade Delivery. Ship From. Trade_ Party', '{provenance,expedie de,expédié de,ship from}', 160),
('SHIP_TO', 'LOCATION', 'Ship To (final delivery party)', 'Livré à',
  'The party to whom goods will be or have been ultimately shipped.',
  'tradeTransaction/applicableHeaderTradeDelivery/shipTo',
  'Header_ Trade Delivery. Ship To. Trade_ Party ; Line_ Trade Delivery. Ship To. Trade_ Party', '{livre a,livré à,destination finale,ship to}', 170);
