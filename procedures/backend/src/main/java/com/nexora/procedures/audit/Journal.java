package com.nexora.procedures.audit;

import com.nexora.procedures.security.Habilitations;
import com.nexora.procedures.web.Json;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Journal d'audit chaîné du studio (table studio.audit_event, ajout seul, empreintes prev_hash / row_hash calculées
 * par trigger). L'auteur vient du jeton SSO ; le canal et le motif des en-têtes X-Nexora-Channel et X-Nexora-Reason.
 */
@Component
public class Journal {

    public static final String ENTETE_CANAL = "X-Nexora-Channel", ENTETE_MOTIF = "X-Nexora-Reason";
    private static final Set<String> CANAUX = Set.of("UI", "API", "IMPORT", "SEED", "AGENT");

    public record Evenement(long id, OffsetDateTime occurredAt, String actor, String channel, String actionCode,
                            String procedureCode, Long versionId, Long aiRunId, String policyDecision, String reason,
                            Map<String, Object> details, String rowHash) {}

    private final JdbcClient jdbc;
    private final Json json;

    public Journal(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void tracer(String action, String procedure, Long version, Map<String, Object> details) {
        tracer(action, procedure, version, null, null, details, null);
    }

    public void tracer(String action, String procedure, Long version, Long run, String decision, Map<String, Object> details, String canalForce) {
        jdbc.sql("""
                insert into studio.audit_event (actor, channel, action_code, procedure_code, version_id, ai_run_id, policy_decision, reason, details)
                values (:a, :c, :x, :p, :v, :r, cast(:d as studio.policy_decision), :m, cast(:j as jsonb))
                """)
                .param("a", utilisateur()).param("c", canalForce != null ? canalForce : canal()).param("x", action)
                .param("p", procedure).param("v", version).param("r", run).param("d", decision).param("m", motif())
                .param("j", json.ecrire(details)).update();
    }

    public List<Evenement> lire(Long version, int limite) {
        return jdbc.sql("""
                select id, occurred_at, actor, channel, action_code, procedure_code, version_id, ai_run_id, policy_decision::text,
                       reason, details::text, row_hash
                  from studio.audit_event where (cast(:v as bigint) is null or version_id = :v) order by id desc limit :l
                """).param("v", version).param("l", limite)
                .query((rs, i) -> new Evenement(rs.getLong(1), rs.getObject(2, OffsetDateTime.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), (Long) rs.getObject(7), (Long) rs.getObject(8),
                        rs.getString(9), rs.getString(10), json.map(rs.getString(11)), rs.getString(12)))
                .list();
    }

    /** Identité issue du jeton SSO ; « systeme » hors requête (amorçage, tâches internes). */
    public static String utilisateur() {
        return Habilitations.utilisateur().orElse(requete() == null ? "systeme" : "anonyme");
    }

    private static String canal() {
        String c = entete(ENTETE_CANAL);
        return c != null && CANAUX.contains(c.toUpperCase()) ? c.toUpperCase() : (requete() == null ? "SEED" : "API");
    }

    public static String motif() {
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
