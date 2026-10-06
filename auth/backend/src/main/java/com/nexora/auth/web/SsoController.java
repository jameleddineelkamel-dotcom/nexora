package com.nexora.auth.web;

import com.nexora.auth.account.AccountService;
import com.nexora.auth.account.Compte;
import com.nexora.auth.account.Journal;
import com.nexora.auth.config.AuthProperties;
import com.nexora.auth.security.KeyService;
import com.nexora.auth.security.TokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authentification unique (SSO) de la plateforme NEXORA.
 * <ul>
 *   <li>Une application non authentifiée redirige vers {@code /sso/authorize?redirect_uri=…} ;</li>
 *   <li>si la session SSO (cookie) est valide, le jeton est renvoyé aussitôt dans le fragment
 *       {@code #access_token=…} de l'adresse de retour ; sinon l'utilisateur passe par la page de connexion ;</li>
 *   <li>une seule connexion vaut pour toutes les applications de la plateforme.</li>
 * </ul>
 */
@RestController
public class SsoController {

    public record Identifiants(String username, String password) {}

    public record ChangementMotDePasse(String current, String next) {}

    private final AccountService comptes;
    private final TokenService jetons;
    private final KeyService cles;
    private final Journal journal;
    private final AuthProperties props;

    public SsoController(AccountService comptes, TokenService jetons, KeyService cles, Journal journal, AuthProperties props) {
        this.comptes = comptes;
        this.jetons = jetons;
        this.cles = cles;
        this.journal = journal;
        this.props = props;
    }

    // ---------- API de connexion (page de connexion du service auth) ----------
    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<Map<String, Object>> connexion(@RequestBody Identifiants id, HttpServletRequest req) {
        Compte c = comptes.authentifier(id.username(), id.password(), ip(req));
        String session = comptes.ouvrirSession(c, ip(req), req.getHeader(HttpHeaders.USER_AGENT));
        TokenService.Jeton j = jetons.emettre(c);
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(session, props.sessionValidity().toSeconds()).toString())
                .body(reponse(c, j));
    }

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Void> deconnexion(HttpServletRequest req) {
        fermer(req);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
    }

    @GetMapping("/api/v1/auth/me")
    public Map<String, Object> moi(@AuthenticationPrincipal Jwt jwt) {
        Compte c = comptes.parUsername(jwt.getSubject()).orElseThrow(() -> AuthException.introuvable("Compte introuvable."));
        return utilisateur(c);
    }

    @PostMapping("/api/v1/auth/password")
    public ResponseEntity<Void> motDePasse(@AuthenticationPrincipal Jwt jwt, @RequestBody ChangementMotDePasse m, HttpServletRequest req) {
        comptes.changerMotDePasse(jwt.getSubject(), m.current(), m.next(), ip(req));
        return ResponseEntity.noContent().build();
    }

    /** Session SSO courante (page de connexion : « déjà connecté en tant que … »). */
    @GetMapping("/api/v1/auth/session")
    public ResponseEntity<Map<String, Object>> sessionCourante(HttpServletRequest req) {
        return comptes.session(lireCookie(req))
                .map(c -> ResponseEntity.ok(reponse(c, jetons.emettre(c))))
                .orElse(ResponseEntity.noContent().build());
    }

    /** Applications de la plateforme (portail) et règles de mot de passe, pour la page de connexion. */
    @GetMapping("/api/v1/auth/config")
    public Map<String, Object> config() {
        return Map.of("applications", props.portail(), "passwordMinLength", 10, "maxAttempts", AccountService.ESSAIS_MAX);
    }

    // ---------- Redirections SSO ----------
    @GetMapping("/sso/authorize")
    public ResponseEntity<Void> autoriser(@RequestParam("redirect_uri") String retour, @RequestParam(required = false) String state,
                                          HttpServletRequest req) {
        if (!props.retourAutorise(retour)) throw AuthException.invalide("Adresse de retour non autorisée : " + retour);
        var compte = comptes.session(lireCookie(req));
        if (compte.isEmpty()) {
            return redirection("/login?redirect_uri=" + enc(retour) + (state == null ? "" : "&state=" + enc(state)));
        }
        TokenService.Jeton j = jetons.emettre(compte.get());
        String fragment = "access_token=" + enc(j.valeur()) + "&token_type=Bearer&expires_in=" + j.expiresIn()
                + (state == null ? "" : "&state=" + enc(state));
        String base = retour.contains("#") ? retour.substring(0, retour.indexOf('#')) : retour;
        return redirection(base + "#" + fragment);
    }

    @GetMapping("/sso/logout")
    public ResponseEntity<Void> sortir(@RequestParam(value = "redirect_uri", required = false) String retour, HttpServletRequest req) {
        fermer(req);
        String cible = "/login" + (retour != null && props.retourAutorise(retour) ? "?redirect_uri=" + enc(retour) : "");
        return ResponseEntity.status(302).location(URI.create(cible)).header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
    }

    // ---------- Clés publiques et découverte ----------
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return cles.jwksPublic().toJSONObject();
    }

    @GetMapping("/.well-known/openid-configuration")
    public Map<String, Object> decouverte() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("issuer", props.issuer());
        m.put("authorization_endpoint", props.issuer() + "/sso/authorize");
        m.put("end_session_endpoint", props.issuer() + "/sso/logout");
        m.put("jwks_uri", props.issuer() + "/.well-known/jwks.json");
        m.put("userinfo_endpoint", props.issuer() + "/api/v1/auth/me");
        m.put("response_types_supported", java.util.List.of("token"));
        m.put("id_token_signing_alg_values_supported", java.util.List.of("RS256"));
        return m;
    }

    // ---------- Utilitaires ----------
    private Map<String, Object> reponse(Compte c, TokenService.Jeton j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accessToken", j.valeur());
        m.put("tokenType", "Bearer");
        m.put("expiresIn", j.expiresIn());
        m.put("user", utilisateur(c));
        return m;
    }

    static Map<String, Object> utilisateur(Compte c) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("username", c.username());
        u.put("fullName", c.fullName());
        u.put("email", c.email());
        u.put("organisation", c.organisation());
        u.put("language", c.language());
        u.put("mustChangePassword", c.mustChangePassword());
        u.put("roles", c.roles());
        u.put("groups", c.groups());
        u.put("permissions", c.permissions());
        u.put("lastLoginAt", c.lastLoginAt());
        return u;
    }

    private void fermer(HttpServletRequest req) {
        String id = lireCookie(req);
        comptes.session(id).ifPresent(c -> journal.tracer("LOGOUT", c.username(), c.username(), ip(req), null));
        comptes.fermerSession(id);
    }

    private ResponseCookie cookie(String valeur, long secondes) {
        return ResponseCookie.from(AuthProperties.COOKIE, valeur).httpOnly(true).secure(props.secureCookie()).sameSite("Lax")
                .path("/").maxAge(secondes).build();
    }

    private static String lireCookie(HttpServletRequest req) {
        return req.getCookies() == null ? null : Arrays.stream(req.getCookies()).filter(c -> AuthProperties.COOKIE.equals(c.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }

    static String ip(HttpServletRequest req) {
        String f = req.getHeader("X-Forwarded-For");
        return f != null && !f.isBlank() ? f.split(",")[0].strip() : req.getRemoteAddr();
    }

    private static ResponseEntity<Void> redirection(String cible) {
        return ResponseEntity.status(302).location(URI.create(cible)).build();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
