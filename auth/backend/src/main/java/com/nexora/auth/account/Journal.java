package com.nexora.auth.account;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Journal de sécurité : connexions, échecs, verrouillages, administration des comptes et des droits. */
@Service
public class Journal {

    public record Evenement(long id, OffsetDateTime occurredAt, String type, String username, String actor, String ip, String details) {}

    public record Page(List<Evenement> items, long total) {}

    private final JdbcClient jdbc;

    public Journal(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Enregistré dans sa propre transaction : un échec de connexion reste tracé même si l'appel échoue. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tracer(String type, String username, String acteur, String ip, String details) {
        jdbc.sql("insert into auth_event (type, username, actor, ip, details) values (:t, :u, :a, :ip, :d)")
                .param("t", type).param("u", username).param("a", acteur).param("ip", ip).param("d", details).update();
    }

    @Transactional(readOnly = true)
    public Page lire(String username, String type, int page, int taille) {
        StringBuilder w = new StringBuilder(" where true");
        Map<String, Object> p = new HashMap<>();
        if (username != null && !username.isBlank()) { w.append(" and (username ilike :u or actor ilike :u)"); p.put("u", "%" + username.strip() + "%"); }
        if (type != null && !type.isBlank()) { w.append(" and type = :t"); p.put("t", type); }
        long total = jdbc.sql("select count(*) from auth_event" + w).params(p).query(Long.class).single();
        p.put("lim", Math.clamp(taille, 1, 200));
        p.put("off", (long) Math.max(page, 0) * Math.clamp(taille, 1, 200));
        List<Evenement> items = jdbc.sql("select * from auth_event" + w + " order by occurred_at desc, id desc limit :lim offset :off").params(p)
                .query((rs, i) -> new Evenement(rs.getLong("id"), rs.getObject("occurred_at", OffsetDateTime.class), rs.getString("type"),
                        rs.getString("username"), rs.getString("actor"), rs.getString("ip"), rs.getString("details")))
                .list();
        return new Page(items, total);
    }
}
