package com.nexora.refdata.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Configuration publique de l'interface : adresse du service d'authentification unique. */
@RestController
public class ConfigController {

    private final String authUrl;

    public ConfigController(@Value("${nexora.auth.url}") String authUrl) {
        this.authUrl = authUrl;
    }

    @GetMapping("/api/v1/config")
    public Map<String, String> config() {
        return Map.of("authUrl", authUrl, "application", "refdata");
    }
}
