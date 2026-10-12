package com.nexora.eservices.deploiement;

import com.nexora.eservices.audit.Journal;
import com.nexora.eservices.registre.Services;
import com.nexora.eservices.security.Habilitations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Projection locale des listes de codes du Référentiel Commun utilisées par les procédures déployées (dossier v4.2 §5.2) :
 * les tables REF_* des champs du paquet sont copiées au déploiement (libellés FR et EN), puis servies à eFORCE et
 * utilisées par la validation des demandes.
 */
@Service
public class ListesService {

    private static final Logger log = LoggerFactory.getLogger(ListesService.class);

    public record Liste(String table, int entries, OffsetDateTime loadedAt, String loadedBy) {}

    public record Valeur(String code, String labelFr, String labelEn, String parentCode) {}

    private final JdbcClient jdbc;
    private final Services services;
    private final Journal journal;

    public ListesService(JdbcClient jdbc, Services services, Journal journal) {
        this.jdbc = jdbc;
        this.services = services;
        this.journal = journal;
    }

    public static Set<String> tables(Paquet p) {
        Set<String> t = new TreeSet<>();
        p.fields().forEach(f -> { if (f.refdataTable() != null && f.refdataTable().matches("^REF_[A-Z0-9_]+$")) t.add(f.refdataTable()); });
        return t;
    }

    /** Charge (ou recharge) les listes d'un paquet ; renvoie les tables en échec (le déploiement n'est pas bloqué). */
    public Map<String, String> charger(Paquet p) {
        Map<String, String> echecs = new LinkedHashMap<>();
        for (String table : tables(p)) {
            try {
                List<Services.Code> fr = services.listeDeCodes(table, "fr");
                Map<String, String> en = new HashMap<>();
                services.listeDeCodes(table, "en").forEach(c -> en.put(c.code(), c.label()));
                jdbc.sql("delete from wfe.code_list where refdata_table = :t").param("t", table).update();
                jdbc.sql("insert into wfe.code_list (refdata_table, entries, loaded_by) values (:t, :n, :u)").param("t", table).param("n", fr.size())
                        .param("u", Habilitations.acteur()).update();
                for (Services.Code c : fr)
                    jdbc.sql("insert into wfe.code_list_value values (:t, :c, :f, :e, :p) on conflict do nothing").param("t", table).param("c", c.code())
                            .param("f", tronquer(c.label())).param("e", tronquer(en.get(c.code()))).param("p", c.parentCode()).update();
            } catch (RuntimeException e) {
                log.warn("Liste {} non chargée : {}", table, e.getMessage());
                echecs.put(table, Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
            }
        }
        journal.tracer("LISTES_CHARGEES", null, null, Map.of("procedure", p.procedure().code(), "tables", tables(p), "failures", echecs));
        return echecs;
    }

    public List<Liste> listes() {
        return jdbc.sql("select refdata_table, entries, loaded_at, loaded_by from wfe.code_list order by refdata_table")
                .query((rs, i) -> new Liste(rs.getString(1), rs.getInt(2), rs.getObject(3, OffsetDateTime.class), rs.getString(4))).list();
    }

    public List<Valeur> valeurs(String table, String q, int limite) {
        String motif = q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
        return jdbc.sql("""
                select code, label_fr, label_en, parent_code from wfe.code_list_value
                 where refdata_table = :t and (cast(:q as text) is null or lower(code) like :q or lower(label_fr) like :q or lower(coalesce(label_en, '')) like :q)
                 order by case when lower(code) = lower(coalesce(:exact, '')) then 0 else 1 end, label_fr limit :l
                """).param("t", table).param("q", motif).param("exact", q == null ? null : q.strip()).param("l", Math.min(Math.max(limite, 1), 5000))
                .query((rs, i) -> new Valeur(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();
    }

    /** Codes connus d'une table (vide si la liste n'a pas été chargée : la validation n'est alors pas bloquante). */
    public Optional<Set<String>> codes(String table) {
        Long n = jdbc.sql("select count(*) from wfe.code_list where refdata_table = :t").param("t", table).query(Long.class).single();
        if (n == 0) return Optional.empty();
        return Optional.of(new HashSet<>(jdbc.sql("select code from wfe.code_list_value where refdata_table = :t").param("t", table).query(String.class).list()));
    }

    private static String tronquer(String s) {
        return s == null ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }
}
