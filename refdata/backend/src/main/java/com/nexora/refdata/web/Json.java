package com.nexora.refdata.web;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Conversions JSON ⇄ objets pour les colonnes JSONB (Jackson 3). */
@Component
public class Json {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {};

    private final JsonMapper mapper;

    public Json(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String ecrire(Object valeur) {
        return mapper.writeValueAsString(valeur == null ? Map.of() : valeur);
    }

    public Map<String, Object> map(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        return mapper.readValue(json, MAP);
    }

    public <T> T lire(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    public <T> T lire(java.io.InputStream in, Class<T> type) {
        return mapper.readValue(in, type);
    }

    public JsonMapper mapper() {
        return mapper;
    }
}
