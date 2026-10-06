package com.nexora.auth.security;

import com.nexora.auth.config.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    public static final String ADMIN = "PERM_auth.administration";

    @Bean
    SecurityFilterChain securite(HttpSecurity http, AuthProperties props) throws Exception {
        http.csrf(c -> c.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/v1/admin/**").hasAuthority(ADMIN)
                        .requestMatchers("/api/v1/auth/me", "/api/v1/auth/password").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(convertisseur())));
        return http.build();
    }

    /** Permissions du jeton (« permissions ») → autorités Spring « PERM_xxx ». */
    public static Converter<Jwt, AbstractAuthenticationToken> convertisseur() {
        return jwt -> {
            List<String> perms = jwt.getClaimAsStringList("permissions");
            return new JwtAuthenticationToken(jwt, perms == null ? List.of()
                    : perms.stream().map(p -> new SimpleGrantedAuthority("PERM_" + p)).toList(), jwt.getSubject());
        };
    }

    @Bean
    JwtDecoder decodeur(KeyService cles, AuthProperties props) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey(cles.publique()).build();
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(props.issuer())));
        return d;
    }

    @Bean
    PasswordEncoder motsDePasse() {
        return new BCryptPasswordEncoder(11);
    }

    @Bean
    UrlBasedCorsConfigurationSource cors(AuthProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(props.redirectOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
        s.registerCorsConfiguration("/api/**", c);
        return s;
    }
}
