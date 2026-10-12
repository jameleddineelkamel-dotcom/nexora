package com.nexora.eservices.cotation;

import com.nexora.eservices.audit.Journal;
import com.nexora.eservices.deploiement.Paquet;
import com.nexora.eservices.dossier.Dossier;
import com.nexora.eservices.web.EservicesException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Cotation automatique (UC-04.1) : le lieu de traitement choisi dans la demande désigne une unité administrative ; le
 * dossier est affecté à un seul agent de traitement actif de cette unité, de façon aléatoire et équitable (le moins
 * chargé, tirage au sort en cas d'égalité, RM01 à RM03), avec un signataire de la même unité ; chaque cotation est
 * historisée (RM04). Le paramétrage (lieux, unités, agents) est réservé aux administrateurs.
 */
@Service
public class CotationService {

    public record Affectation(String lieu, String unite, String agent, String signataire, int candidats) {}

    public record Lieu(String code, String labelFr, String labelEn, String unitCode, String unitLabel, int sortOrder, boolean active,
                       int agents, int signataires) {}

    public record Unite(String code, String label, String authority, boolean active, List<Membre> membres) {}

    public record Membre(String unitCode, String username, String role, String fullName, boolean active, long openCases) {}

    private final JdbcClient jdbc;
    private final Journal journal;
    private final SecureRandom hasard = new SecureRandom();

    public CotationService(JdbcClient jdbc, Journal journal) {
        this.jdbc = jdbc;
        this.journal = journal;
    }

    public Affectation coter(Dossier d, Paquet p) {
        String lieu = d.processingLocation() != null ? d.processingLocation()
                : Objects.toString(d.formData().get(p.reglage("processingLocationField", "processingLocation")), null);
        if (lieu == null) throw new IllegalStateException("Dossier " + d.dossierNo() + " sans lieu de traitement : cotation impossible.");
        String unite = jdbc.sql("""
                select l.unit_code from wfe.processing_location l join wfe.admin_unit u on u.unit_code = l.unit_code
                 where l.location_code = :l and l.is_active and u.is_active
                """).param("l", lieu).query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("Lieu de traitement inconnu ou inactif : " + lieu));
        List<Membre> agents = membres(unite, "TRAITEMENT");
        if (agents.isEmpty())
            throw new IllegalStateException("Aucun agent de traitement actif dans l'unité " + unite + " (lieu " + lieu + ") : complétez le paramétrage de la cotation.");
        Membre agent = moinsCharge(agents);
        List<Membre> signataires = membres(unite, "SIGNATAIRE");
        if (signataires.isEmpty()) throw new IllegalStateException("Aucun signataire actif dans l'unité " + unite + " : complétez le paramétrage de la cotation.");
        Membre signataire = moinsCharge(signataires);
        int sla = Integer.parseInt(p.reglage("slaHours", "48"));
        jdbc.sql("""
                update wfe.dossier set processing_location = :l, admin_unit = :u, assigned_agent = :a, signatory = :s,
                       sla_due_at = coalesce(sla_due_at, now() + make_interval(hours => :h)), updated_at = now() where dossier_id = :d
                """).param("l", lieu).param("u", unite).param("a", agent.username()).param("s", signataire.username()).param("h", sla)
                .param("d", d.id()).update();
        jdbc.sql("insert into wfe.cotation_history (dossier_id, location_code, unit_code, agent, signatory, candidates, strategy) values (:d, :l, :u, :a, :s, :c, :st)")
                .param("d", d.id()).param("l", lieu).param("u", unite).param("a", agent.username()).param("s", signataire.username())
                .param("c", agents.size()).param("st", p.reglage("cotation", "ALEATOIRE_EQUITABLE")).update();
        journal.tracer("DOSSIER_COTE", d.id(), d.dossierNo(), Map.of("location", lieu, "unit", unite, "agent", agent.username(),
                "signatory", signataire.username(), "candidates", agents.size()));
        return new Affectation(lieu, unite, agent.username(), signataire.username(), agents.size());
    }

    /** Agent le moins chargé (dossiers actifs cotés), tirage au sort parmi les ex aequo. */
    private Membre moinsCharge(List<Membre> l) {
        long min = l.stream().mapToLong(Membre::openCases).min().orElse(0);
        List<Membre> ex = l.stream().filter(m -> m.openCases() == min).toList();
        return ex.get(hasard.nextInt(ex.size()));
    }

    private List<Membre> membres(String unite, String role) {
        return jdbc.sql("""
                select m.unit_code, m.username, m.role, m.full_name, m.is_active,
                       (select count(*) from wfe.dossier d where d.lifecycle = 'ACTIVE' and (case when m.role = 'TRAITEMENT' then d.assigned_agent else d.signatory end) = m.username)
                  from wfe.unit_member m where m.unit_code = :u and m.role = :r and m.is_active order by m.username
                """).param("u", unite).param("r", role).query((rs, i) -> new Membre(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getBoolean(5), rs.getLong(6))).list();
    }

    public List<Map<String, Object>> historique(UUID dossier) {
        return jdbc.sql("select location_code, unit_code, agent, signatory, candidates, strategy, occurred_at from wfe.cotation_history where dossier_id = :d order by cotation_id")
                .param("d", dossier).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("location", rs.getString(1));
                    m.put("unit", rs.getString(2));
                    m.put("agent", rs.getString(3));
                    m.put("signatory", rs.getString(4));
                    m.put("candidates", rs.getInt(5));
                    m.put("strategy", rs.getString(6));
                    m.put("occurredAt", rs.getObject(7, OffsetDateTime.class));
                    return m;
                }).list();
    }

    // ------------------------------------------------------------------ paramétrage
    public List<Lieu> lieux(boolean actifsSeulement) {
        return jdbc.sql("""
                select l.location_code, l.label_fr, l.label_en, l.unit_code, u.label, l.sort_order, l.is_active,
                       (select count(*) from wfe.unit_member m where m.unit_code = l.unit_code and m.role = 'TRAITEMENT' and m.is_active),
                       (select count(*) from wfe.unit_member m where m.unit_code = l.unit_code and m.role = 'SIGNATAIRE' and m.is_active)
                  from wfe.processing_location l join wfe.admin_unit u on u.unit_code = l.unit_code
                 where (not :a or (l.is_active and u.is_active)) order by l.sort_order, l.label_fr
                """).param("a", actifsSeulement).query((rs, i) -> new Lieu(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6), rs.getBoolean(7), rs.getInt(8), rs.getInt(9))).list();
    }

    public List<Unite> unites() {
        Map<String, List<Membre>> membres = new HashMap<>();
        jdbc.sql("""
                select m.unit_code, m.username, m.role, m.full_name, m.is_active,
                       (select count(*) from wfe.dossier d where d.lifecycle = 'ACTIVE' and (case when m.role = 'TRAITEMENT' then d.assigned_agent else d.signatory end) = m.username)
                  from wfe.unit_member m order by m.unit_code, m.role desc, m.username
                """).query((rs, i) -> membres.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Membre(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getLong(6)))).list();
        return jdbc.sql("select unit_code, label, authority, is_active from wfe.admin_unit order by unit_code")
                .query((rs, i) -> new Unite(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), membres.getOrDefault(rs.getString(1), List.of())))
                .list();
    }

    @Transactional
    public List<Lieu> enregistrerLieu(Lieu l) {
        if (l.code() == null || !l.code().matches("^[A-Z][A-Z0-9_]{0,19}$")) throw EservicesException.invalide("Code de lieu invalide (majuscules, chiffres, _).");
        journal.contexte();
        jdbc.sql("""
                insert into wfe.processing_location (location_code, label_fr, label_en, unit_code, sort_order, is_active) values (:c, :f, :e, :u, :o, :a)
                on conflict (location_code) do update set label_fr = excluded.label_fr, label_en = excluded.label_en, unit_code = excluded.unit_code,
                  sort_order = excluded.sort_order, is_active = excluded.is_active
                """).param("c", l.code()).param("f", l.labelFr()).param("e", l.labelEn()).param("u", l.unitCode()).param("o", l.sortOrder())
                .param("a", l.active()).update();
        journal.tracer("LIEU_TRAITEMENT_ENREGISTRE", null, null, Map.of("code", l.code(), "unit", l.unitCode(), "active", l.active()));
        return lieux(false);
    }

    @Transactional
    public List<Unite> enregistrerUnite(Unite u) {
        if (u.code() == null || !u.code().matches("^[A-Z][A-Z0-9_]{0,19}$")) throw EservicesException.invalide("Code d'unité invalide (majuscules, chiffres, _).");
        journal.contexte();
        jdbc.sql("""
                insert into wfe.admin_unit (unit_code, label, authority, is_active) values (:c, :l, :a, :ac)
                on conflict (unit_code) do update set label = excluded.label, authority = excluded.authority, is_active = excluded.is_active
                """).param("c", u.code()).param("l", u.label()).param("a", u.authority() == null ? "MINCOMMERCE" : u.authority()).param("ac", u.active()).update();
        journal.tracer("UNITE_ENREGISTREE", null, null, Map.of("code", u.code(), "active", u.active()));
        return unites();
    }

    @Transactional
    public List<Unite> enregistrerMembre(Membre m) {
        if (m.username() == null || !m.username().matches("^[a-z0-9][a-z0-9._-]{1,59}$")) throw EservicesException.invalide("Identifiant d'utilisateur invalide.");
        if (!List.of("TRAITEMENT", "SIGNATAIRE").contains(m.role())) throw EservicesException.invalide("Rôle attendu : TRAITEMENT ou SIGNATAIRE.");
        journal.contexte();
        jdbc.sql("""
                insert into wfe.unit_member (unit_code, username, role, full_name, is_active) values (:u, :n, :r, :f, :a)
                on conflict (unit_code, username, role) do update set full_name = excluded.full_name, is_active = excluded.is_active
                """).param("u", m.unitCode()).param("n", m.username()).param("r", m.role()).param("f", m.fullName()).param("a", m.active()).update();
        journal.tracer("AGENT_UNITE_ENREGISTRE", null, null, Map.of("unit", m.unitCode(), "username", m.username(), "role", m.role(), "active", m.active()));
        return unites();
    }
}
