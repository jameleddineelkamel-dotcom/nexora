package com.nexora.eservices.audit;

import com.nexora.eservices.security.Habilitations;
import com.nexora.eservices.web.Json;
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
import java.util.UUID;

/**
 * Journal d'audit chaîné des e-Services (wfe.audit_event, ajout seul). Transmet aussi à PostgreSQL l'auteur et le
 * motif de l'opération (set_config local à la transaction), lus par les déclencheurs d'historique des messages.
 */
@Component
public class Journal {

    public static final String ENTETE_CANAL = "X-Nexora-Channel", ENTETE_MOTIF = "X-Nexora-Reason";
    private static final Set<String> CANAUX = Set.of("UI", "API", "IMPORT", "SYNC", "ENGINE");

    public record Evenement(long id, OffsetDateTime occurredAt, String actor, String channel, String actionCode, UUID dossierId,
                            String dossierNo, String reason, Map<String, Object> details, String rowHash) {}

    private final JdbcClient jdbc;
    private final Json json;

    public Journal(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** À appeler au début de chaque transaction d'écriture. */
    public void contexte() {
        jdbc.sql("select set_config('nexora.user', :u, true), set_config('nexora.reason', :r, true)")
                .param("u", Habilitations.acteur()).param("r", motif() == null ? "" : motif()).query((rs, i) -> 1).single();
    }

    public void tracer(String action, UUID dossier, String dossierNo, Map<String, Object> details) {
        jdbc.sql("""
                insert into wfe.audit_event (actor, channel, action_code, dossier_id, dossier_no, reason, details)
                values (:a, :c, :x, :d, :n, :m, cast(:j as jsonb))
                """).param("a", Habilitations.acteur()).param("c", canal()).param("x", action).param("d", dossier).param("n", dossierNo)
                .param("m", motif()).param("j", json.ecrire(details)).update();
    }

    public List<Evenement> lire(UUID dossier, int limite) {
        return jdbc.sql("""
                select id, occurred_at, actor, channel, action_code, dossier_id, dossier_no, reason, details::text, row_hash
                  from wfe.audit_event where (cast(:d as uuid) is null or dossier_id = :d) order by id desc limit :l
                """).param("d", dossier).param("l", limite)
                .query((rs, i) -> new Evenement(rs.getLong(1), rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), json.map(rs.getString(9)), rs.getString(10)))
                .list();
    }

    private static String canal() {
        String c = entete(ENTETE_CANAL);
        if (requete() == null) return "ENGINE";
        return c != null && CANAUX.contains(c.toUpperCase()) ? c.toUpperCase() : "API";
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
