package com.nexora.refdata.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Métadonnées de l'historique : transmet à PostgreSQL l'auteur, le canal, le motif et le lot de
 * l'opération en cours (set_config local à la transaction), lus par le trigger {@code ref_journaliser}.
 * En attendant le microservice d'identité (Party Management / IAM), l'auteur est fourni par l'en-tête
 * {@code X-Nexora-User}.
 */
@Component
public class AuditContext {

    public static final String ENTETE_UTILISATEUR = "X-Nexora-User";
    public static final String ENTETE_CANAL = "X-Nexora-Channel";
    public static final String ENTETE_MOTIF = "X-Nexora-Reason";
    private static final Set<String> CANAUX = Set.of("UI", "API", "IMPORT", "SEED", "SYNC");

    private final JdbcClient jdbc;

    public AuditContext(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** À appeler au début de chaque transaction d'écriture. */
    public void appliquer(String canalForce, String motifForce, String lot) {
        String canal = canalForce != null ? canalForce : canal();
        String motif = motifForce != null ? motifForce : entete(ENTETE_MOTIF);
        jdbc.sql("""
                select set_config('nexora.user', :u, true), set_config('nexora.channel', :c, true),
                       set_config('nexora.reason', :r, true), set_config('nexora.correlation', :l, true),
                       set_config('nexora.history', 'on', true)
                """)
                .param("u", utilisateur()).param("c", canal).param("r", motif == null ? "" : motif)
                .param("l", lot == null ? "" : lot)
                .query((rs, i) -> 1).single();
    }

    public void appliquer() {
        appliquer(null, null, null);
    }

    public void historique(boolean actif) {
        jdbc.sql("select set_config('nexora.history', :v, true)").param("v", actif ? "on" : "off").query((rs, i) -> 1).single();
    }

    public String utilisateur() {
        String u = entete(ENTETE_UTILISATEUR);
        return u == null || u.isBlank() ? (requete() == null ? "systeme" : "anonyme") : u.strip();
    }

    public String canal() {
        String c = entete(ENTETE_CANAL);
        return c != null && CANAUX.contains(c.toUpperCase()) ? c.toUpperCase() : "API";
    }

    public String motif() {
        return entete(ENTETE_MOTIF);
    }

    private static String entete(String nom) {
        HttpServletRequest r = requete();
        String v = r == null ? null : r.getHeader(nom);
        if (v == null || v.isBlank()) return null;
        try {
            return URLDecoder.decode(v, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return v;
        }
    }

    private static HttpServletRequest requete() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
    }
}
