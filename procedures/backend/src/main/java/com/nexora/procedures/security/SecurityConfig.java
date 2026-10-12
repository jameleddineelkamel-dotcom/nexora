package com.nexora.procedures.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Habilitations du studio des procédures : chaque appel à l'API exige un jeton du microservice auth (SSO NEXORA)
 * et le droit correspondant à l'action. Séparation des tâches : modéliser, approuver et publier sont trois droits
 * distincts (RG-GOV-04).
 */
@Configuration
public class SecurityConfig {

    public static final String LIRE = "PERM_procedures.lire", MODELISER = "PERM_procedures.modeliser",
            APPROUVER = "PERM_procedures.approuver", PUBLIER = "PERM_procedures.publier";

    @Bean
    SecurityFilterChain securite(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/config", "/actuator/health", "/actuator/info").permitAll()
                        // Cycle de vie : approuver / renvoyer, publier / retirer
                        .requestMatchers(HttpMethod.POST, "/api/v1/versions/*/approve", "/api/v1/versions/*/reject").hasAuthority(APPROUVER)
                        .requestMatchers(HttpMethod.POST, "/api/v1/versions/*/publish", "/api/v1/versions/*/retire").hasAuthority(PUBLIER)
                        // Coupe-circuit des agents (AC-AG-06)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/agents/**").hasAnyAuthority(APPROUVER, PUBLIER)
                        // Modélisation : procédures, versions, agents, propositions, terminologie
                        .requestMatchers(HttpMethod.POST, "/api/**").hasAuthority(MODELISER)
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasAuthority(MODELISER)
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasAuthority(MODELISER)
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAuthority(LIRE)
                        .requestMatchers("/api/**").denyAll()
                        // Interface Angular (la connexion se fait côté navigateur, par redirection SSO)
                        .anyRequest().permitAll())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(resolveur())
                        .jwt(j -> j.jwtAuthenticationConverter(jwt -> {
                            List<String> perms = jwt.getClaimAsStringList("permissions");
                            return new JwtAuthenticationToken(jwt, perms == null ? List.of()
                                    : perms.stream().map(p -> new SimpleGrantedAuthority("PERM_" + p)).toList(),
                                    jwt.getClaimAsString("preferred_username"));
                        })));
        return http.build();
    }

    /** Jeton dans l'en-tête Authorization, ou en paramètre access_token pour les téléchargements par lien. */
    private static DefaultBearerTokenResolver resolveur() {
        DefaultBearerTokenResolver r = new DefaultBearerTokenResolver();
        r.setAllowUriQueryParameter(true);
        return r;
    }
}
