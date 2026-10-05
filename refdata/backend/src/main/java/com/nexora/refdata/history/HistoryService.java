package com.nexora.refdata.history;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.nexora.refdata.entry.Entry.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Consultation de l'historique (alimenté par le trigger ref_journaliser) et du journal des chargements. */
@Service
public class HistoryService {

    public record Filtre(String table, String type, String code, String operation, String auteur, String canal,
                         LocalDate du, LocalDate au, String lot) {}

    public record Evenement(long id, String entityType, long entityId, String tableCode, String entityCode, String operation,
                            @JsonRawValue String changes, @JsonRawValue String snapshot, String author, String channel,
                            String reason, String correlationId, OffsetDateTime occurredAt) {}

    public record Activite(LocalDate day, long total, long creations, long modifications, long invalidations) {}

    public record Chargement(String id, String tableCode, String fileName, String format, String mode, int total, int created,
                             int updated, int unchanged, int invalidated, int rejected, @JsonRawValue String errors,
                             String author, String channel, String reason, OffsetDateTime occurredAt) {}

    private final JdbcClient jdbc;

    public HistoryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page<Evenement> evenements(Filtre f, int page, int taille) {
        StringBuilder w = new StringBuilder(" where true");
        Map<String, Object> p = new HashMap<>();
        ajouter(w, p, "table_code", "t", f.table());
        ajouter(w, p, "entity_type", "ty", f.type());
        ajouter(w, p, "entity_code", "c", f.code());
        ajouter(w, p, "operation", "o", f.operation());
        // « !SEED » : tous les canaux sauf le chargement initial
        if (f.canal() != null && f.canal().startsWith("!")) { w.append(" and channel <> :ca"); p.put("ca", f.canal().substring(1)); }
        else ajouter(w, p, "channel", "ca", f.canal());
        ajouter(w, p, "correlation_id", "l", f.lot());
        if (f.auteur() != null && !f.auteur().isBlank()) { w.append(" and author ilike :a"); p.put("a", "%" + f.auteur().strip() + "%"); }
        if (f.du() != null) { w.append(" and occurred_at >= :du"); p.put("du", Timestamp.valueOf(f.du().atStartOfDay())); }
        if (f.au() != null) { w.append(" and occurred_at < :au"); p.put("au", Timestamp.valueOf(f.au().plusDays(1).atStartOfDay())); }
        long total = jdbc.sql("select count(*) from ref_history" + w).params(p).query(Long.class).single();
        p.put("lim", Math.clamp(taille, 1, 500));
        p.put("off", (long) Math.max(page, 0) * Math.clamp(taille, 1, 500));
        List<Evenement> items = jdbc.sql("select * from ref_history" + w + " order by occurred_at desc, id desc limit :lim offset :off")
                .params(p)
                .query((rs, i) -> new Evenement(rs.getLong("id"), rs.getString("entity_type"), rs.getLong("entity_id"),
                        rs.getString("table_code"), rs.getString("entity_code"), rs.getString("operation"),
                        rs.getString("changes"), rs.getString("snapshot"), rs.getString("author"), rs.getString("channel"),
                        rs.getString("reason"), rs.getString("correlation_id"), rs.getObject("occurred_at", OffsetDateTime.class)))
                .list();
        return new Page<>(items, total, page, taille);
    }

    /** Activité quotidienne sur une période (tableau de bord). */
    @Transactional(readOnly = true)
    public List<Activite> activite(int jours) {
        LocalDate debut = LocalDate.now(ZoneId.systemDefault()).minusDays(Math.clamp(jours, 1, 366) - 1L);
        return jdbc.sql("""
                select d::date as jour, count(h.id) as total,
                       count(h.id) filter (where h.operation = 'CREATION') as creations,
                       count(h.id) filter (where h.operation = 'MODIFICATION') as modifications,
                       count(h.id) filter (where h.operation = 'INVALIDATION') as invalidations
                  from generate_series(cast(:debut as date), current_date, interval '1 day') d
                  left join ref_history h on h.occurred_at >= d and h.occurred_at < d + interval '1 day' and h.channel <> 'SEED'
                 group by d order by d
                """)
                .param("debut", java.sql.Date.valueOf(debut))
                .query((rs, i) -> new Activite(rs.getDate("jour").toLocalDate(), rs.getLong("total"), rs.getLong("creations"),
                        rs.getLong("modifications"), rs.getLong("invalidations")))
                .list();
    }

    @Transactional(readOnly = true)
    public List<Chargement> chargements(String table, int limite) {
        String w = table == null ? "" : " where table_code = :t";
        var spec = jdbc.sql("select * from ref_import" + w + " order by occurred_at desc limit :lim").param("lim", Math.clamp(limite, 1, 500));
        if (table != null) spec = spec.param("t", table);
        return spec.query((rs, i) -> new Chargement(rs.getString("id"), rs.getString("table_code"), rs.getString("file_name"),
                        rs.getString("format"), rs.getString("mode"), rs.getInt("total"), rs.getInt("created"), rs.getInt("updated"),
                        rs.getInt("unchanged"), rs.getInt("invalidated"), rs.getInt("rejected"), rs.getString("errors"),
                        rs.getString("author"), rs.getString("channel"), rs.getString("reason"),
                        rs.getObject("occurred_at", OffsetDateTime.class)))
                .list();
    }

    private static void ajouter(StringBuilder w, Map<String, Object> p, String colonne, String nom, String valeur) {
        if (valeur == null || valeur.isBlank()) return;
        w.append(" and ").append(colonne).append(" = :").append(nom);
        p.put(nom, valeur.strip());
    }
}
