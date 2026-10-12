package com.nexora.procedures.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Configuration publique de l'interface : service d'authentification unique et autres services de la plateforme. */
@RestController
public class ConfigController {

    private final Map<String, String> config;

    public ConfigController(@Value("${nexora.auth.url}") String authUrl, @Value("${nexora.refdata.url}") String refdataUrl,
                            @Value("${nexora.eservices.url}") String eservicesUrl) {
        this.config = Map.of("authUrl", authUrl, "refdataUrl", refdataUrl, "eservicesUrl", eservicesUrl, "application", "procedures");
    }

    @GetMapping("/api/v1/config")
    public Map<String, String> config() {
        return config;
    }
}
