-- =============================================================================================
-- NEXORA · procedures — V8 : format du message dans un flux
--   Le format de contenu se règle sur le contrat du message (message_definition.content_format) ; un flux peut le
--   remplacer pour un échange donné (ex. contrat JSON transmis en EDIFACT à la douane). NULL = format du message.
-- =============================================================================================
ALTER TABLE studio.flow_message
    ADD COLUMN content_format VARCHAR(10) CHECK (content_format IN ('JSON', 'XML', 'CSV', 'TXT', 'EDIFACT', 'PDF'));

COMMENT ON COLUMN studio.flow_message.content_format IS 'Format de l''échange dans ce flux ; NULL = format du message (message_definition.content_format)';
