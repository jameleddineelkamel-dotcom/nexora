package com.nexora.auth.security;

import com.nexora.auth.account.Compte;
import com.nexora.auth.config.AuthProperties;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Jetons d'accès de la plateforme (JWT signé RS256). Ils portent l'identité, les rôles, les groupes et les
 * permissions effectives : chaque microservice les vérifie avec la clé publique (JWKS), sans appeler auth.
 */
@Service
public class TokenService {

    private final JwtEncoder encodeur;
    private final AuthProperties props;
    private final String kid;

    public TokenService(KeyService cles, AuthProperties props) {
        this.encodeur = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(cles.cle())));
        this.props = props;
        this.kid = cles.cle().getKeyID();
    }

    public record Jeton(String valeur, long expiresIn) {}

    public Jeton emettre(Compte c) {
        Instant maintenant = Instant.now();
        Instant expiration = maintenant.plus(props.tokenValidity());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(c.username())
                .issuedAt(maintenant)
                .expiresAt(expiration)
                .id(java.util.UUID.randomUUID().toString())
                .claim("uid", c.id())
                .claim("name", c.fullName())
                .claim("preferred_username", c.username())
                .claim("email", c.email() == null ? "" : c.email())
                .claim("locale", c.language())
                .claim("roles", c.roles())
                .claim("groups", c.groups())
                .claim("permissions", c.permissions())
                .build();
        JwsHeader entete = JwsHeader.with(SignatureAlgorithm.RS256).keyId(kid).build();
        String valeur = encodeur.encode(JwtEncoderParameters.from(entete, claims)).getTokenValue();
        return new Jeton(valeur, props.tokenValidity().toSeconds());
    }
}
