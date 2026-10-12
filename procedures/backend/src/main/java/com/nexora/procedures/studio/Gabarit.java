package com.nexora.procedures.studio;

import com.nexora.procedures.agents.BpmnGenerateur;
import com.nexora.procedures.agents.DrlGenerateur;
import com.nexora.procedures.studio.Modele.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Gabarit d'une nouvelle procédure du guichet unique : le socle commun à toutes les procédures (acteurs, statuts,
 * identification du demandeur pré-remplie depuis le registre, lieu de traitement, catalogue de messages et flux,
 * livrable, règles DRL de base, workflow BPMN généré). Le concepteur le complète ensuite (formulaire métier, pièces,
 * frais, règles), seul ou avec les agents (Document Agent sur la SFD, Data Agent, Rules Agent, Compliance Agent).
 */
public final class Gabarit {

    private Gabarit() {}

    /** Remplit la version {@code id} avec le gabarit standard. {@code curCode} : code de procédure du CUR (3 lettres). */
    public static void appliquer(StudioRepository repo, long id, Procedure p, String curCode, String auteur) {
        String code = p.code();
        String cur = curCode == null || !curCode.strip().toUpperCase(Locale.ROOT).matches("^[A-Z]{3}$")
                ? code.replaceAll("[^A-Z]", "").concat("XXX").substring(0, 3) : curCode.strip().toUpperCase(Locale.ROOT);
        repo.typesDemande(id, List.of(new RequestType("STD", p.labelFr(), p.labelEn(), cur, List.of("INITIAL", "AMENDMENT"),
                "Type de demande à préciser (déclinaisons, natures autorisées).", 10)));
        repo.acteurs(id, List.of(
                new Actor("DEMANDEUR", "Opérateur demandeur", "Applicant operator", "DEMANDEUR", "EFORCE", "OPERATEUR_ECONOMIQUE", "Dépose la demande, paie les frais, reçoit le livrable", 10),
                new Actor("ADMINISTRATION", p.authority() == null || p.authority().isBlank() ? "Administration compétente" : p.authority(), null, "ADMINISTRATION", "EGOV", null, "Instruit et délivre", 20),
                new Actor("AGENT_TRAITEMENT", "Agent de traitement", "Processing officer", "ADMINISTRATION", "EGOV", "AGENT_TRAITEMENT", "Traite les dossiers cotés", 30),
                new Actor("SIGNATAIRE", "Agent signataire", "Signing officer", "ADMINISTRATION", "EGOV", "AGENT_SIGNATAIRE", "Valide et signe le livrable", 40),
                new Actor("GUCE", "Guichet unique", "Single window", "SYSTEME", "EPAYMENT", null, "Avis de paiement, commission", 50),
                new Actor("BANQUE", "Banques", "Banks", "BANQUE", "EPAYMENT", "BANQUE", "Encaissement des frais", 60),
                new Actor("REGISTRE", "Registre des opérateurs", "Operator registry", "SYSTEME", "EXTERNE", null, "Pré-remplissage et mise à jour des opérateurs", 70)));
        repo.statuts(id, List.of(
                new CaseStatus("BROUILLON", "Brouillon", "Draft", false, "neutral", 10),
                new CaseStatus("EN_ATTENTE_PAIEMENT", "En attente de paiement", "Awaiting payment", false, "warn", 20),
                new CaseStatus("A_COTER", "À coter", "To be assigned", false, "info", 30),
                new CaseStatus("EN_ATTENTE_TRAITEMENT", "En attente de traitement", "Awaiting processing", false, "info", 40),
                new CaseStatus("COMPLEMENT_DEMANDE", "Complément d'information demandé", "Additional information requested", false, "warn", 50),
                new CaseStatus("EN_ATTENTE_SIGNATURE", "En attente de signature", "Awaiting signature", false, "info", 60),
                new CaseStatus("VALIDE", "Validé — livrable délivré", "Approved — deliverable issued", true, "ok", 70),
                new CaseStatus("REJETE", "Rejeté", "Rejected", true, "danger", 80),
                new CaseStatus("ANNULE", "Annulé", "Cancelled", true, "neutral", 90)));
        repo.formulaire(id, List.of(
                        new Section("DEMANDE", "Demande", "Request", false, 0, 10),
                        new Section("IDENTITE", "Informations générales", "General information", false, 0, 20),
                        new Section("TRAITEMENT", "Informations relatives au traitement", "Processing information", false, 0, 90)),
                List.of(
                        champ("previousRequestNumber", "DEMANDE", "Dossier source", "Source case", "TEXT", 35, "CONDITIONNEL", "Amendement : dossier source", "1004",
                                "Exchanged Document. Referenced. Identifier", false, null, false, 10),
                        champ("taxIdentifier", "IDENTITE", "Numéro d'identification unique (NIU)", "Taxpayer identification number", "TEXT", 35, "OUI", null, "3039",
                                "Tax Registration. Identification. Identifier", true, "niu", false, 10),
                        champ("legalName", "IDENTITE", "Nom / Raison sociale", "Name / Company name", "TEXT", 200, "OUI", null, "3036",
                                "Legal Entity. Name. Text", true, "legalName", true, 20),
                        champ("cityName", "IDENTITE", "Ville", "City", "TEXT", 35, "OUI", null, "3164", "Trade Address. City. Name. Text", true, "city", true, 30),
                        champ("emailAddress", "IDENTITE", "Adresse électronique", "E-mail", "EMAIL", 254, "OUI", null, "3153", "URI Communication. URI. Identifier", false, "email", true, 40),
                        new Field("processingLocation", "TRAITEMENT", "Lieu de traitement", "Processing location", "CODE", 10, null, "OUI", null, "@LIEUX_TRAITEMENT",
                                null, null, null, null, null, null, true, null, false, "Détermine l'unité administrative et la cotation du dossier.", null, 10)));
        repo.frais(id, List.of(new FeeItem("GUCE", "GUCE", "Commission du guichet unique", "Single window fee", new BigDecimal("0"), "XAF", List.of(),
                List.of("INITIAL"), 10)));
        String m = code.length() > 20 ? code.substring(0, 20) : code;   // codes de messages : 40 caractères au plus
        repo.messages(id, List.of(
                new MessageDef(m + "_REQUEST", "Demande", "Request", "REQUEST", "JSON", p.code().charAt(0) + p.code().substring(1).toLowerCase(Locale.ROOT) + "Request",
                        "UN/CCL D23B / WCO DM", null, "personal_data", "Demande transmise à l'administration après dépôt (et paiement).",
                        List.of(new MessageField(1, "taxIdentifier", "$.taxIdentifier", true, true), new MessageField(2, "legalName", "$.legalName", true, true),
                                new MessageField(3, "processingLocation", "$.processingLocation", true, true))),
                new MessageDef(m + "_PAYMENT_NOTICE", "Avis de paiement", "Payment notice", "PAYMENT", "XML", "PaymentNotice", "WCO DM [142] Invoice", null, "internal", null,
                        List.of(new MessageField(1, null, "PaymentNotice/paymentNoticeNumber", true, true), new MessageField(2, null, "PaymentNotice/requestId", true, true),
                                new MessageField(3, null, "PaymentNotice/totalpaymentNoticeAmount", true, true), new MessageField(4, null, "PaymentNotice/paymentNoticeCurrencyCode", true, false))),
                new MessageDef("PAYMENT_CONFIRMATION", "Confirmation de paiement (banque)", "Payment confirmation (bank)", "PAYMENT", "XML", "PaymentConfirmation",
                        "UN/CEFACT Rec. 17 / ISO 20022 (simplifié)", null, "internal", null,
                        List.of(new MessageField(1, null, "PaymentConfirmation/paymentNoticeNumber", true, true), new MessageField(2, null, "PaymentConfirmation/paymentReferenceText", true, true),
                                new MessageField(3, null, "PaymentConfirmation/paidAmount", true, false), new MessageField(4, null, "PaymentConfirmation/currencyCode", true, false),
                                new MessageField(5, null, "PaymentConfirmation/paidAt", true, false), new MessageField(6, null, "PaymentConfirmation/bankCode", false, false))),
                new MessageDef(m + "_COMPLEMENT", "Demande de complément", "Request for information", "NOTIFICATION", "TXT", null, "Notification eGOV → eFORCE", null, "internal", null,
                        List.of(new MessageField(1, null, "dossierNo", true, true), new MessageField(2, null, "observation", true, false))),
                new MessageDef(m + "_DECISION", "Décision de rejet", "Rejection decision", "DECISION", "JSON", "Decision", "UN/EDIFACT APERAK (simplifié)", null, "internal", null,
                        List.of(new MessageField(1, null, "$.dossierNo", true, true), new MessageField(2, null, "$.decision", true, true), new MessageField(3, null, "$.observation", true, false))),
                new MessageDef(m + "_DOCUMENT", "Livrable délivré", "Issued deliverable", "DOCUMENT", "JSON", "IssuedDocument", "WCO DM [034], [036]", null, "internal", null,
                        List.of(new MessageField(1, null, "$.documentReferenceNumber", true, true), new MessageField(2, "taxIdentifier", "$.taxIdentifier", true, true),
                                new MessageField(3, null, "$.expirationDatetime", false, true), new MessageField(4, null, "$.qrCodeText", true, false),
                                new MessageField(5, null, "$.fileURIIdentifier", true, false))),
                new MessageDef(m + "_ENCAISSEMENTS", "Relevé des encaissements", "Collections statement", "REPORT", "CSV", null, "Relevé de reversement (CSV)", null, "internal", null,
                        List.of(new MessageField(1, null, "dossierNo", true, false), new MessageField(2, null, "paymentNoticeNumber", true, false),
                                new MessageField(3, null, "beneficiary", true, true), new MessageField(4, null, "amount", true, false),
                                new MessageField(5, null, "currency", true, false), new MessageField(6, null, "paidAt", true, false)))));
        repo.flux(id, List.of(
                new Flow("DEPOT", "Dépôt de la demande", "Request submission", 10, List.of(new FlowMessage(1, m + "_REQUEST", "DEMANDEUR", "ADMINISTRATION", "ASYNC", null, null))),
                new Flow("PAIEMENT", "Paiement des frais", "Fee payment", 20, List.of(new FlowMessage(1, m + "_PAYMENT_NOTICE", "GUCE", "DEMANDEUR", "NOTIFY", null, null),
                        new FlowMessage(2, "PAYMENT_CONFIRMATION", "BANQUE", "GUCE", "ASYNC", 1, 720))),
                new Flow("INSTRUCTION", "Instruction du dossier", "Case processing", 30, List.of(new FlowMessage(1, m + "_COMPLEMENT", "AGENT_TRAITEMENT", "DEMANDEUR", "NOTIFY", null, null),
                        new FlowMessage(2, m + "_DECISION", "AGENT_TRAITEMENT", "DEMANDEUR", "NOTIFY", null, null))),
                new Flow("DELIVRANCE", "Délivrance", "Issuance", 40, List.of(new FlowMessage(1, m + "_DOCUMENT", "SIGNATAIRE", "DEMANDEUR", "NOTIFY", null, null))),
                new Flow("REVERSEMENT", "Relevé des encaissements", "Collections statement", 50, List.of(new FlowMessage(1, m + "_ENCAISSEMENTS", "GUCE", "ADMINISTRATION", "NOTIFY", null, null)))));
        repo.livrables(id, List.of(new Deliverable("LIVRABLE", p.labelFr(), p.labelEn(), "DOC", "UN_AN", p.labelFr().toUpperCase(Locale.ROOT),
                List.of("taxIdentifier", "legalName", "cityName"), "Le responsable habilité")));
        // Règles : en-tête, pièces conditionnelles et frais générés (vides au départ), pas de règle de validation métier
        String drl = DrlGenerateur.entete(code);
        drl = DrlGenerateur.remplacerSection(drl, "pieces", "");
        drl = DrlGenerateur.remplacerSection(drl, "frais", DrlGenerateur.frais(repo.frais(id)));
        repo.regles(id, new RuleSet(drl, List.of(), null, null), auteur);
        VersionComplete v = repo.complete(id);
        String cle = code.toLowerCase(Locale.ROOT).replace('_', '-');
        repo.processus(id, new ProcessModel(cle, BpmnGenerateur.generer(v, cle), null, null), auteur);
    }

    private static Field champ(String code, String section, String fr, String en, String type, Integer longueur, String obligatoire, String condition,
                               String untded, String den, boolean recherche, String prefill, boolean amendable, int ordre) {
        return new Field(code, section, fr, en, type, longueur, null, obligatoire, condition, null, null, untded, null, den, null, null, recherche,
                prefill, amendable, null, null, ordre);
    }
}
