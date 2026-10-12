package com.nexora.procedures.agents;

import com.nexora.procedures.web.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Tables du Référentiel Commun (refdata), lues avec le jeton de l'utilisateur et gardées 10 minutes : sert à relier
 * les listes citées par un modèle de données (REF_UNIT, REF_NSH, REF_PORT…) aux tables réellement disponibles.
 */
@Component
public class Referentiel {

    private static final Logger log = LoggerFactory.getLogger(Referentiel.class);

    /** Noms usuels des modèles de données → tables du Référentiel Commun. */
    private static final Map<String, String> SYNONYMES = Map.ofEntries(
            Map.entry("REF_UNIT", "REF_MEASUREMENT_UNIT"), Map.entry("REF_UNITS", "REF_MEASUREMENT_UNIT"), Map.entry("REF_NSH", "REF_NDP"),
            Map.entry("REF_HS", "REF_NDP"), Map.entry("REF_SH", "REF_NDP"), Map.entry("REF_PORT", "REF_UNLOCODE"), Map.entry("REF_AIRPORT", "REF_UNLOCODE"),
            Map.entry("REF_CDA", "REF_DECLARANT"), Map.entry("REF_DOCUMENT_TYPE", "REF_DOCUMENT_CODES"), Map.entry("REF_DOCUEMENT_TYPE", "REF_DOCUMENT_CODES"),
            Map.entry("REF_COUNTRIES", "REF_COUNTRY"), Map.entry("REF_CURRENCIES", "REF_CURRENCY"), Map.entry("REF_PAYMENT_TERMS", "REF_PAYTERMS"));

    private final String url;
    private final Json json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private volatile Set<String> tables = Set.of();
    private volatile Instant lu = Instant.EPOCH;

    public Referentiel(@Value("${nexora.refdata.url}") String url, Json json) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.json = json;
    }

    /** Codes des tables disponibles (vide si le référentiel est injoignable). */
    public Set<String> tables() {
        if (Duration.between(lu, Instant.now()).toMinutes() < 10 && !tables.isEmpty()) return tables;
        try {
            HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(url + "/api/v1/tables")).timeout(Duration.ofSeconds(5)).GET();
            if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken j)
                r.header("Authorization", "Bearer " + j.getToken().getTokenValue());
            HttpResponse<String> rep = http.send(r.build(), HttpResponse.BodyHandlers.ofString());
            if (rep.statusCode() / 100 != 2) return tables;
            Set<String> codes = new TreeSet<>();
            Object corps = json.mapper().readValue(rep.body(), Object.class);
            List<?> l = corps instanceof List<?> x ? x : corps instanceof Map<?, ?> m && m.get("content") instanceof List<?> c ? c : List.of();
            for (Object o : l) if (o instanceof Map<?, ?> m && m.get("code") != null) codes.add(String.valueOf(m.get("code")));
            tables = codes;
            lu = Instant.now();
        } catch (Exception e) {
            log.debug("Référentiel Commun injoignable : {}", e.getMessage());
        }
        return tables;
    }

    /** Nom de table d'une liste citée, après correspondance connue, sans vérifier son existence. */
    public static String synonyme(String liste) {
        if (liste == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("REF_[A-Z0-9_]+").matcher(liste.toUpperCase(Locale.ROOT));
        return m.find() ? SYNONYMES.getOrDefault(m.group(), m.group()) : null;
    }

    /** Table du Référentiel Commun correspondant à une liste citée (« REF_UNIT », « TABLE REF_COUNTRY »…), ou null. */
    public String table(String liste) {
        if (liste == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("REF_[A-Z0-9_]+").matcher(liste.toUpperCase(Locale.ROOT));
        if (!m.find()) return null;
        String nom = m.group();
        Set<String> t = tables();
        if (t.isEmpty()) return SYNONYMES.getOrDefault(nom, nom);
        if (t.contains(nom)) return nom;
        String s = SYNONYMES.get(nom);
        return s != null && t.contains(s) ? s : null;
    }
}
