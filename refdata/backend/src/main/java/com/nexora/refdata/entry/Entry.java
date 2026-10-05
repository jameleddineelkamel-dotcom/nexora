package com.nexora.refdata.entry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Un code d'une table de référence : valeur, libellés FR/EN, parent (hiérarchie), attributs typés,
 * période de validité et statut (un code n'est jamais supprimé : il est invalidé).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Entry(String code, String labelFr, String labelEn, String parentCode, Map<String, Object> attributes,
                    LocalDate validFrom, LocalDate validTo, String status, Long version, Long childCount,
                    OffsetDateTime createdAt, OffsetDateTime updatedAt) {

    public Entry avec(Map<String, Object> attributs) {
        return new Entry(code, labelFr, labelEn, parentCode, attributs, validFrom, validTo, status, version, childCount, createdAt, updatedAt);
    }

    /** Vrai si les données métier sont identiques (sert à compter les lignes inchangées d'un import). */
    public boolean memesDonnees(Entry o) {
        return java.util.Objects.equals(labelFr, o.labelFr) && java.util.Objects.equals(labelEn, o.labelEn)
                && java.util.Objects.equals(parentCode, o.parentCode) && java.util.Objects.equals(validFrom, o.validFrom)
                && java.util.Objects.equals(validTo, o.validTo) && java.util.Objects.equals(status, o.status)
                && comparables(attributes).equals(comparables(o.attributes));
    }

    /** Attributs sans ordre ni type (le JSONB réordonne les clés, les nombres peuvent changer de représentation). */
    private static java.util.Map<String, String> comparables(Map<String, Object> a) {
        java.util.Map<String, String> m = new java.util.TreeMap<>();
        if (a != null) a.forEach((k, v) -> {
            if (v == null) return;
            String s = v.toString();
            if (v instanceof Number) s = new java.math.BigDecimal(s).stripTrailingZeros().toPlainString();
            m.put(k, s);
        });
        return m;
    }

    public record Page<T>(java.util.List<T> items, long total, int page, int size) {}

    /** Élément compact pour les listes déroulantes des autres microservices. */
    public record LookupItem(String code, String label, String parentCode) {}
}
