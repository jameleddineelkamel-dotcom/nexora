package com.nexora.eservices.deploiement;

import java.math.BigDecimal;
import java.util.*;

/**
 * Paquet de procédure publié par le studio (format nexora.procedure/1), vu par le Runtime. Le Runtime n'a besoin de
 * rien d'autre pour exécuter la procédure : formulaire, pièces, frais, catalogue de messages, flux, BPMN, DRL, livrables.
 */
public record Paquet(String format, ProcedureInfo procedure, Map<String, Object> terminology, Map<String, Object> version,
                     Map<String, Object> settings, List<Nature> natures, List<RequestType> requestTypes, List<Actor> actors,
                     List<CaseStatus> statuses, List<Section> sections, List<Field> fields, List<RequiredDocument> documents,
                     List<FeeItem> fees, List<MessageDef> messages, List<Flow> flows, Process process, Rules rules,
                     List<Deliverable> deliverables) {

    public record ProcedureInfo(String code, String labelFr, String labelEn, String circuitCode, String authority, String description) {}

    public record Nature(String code, String labelFr, String labelEn) {}

    public record RequestType(String code, String labelFr, String labelEn, String curCode, List<String> natures, String description, int sortOrder) {}

    public record Actor(String code, String labelFr, String labelEn, String kind, String systemCode, String authRole, String responsibilities, int sortOrder) {}

    public record CaseStatus(String code, String labelFr, String labelEn, boolean isFinal, String tone, int sortOrder) {}

    /** Section du formulaire ; {@code parentCode} en fait une sous-section. */
    public record Section(String code, String labelFr, String labelEn, boolean repeating, int minRows, int sortOrder, String parentCode) {}

    public record CodeValue(String code, String labelFr, String labelEn) {}

    public record Field(String code, String sectionCode, String labelFr, String labelEn, String dataType, Integer maxLength, String pattern,
                        String mandatory, String conditionText, String refdataTable, List<CodeValue> codeValues, String untdedRef, String wcoRef,
                        String cclDen, String cclUid, String biePath, boolean searchable, String prefill, boolean amendable, String helpFr,
                        String helpEn, int sortOrder) {}

    public record RequiredDocument(String code, String labelFr, String labelEn, boolean mandatory, String conditionText, List<String> requestTypes,
                                   String observation, int sortOrder) {}

    public record FeeItem(String code, String beneficiary, String labelFr, String labelEn, BigDecimal amount, String currency,
                          List<String> requestTypes, List<String> natures, int sortOrder) {}

    public record MessageField(int seq, String fieldCode, String path, boolean mandatory, boolean indexed) {}

    public record MessageDef(String code, String labelFr, String labelEn, String messageType, String contentFormat, String rootElement,
                             String standardRef, String schemaText, String sensitivity, String description, List<MessageField> fields) {}

    public record FlowMessage(int seq, String messageCode, String senderActor, String receiverActor, String interactionPattern,
                              Integer responseToSeq, Integer timeoutHours) {}

    public record Flow(String code, String labelFr, String labelEn, int sortOrder, List<FlowMessage> messages) {}

    public record Process(String processKey, String bpmnXml) {}

    public record Rules(String drl, List<Map<String, Object>> businessRules) {}

    public record Deliverable(String code, String labelFr, String labelEn, String docCode, String validityRule, String titleFr,
                              List<String> bodyFields, String signatureNote) {}

    /** Usage d'un message dans un flux : flux, rang, émetteur et destinataire (RG-TRX-02). */
    public record Usage(Flow flow, FlowMessage message) {}

    public Optional<Field> champ(String code) {
        return fields.stream().filter(f -> f.code().equals(code)).findFirst();
    }

    public List<Field> champsDe(String section) {
        return fields.stream().filter(f -> f.sectionCode().equals(section)).toList();
    }

    public Optional<Section> section(String code) {
        return sections.stream().filter(s -> s.code().equals(code)).findFirst();
    }

    public Optional<MessageDef> message(String code) {
        return messages.stream().filter(m -> m.code().equals(code)).findFirst();
    }

    public Optional<Usage> usage(String messageCode) {
        for (Flow f : flows)
            for (FlowMessage m : f.messages())
                if (m.messageCode().equals(messageCode)) return Optional.of(new Usage(f, m));
        return Optional.empty();
    }

    public Optional<RequestType> typeDemande(String code) {
        return requestTypes.stream().filter(t -> t.code().equals(code)).findFirst();
    }

    public Optional<CaseStatus> statut(String code) {
        return statuses.stream().filter(s -> s.code().equals(code)).findFirst();
    }

    public Optional<Actor> acteur(String code) {
        return actors.stream().filter(a -> a.code().equals(code)).findFirst();
    }

    public Optional<Deliverable> livrable(String code) {
        return deliverables.stream().filter(d -> d.code().equals(code)).findFirst();
    }

    /** Premier message du catalogue d'un type donné (ex. REQUEST transmis à eGOV après le paiement). */
    public Optional<MessageDef> premierMessage(String type) {
        return messages.stream().filter(m -> m.messageType().equals(type)).findFirst();
    }

    public String reglage(String cle, String defaut) {
        Object v = settings == null ? null : settings.get(cle);
        return v == null || String.valueOf(v).isBlank() ? defaut : String.valueOf(v);
    }

    public int versionNo() {
        Object v = version == null ? null : version.get("versionNo");
        return v instanceof Number n ? n.intValue() : 1;
    }
}
