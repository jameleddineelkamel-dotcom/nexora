package com.nexora.refdata.search;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Recherche par mots : chaque mot (2 caractères ou plus) doit figurer dans le texte, sans tenir compte
 * de la casse ni des accents. Une condition LIKE par mot permet à PostgreSQL de combiner l'index trigramme.
 * Ex. « cote ivoire » trouve « Côte d'Ivoire ».
 */
public final class Recherche {

    private Recherche() {}

    public static List<String> mots(String q) {
        if (q == null) return List.of();
        return Arrays.stream(q.strip().split("[\\s'’,;/()\\-]+")).filter(m -> m.length() >= 2).distinct().limit(6).toList();
    }

    /** Fragment « and expr like … » pour chaque mot ; les paramètres sont ajoutés à {@code params}. */
    public static String clause(String expression, String q, Map<String, Object> params, String prefixe) {
        List<String> mots = mots(q);
        if (mots.isEmpty() && q != null && !q.isBlank()) mots = List.of(q.strip());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mots.size(); i++) {
            String nom = prefixe + i;
            sb.append(" and ").append(expression).append(" like '%' || ref_texte_recherche(cast(:").append(nom).append(" as text)) || '%'");
            params.put(nom, mots.get(i));
        }
        return sb.toString();
    }
}
