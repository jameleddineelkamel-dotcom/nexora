package com.nexora.auth.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

/**
 * Clé RSA de signature des jetons : générée au premier démarrage puis conservée en base, pour que les
 * jetons restent valides après un redémarrage. La clé publique est diffusée en JWKS aux autres microservices.
 */
@Service
public class KeyService {

    private final RSAKey cle;

    public KeyService(JdbcClient jdbc) {
        this.cle = jdbc.sql("select kid, private_pem, public_pem from auth_key where active order by created_at desc limit 1")
                .query((rs, i) -> lire(rs.getString(1), rs.getString(2), rs.getString(3)))
                .optional()
                .orElseGet(() -> generer(jdbc));
    }

    public RSAKey cle() {
        return cle;
    }

    public RSAPublicKey publique() {
        try {
            return cle.toRSAPublicKey();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Partie publique seulement (diffusée sur /.well-known/jwks.json). */
    public JWKSet jwksPublic() {
        return new JWKSet(cle.toPublicJWK());
    }

    private static RSAKey generer(JdbcClient jdbc) {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            String kid = "nexora-" + UUID.randomUUID().toString().substring(0, 8);
            Base64.Encoder b64 = Base64.getEncoder();
            jdbc.sql("insert into auth_key (kid, private_pem, public_pem) values (:k, :priv, :pub)")
                    .param("k", kid).param("priv", b64.encodeToString(kp.getPrivate().getEncoded()))
                    .param("pub", b64.encodeToString(kp.getPublic().getEncoded())).update();
            return construire(kid, (RSAPublicKey) kp.getPublic(), (RSAPrivateKey) kp.getPrivate());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey lire(String kid, String prive, String publique) {
        try {
            KeyFactory f = KeyFactory.getInstance("RSA");
            Base64.Decoder b64 = Base64.getDecoder();
            return construire(kid, (RSAPublicKey) f.generatePublic(new X509EncodedKeySpec(b64.decode(publique))),
                    (RSAPrivateKey) f.generatePrivate(new PKCS8EncodedKeySpec(b64.decode(prive))));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Clé de signature illisible", e);
        }
    }

    private static RSAKey construire(String kid, RSAPublicKey pub, RSAPrivateKey priv) {
        return new RSAKey.Builder(pub).privateKey(priv).keyID(kid).keyUse(KeyUse.SIGNATURE)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256).build();
    }
}
