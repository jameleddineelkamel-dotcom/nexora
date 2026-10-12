package com.nexora.eservices.dossier;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Dossier d'une procédure (PROCEDURE_CASE §10.14), identifié par son CUR une fois déposé. */
public record Dossier(UUID id, String dossierNo, long deploymentId, String procedureCode, int versionNo, String requestType, String requestNature,
                      String parentDossierNo, String applicantNiu, String applicantName, String submittedBy, boolean onBehalf, String status,
                      String lifecycle, String processingLocation, String adminUnit, String assignedAgent, String signatory, String processInstanceId,
                      Map<String, Object> formData, Map<String, Object> previousData, Map<String, Object> searchCriteria,
                      List<Map<String, Object>> fees, BigDecimal totalAmount, String currency, String lastObservation, OffsetDateTime slaDueAt,
                      OffsetDateTime createdAt, OffsetDateTime submittedAt, OffsetDateTime updatedAt, OffsetDateTime closedAt) {}
