package com.nexora.registration.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Habilitations du registre : toute API exige un jeton SSO ; les droits fins (lecture, gestion, complétion, accès d'un
 * opérateur à sa propre fiche) sont contrôlés par le service, car un opérateur doit pouvoir lire la fiche à laquelle il
 * est rattaché sans disposer du droit de lecture général.
 */
@Configuration
public class SecurityConfig {

    public static final String LIRE = "registration.lire", GERER = "registration.gerer", COMPLETER = "registration.completer";

    @Bean
    SecurityFilterChain securite(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/config", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/**").authenticated()
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

    private static DefaultBearerTokenResolver resolveur() {
        DefaultBearerTokenResolver r = new DefaultBearerTokenResolver();
        r.setAllowUriQueryParameter(true);
        return r;
    }
}
