package com.nexora.procedures.studio;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Modèle de conception d'une procédure (dossier v4.2 §10.13) : une version porte ses types de demande, acteurs,
 * statuts, formulaire, pièces, frais, catalogue de messages, flux, workflow BPMN, règles DRL et livrables.
 * Ces enregistrements sont aussi la forme JSON de l'API et, figés, celle du paquet publié.
 */
public final class Modele {

    private Modele() {}

    public record Procedure(String code, String labelFr, String labelEn, String circuitCode, String authority, String description,
                            boolean active, OffsetDateTime createdAt, String createdBy) {}

    /** Ligne de liste : procédure, sa terminologie GUCE et ses versions courantes. */
    public record ProcedureResume(String code, String labelFr, String labelEn, String authority, String circuitCode, String circuitFr,
                                  String phaseCode, String phaseFr, String processCode, String processFr, String bspArea,
                                  Long publishedVersionId, Integer publishedVersionNo, OffsetDateTime publishedAt,
                                  Long workingVersionId, Integer workingVersionNo, String workingStatus, int versionCount) {}

    public record Version(long id, String procedureCode, int versionNo, String lifecycleStatus, Map<String, Object> settings,
                          String changeNote, OffsetDateTime createdAt, String createdBy, OffsetDateTime updatedAt, String updatedBy,
                          OffsetDateTime submittedAt, String submittedBy, OffsetDateTime approvedAt, String approvedBy,
                          OffsetDateTime publishedAt, String publishedBy, OffsetDateTime retiredAt, String packageHash) {}

    public record RequestType(String code, String labelFr, String labelEn, String curCode, List<String> natures, String description,
                              int sortOrder) {}

    public record Actor(String code, String labelFr, String labelEn, String kind, String systemCode, String authRole,
                        String responsibilities, int sortOrder) {}

    public record CaseStatus(String code, String labelFr, String labelEn, boolean isFinal, String tone, int sortOrder) {}

    /** Section du formulaire ; {@code parentCode} en fait une sous-section (un seul niveau d'imbrication). */
    public record Section(String code, String labelFr, String labelEn, boolean repeating, int minRows, int sortOrder, String parentCode) {

        public Section(String code, String labelFr, String labelEn, boolean repeating, int minRows, int sortOrder) {
            this(code, labelFr, labelEn, repeating, minRows, sortOrder, null);
        }
    }

    public record CodeValue(String code, String labelFr, String labelEn) {}

    public record Field(String code, String sectionCode, String labelFr, String labelEn, String dataType, Integer maxLength,
                        String pattern, String mandatory, String conditionText, String refdataTable, List<CodeValue> codeValues,
                        String untdedRef, String wcoRef, String cclDen, String cclUid, String biePath, boolean searchable,
                        String prefill, boolean amendable, String helpFr, String helpEn, int sortOrder) {

        public Field avecBie(String chemin) {
            return new Field(code, sectionCode, labelFr, labelEn, dataType, maxLength, pattern, mandatory, conditionText, refdataTable,
                    codeValues, untdedRef, wcoRef, cclDen, cclUid, chemin, searchable, prefill, amendable, helpFr, helpEn, sortOrder);
        }

        public Field avecListe(String table) {
            return new Field(code, sectionCode, labelFr, labelEn, "CODE", maxLength, pattern, mandatory, conditionText, table,
                    codeValues, untdedRef, wcoRef, cclDen, cclUid, biePath, searchable, prefill, amendable, helpFr, helpEn, sortOrder);
        }
    }

    public record RequiredDocument(String code, String labelFr, String labelEn, boolean mandatory, String conditionText,
                                   List<String> requestTypes, String observation, int sortOrder) {}

    public record FeeItem(String code, String beneficiary, String labelFr, String labelEn, BigDecimal amount, String currency,
                          List<String> requestTypes, List<String> natures, int sortOrder) {}

    public record MessageField(int seq, String fieldCode, String path, boolean mandatory, boolean indexed) {}

    public record MessageDef(String code, String labelFr, String labelEn, String messageType, String contentFormat, String rootElement,
                             String standardRef, String schemaText, String sensitivity, String description, List<MessageField> fields) {}

    public record FlowMessage(int seq, String messageCode, String senderActor, String receiverActor, String interactionPattern,
                              Integer responseToSeq, Integer timeoutHours) {}

    public record Flow(String code, String labelFr, String labelEn, int sortOrder, List<FlowMessage> messages) {}

    public record ProcessModel(String processKey, String bpmnXml, OffsetDateTime updatedAt, String updatedBy) {}

    /** Règle de gestion en clair (RG / RM du SFD), sa source et la règle DRL qui la met en œuvre. */
    public record BusinessRule(String code, String text, String source, String drlRule) {}

    /**
     * Règles d'une version. L'approbation porte sur l'empreinte du DRL : toute modification du DRL la rend caduque
     * ({@link #approuvees()} redevient faux) sans qu'il soit besoin de l'effacer.
     */
    public record RuleSet(String drl, List<BusinessRule> businessRules, OffsetDateTime updatedAt, String updatedBy,
                          String approvedBy, OffsetDateTime approvedAt, String approvedHash) {

        public RuleSet(String drl, List<BusinessRule> businessRules, OffsetDateTime updatedAt, String updatedBy) {
            this(drl, businessRules, updatedAt, updatedBy, null, null, null);
        }

        public boolean approuvees() {
            return approvedHash != null && drl != null && approvedHash.equals(VersionService.sha256(drl));
        }
    }

    public record Deliverable(String code, String labelFr, String labelEn, String docCode, String validityRule, String titleFr,
                              List<String> bodyFields, String signatureNote) {}

    /** Version complète, telle qu'éditée dans le studio. */
    public record VersionComplete(Version version, Procedure procedure, List<RequestType> requestTypes, List<Actor> actors,
                                  List<CaseStatus> statuses, List<Section> sections, List<Field> fields,
                                  List<RequiredDocument> documents, List<FeeItem> fees, List<MessageDef> messages, List<Flow> flows,
                                  ProcessModel process, RuleSet rules, List<Deliverable> deliverables) {}

    /** En-tête modifiable d'une version (paramètres d'exécution et note de version). */
    public record Parametres(Map<String, Object> settings, String changeNote) {}

    /** Constat d'un contrôle de conformité (Compliance Agent). */
    public record Constat(String severity, String rule, String target, String message) {
        public static Constat erreur(String regle, String cible, String message) { return new Constat("error", regle, cible, message); }
        public static Constat alerte(String regle, String cible, String message) { return new Constat("warning", regle, cible, message); }
        public static Constat info(String regle, String cible, String message) { return new Constat("info", regle, cible, message); }
    }
}
