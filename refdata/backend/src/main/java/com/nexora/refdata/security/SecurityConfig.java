package com.nexora.refdata.security;

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
 * Habilitations du Référentiel Commun : chaque appel à l'API exige un jeton du microservice auth (SSO NEXORA)
 * et les droits correspondant à l'action. Les rôles livrés (Administrateur, Gestionnaire, Consultation) regroupent
 * ces droits ; ils sont administrés dans le microservice auth.
 */
@Configuration
public class SecurityConfig {

    public static final String LIRE = "PERM_refdata.lire", EXPORT = "PERM_refdata.export", DONNEES = "PERM_refdata.donnees",
            METADONNEES = "PERM_refdata.metadonnees", STRUCTURE = "PERM_refdata.structure";

    @Bean
    SecurityFilterChain securite(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/config", "/actuator/health", "/actuator/info").permitAll()
                        // Structure : création de tables, catégories, archivage, définitions en masse
                        .requestMatchers(HttpMethod.POST, "/api/v1/tables", "/api/v1/tables/infer", "/api/v1/categories", "/api/v1/catalogue/import").hasAuthority(STRUCTURE)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/categories/**").hasAuthority(STRUCTURE)
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/tables/*/status").hasAuthority(STRUCTURE)
                        // Codes et chargements
                        .requestMatchers(HttpMethod.POST, "/api/v1/tables/*/entries", "/api/v1/tables/*/entry/**", "/api/v1/tables/*/import").hasAuthority(DONNEES)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/tables/*/entry").hasAuthority(DONNEES)
                        // Métadonnées (fiche ISO 19115, pièces jointes) ; la modification de colonnes exige en plus STRUCTURE (service)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/tables/*").hasAnyAuthority(METADONNEES, STRUCTURE)
                        .requestMatchers(HttpMethod.POST, "/api/v1/tables/*/attachments").hasAuthority(METADONNEES)
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/tables/*/attachments/*").hasAuthority(METADONNEES)
                        // Lecture et export
                        .requestMatchers(HttpMethod.GET, "/api/v1/tables/*/export", "/api/v1/catalogue/export").hasAuthority(EXPORT)
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
