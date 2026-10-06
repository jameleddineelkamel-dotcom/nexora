package com.nexora.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("nexora.auth")
public record AuthProperties(String issuer, Duration tokenValidity, Duration sessionValidity, List<String> redirectOrigins,
                             String adminPassword, boolean secureCookie, List<String> applications) {

    /** Application de la plateforme proposée sur le portail après connexion (« code|libellé|url »). */
    public record Application(String code, String label, String url) {}

    public List<Application> portail() {
        return applications == null ? List.of() : applications.stream().map(a -> a.split("\\|", 3)).filter(p -> p.length == 3)
                .map(p -> new Application(p[0].strip(), p[1].strip(), p[2].strip())).toList();
    }

    public static final String COOKIE = "NEXORA_SSO";

    /** Une adresse de retour est acceptée si elle commence par une origine autorisée (protection contre la redirection ouverte). */
    public boolean retourAutorise(String uri) {
        if (uri == null || uri.isBlank()) return false;
        try {
            java.net.URI u = java.net.URI.create(uri);
            if (u.getScheme() == null || u.getHost() == null) return false;
            String origine = u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            return redirectOrigins.stream().anyMatch(o -> o.equalsIgnoreCase(origine));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
