package com.nexora.registration.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Configuration publique de l'interface : service d'authentification unique et référentiel commun. */
@RestController
public class ConfigController {

    private final Map<String, String> config;

    public ConfigController(@Value("${nexora.auth.url}") String authUrl, @Value("${nexora.refdata.url}") String refdataUrl) {
        this.config = Map.of("authUrl", authUrl, "refdataUrl", refdataUrl, "application", "registration");
    }

    @GetMapping("/api/v1/config")
    public Map<String, String> config() {
        return config;
    }
}
