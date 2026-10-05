package com.nexora.refdata.catalogue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Modèle du catalogue des tables de référence. */
public final class CatalogueModel {

    private CatalogueModel() {}

    public static final Set<String> SOURCES = Set.of("INTERNATIONALE", "REGIONALE", "NATIONALE");
    public static final Set<String> STATUTS = Set.of("ACTIVE", "DRAFT", "ARCHIVED");
    public static final Set<String> ROLES = Set.of("CODE", "LABEL_FR", "LABEL_EN", "ATTRIBUTE");
    public static final Set<String> TYPES = Set.of("STRING", "TEXT", "INTEGER", "DECIMAL", "DATE", "BOOLEAN", "CODE_REF");
    /** Modèle de référence de la chaîne logistique internationale (UN/CEFACT) : Buy – Ship – Pay. */
    public static final List<String> PHASES_BSP = List.of("BUY", "SHIP", "PAY");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Category(String code, String parentCode, String labelFr, String labelEn, String description, String source,
                           String icon, String color, int sortOrder, long tableCount, long entryCount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ColumnDef(String key, String labelFr, String labelEn, String role, String dataType, Integer maxLength,
                            String pattern, boolean required, String cardinality, String xmlTag, String untded, String unit,
                            String definition, String comment, String refTableCode, int sortOrder) {

        public boolean attribut() {
            return "ATTRIBUTE".equals(role);
        }
    }

    /** Résumé d'une table pour les listes du catalogue. */
    public record TableSummary(String code, String number, String nameFr, String nameEn, String description, String categoryCode,
                               String source, List<String> bspPhases, String status, String contentType, String standards,
                               long entryCount, long activeCount, int completeness, OffsetDateTime updatedAt) {}

    /** Définition complète d'une table : identité, fiche ISO 19115, structure et indicateurs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TableDef(String code, String number, String nameFr, String nameEn, String description, String categoryCode,
                           String source, String standards, String producer, String updateAuthority, String obtentionMode,
                           String updateMode, List<String> bspPhases, String parentTableCode, Map<String, Object> metadata,
                           String sourceDocument, String dataSource, String status, List<ColumnDef> columns, Long version,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt, long entryCount, long activeCount,
                           int completeness, String contentType, String sqlView) {

        public ColumnDef colonne(String role) {
            return columns == null ? null : columns.stream().filter(c -> role.equals(c.role())).findFirst().orElse(null);
        }

        public List<ColumnDef> attributs() {
            return columns == null ? List.of() : columns.stream().filter(ColumnDef::attribut).toList();
        }
    }

    /** Champ de la fiche de métadonnées (norme ISO 19115, telle que retenue par le rapport Référentiel Commun). */
    public record ChampMetadonnee(String key, String labelFr, String group, boolean longText) {}

    public static final List<ChampMetadonnee> FICHE_ISO_19115 = List.of(
            new ChampMetadonnee("description", "Description", "IDENTIFICATION", true),
            new ChampMetadonnee("standards", "Standards", "IDENTIFICATION", true),
            new ChampMetadonnee("languages", "Langues", "IDENTIFICATION", false),
            new ChampMetadonnee("usage", "Utilisation (applications métiers utilisatrices)", "IDENTIFICATION", true),
            new ChampMetadonnee("metadataStandard", "Standard de métadonnées", "IDENTIFICATION", false),
            new ChampMetadonnee("theme", "Thématique", "IDENTIFICATION", false),
            new ChampMetadonnee("charset", "Jeu de caractères", "IDENTIFICATION", false),
            new ChampMetadonnee("role", "Rôle", "IDENTIFICATION", true),
            new ChampMetadonnee("producer", "Producteur-fournisseur / autorité de mise à jour", "IDENTIFICATION", false),
            new ChampMetadonnee("version", "Version", "IDENTIFICATION", false),
            new ChampMetadonnee("diffusionPoint", "Point de diffusion", "IDENTIFICATION", false),
            new ChampMetadonnee("keywords", "Mots clés descriptifs", "IDENTIFICATION", false),
            new ChampMetadonnee("creationDate", "Date de création", "CYCLE_DE_VIE", false),
            new ChampMetadonnee("revisionDate", "Date de dernière mise à jour (révision)", "CYCLE_DE_VIE", false),
            new ChampMetadonnee("updateFrequency", "Fréquence de mise à jour", "CYCLE_DE_VIE", false),
            new ChampMetadonnee("updateMode", "Mode d'actualisation", "CYCLE_DE_VIE", false),
            new ChampMetadonnee("maintenanceRules", "Règles de modification / modalités de maintenance", "CYCLE_DE_VIE", true),
            new ChampMetadonnee("traceability", "Traçabilité de l'évolution dans le temps", "CYCLE_DE_VIE", true),
            new ChampMetadonnee("diffusionRules", "Règles de diffusion (ressource en ligne)", "CYCLE_DE_VIE", true),
            new ChampMetadonnee("obtentionMode", "Mode d'obtention", "CYCLE_DE_VIE", true),
            new ChampMetadonnee("lastRequestAuthors", "Auteurs de la dernière demande de mise à jour", "CYCLE_DE_VIE", false),
            new ChampMetadonnee("updateAuthority", "Autorité nationale de mise à jour", "ADMINISTRATIF", false),
            new ChampMetadonnee("useRestrictions", "Restrictions d'usage", "ADMINISTRATIF", false),
            new ChampMetadonnee("accessConstraints", "Contraintes d'accès", "ADMINISTRATIF", false),
            new ChampMetadonnee("producerContact", "Contact : producteur", "ADMINISTRATIF", true),
            new ChampMetadonnee("administratorContact", "Contact : organisme de diffusion / administrateur", "ADMINISTRATIF", true));

    /** Champs promus en colonnes de ref_table ; les autres vivent dans le JSONB metadata. */
    public static final Set<String> CHAMPS_COLONNES = Set.of("description", "standards", "producer", "updateAuthority",
            "obtentionMode", "updateMode");

    /** Taux de complétude de la fiche ISO 19115 (0 à 100). */
    public static int completude(TableDef t) {
        int remplis = 0;
        for (ChampMetadonnee c : FICHE_ISO_19115) {
            Object v = switch (c.key()) {
                case "description" -> t.description();
                case "standards" -> t.standards();
                case "producer" -> t.producer();
                case "updateAuthority" -> t.updateAuthority();
                case "obtentionMode" -> t.obtentionMode();
                case "updateMode" -> t.updateMode();
                default -> t.metadata() == null ? null : t.metadata().get(c.key());
            };
            if (v != null && !v.toString().isBlank()) remplis++;
        }
        return Math.round(remplis * 100f / FICHE_ISO_19115.size());
    }
}
