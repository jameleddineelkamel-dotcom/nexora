package com.nexora.registration.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS pour les autres frontaux NEXORA (e-Services lit les fiches des opérateurs) et routes de l'interface Angular. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] origines;

    public WebConfig(@Value("${nexora.cors.allowed-origins}") String[] origines) {
        this.origines = origines;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origines).allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .exposedHeaders("Content-Disposition");
    }

    @Controller
    static class RoutesInterface {
        @GetMapping({"/operateurs", "/operateurs/**", "/nouvel-operateur"})
        String index() {
            return "forward:/index.html";
        }
    }
}
