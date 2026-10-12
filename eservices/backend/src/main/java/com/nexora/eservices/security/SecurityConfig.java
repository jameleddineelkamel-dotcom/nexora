package com.nexora.eservices.security;

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
 * Habilitations des e-Services : eFORCE (opérateurs, CDA), eGOV (agents de traitement, signataires), e-PAYMENT
 * (banques), supervision et administration. Les contrôles fins (dossiers de l'opérateur, dossiers cotés à l'agent,
 * RG2 UC-04) sont faits par les services ; le filtre exige ici un jeton SSO valide et le droit de la famille d'API.
 */
@Configuration
public class SecurityConfig {

    public static final String DEPOSER = "eservices.deposer", MANDATAIRE = "eservices.mandataire", TRAITER = "eservices.traiter",
            SIGNER = "eservices.signer", ENCAISSER = "eservices.encaisser", SUPERVISER = "eservices.superviser",
            ADMINISTRER = "eservices.administrer";

    @Bean
    SecurityFilterChain securite(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/config", "/api/v1/public/**", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasAuthority(p(ADMINISTRER))
                        .requestMatchers("/api/v1/supervision/**").hasAnyAuthority(p(SUPERVISER), p(ADMINISTRER))
                        .requestMatchers(HttpMethod.POST, "/api/v1/messages/inbound").hasAnyAuthority(p(ENCAISSER), p(ADMINISTRER))
                        .requestMatchers("/api/**").hasAnyAuthority(p(DEPOSER), p(TRAITER), p(SIGNER), p(ENCAISSER), p(SUPERVISER), p(ADMINISTRER))
                        // Interface Angular (la connexion se fait côté navigateur, par redirection SSO)
                        .anyRequest().permitAll())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(resolveur())
                        .jwt(j -> j.jwtAuthenticationConverter(jwt -> {
                            List<String> perms = jwt.getClaimAsStringList("permissions");
                            return new JwtAuthenticationToken(jwt, perms == null ? List.of()
                                    : perms.stream().map(x -> new SimpleGrantedAuthority("PERM_" + x)).toList(),
                                    jwt.getClaimAsString("preferred_username"));
                        })));
        return http.build();
    }

    public static String p(String droit) {
        return "PERM_" + droit;
    }

    /** Jeton dans l'en-tête Authorization, ou en paramètre access_token pour les téléchargements par lien. */
    private static DefaultBearerTokenResolver resolveur() {
        DefaultBearerTokenResolver r = new DefaultBearerTokenResolver();
        r.setAllowUriQueryParameter(true);
        return r;
    }
}
