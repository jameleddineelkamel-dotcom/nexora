package com.nexora.eservices.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS pour le frontal de développement et renvoi des routes de l'interface Angular vers index.html. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] origines;

    public WebConfig(@Value("${nexora.cors.allowed-origins}") String[] origines) {
        this.origines = origines;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origines).allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .exposedHeaders("Content-Disposition");
    }

    @Controller
    static class RoutesInterface {
        @GetMapping({"/demandes", "/demandes/**", "/nouvelle-demande", "/nouvelle-demande/**", "/dossiers", "/dossiers/**", "/taches",
                "/paiements", "/paiements/**", "/messages", "/messages/**", "/supervision", "/administration", "/administration/**",
                "/verifier", "/verifier/**"})
        String index() {
            return "forward:/index.html";
        }
    }
}
