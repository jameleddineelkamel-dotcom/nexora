package com.nexora.auth.account;

import com.nexora.auth.config.AuthProperties;
import com.nexora.auth.web.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

/** Comptes : authentification, verrouillage, sessions SSO, droits effectifs, mots de passe. */
@Service
public class AccountService {

    public static final int ESSAIS_MAX = 5;
    public static final int VERROUILLAGE_MINUTES = 15;
    private static final SecureRandom HASARD = new SecureRandom();

    private final JdbcClient jdbc;
    private final PasswordEncoder encodeur;
    private final Journal journal;
    private final AuthProperties props;

    public AccountService(JdbcClient jdbc, PasswordEncoder encodeur, Journal journal, AuthProperties props) {
        this.jdbc = jdbc;
        this.encodeur = encodeur;
        this.journal = journal;
        this.props = props;
    }

    // ---------- Lecture ----------
    @Transactional(readOnly = true)
    public Optional<Compte> parUsername(String username) {
        return jdbc.sql("select * from auth_user where username = :u").param("u", username.toLowerCase(Locale.ROOT).strip())
                .query(this::compte).optional();
    }

    @Transactional(readOnly = true)
    public Optional<Compte> parId(long id) {
        return jdbc.sql("select * from auth_user where id = :i").param("i", id).query(this::compte).optional();
    }

    @Transactional(readOnly = true)
    public List<Compte> tous() {
        return jdbc.sql("select * from auth_user order by username").query(this::compte).list();
    }

    // ---------- Authentification ----------
    /** Vérifie identifiant et mot de passe ; 5 échecs consécutifs verrouillent le compte 15 minutes. */
    @Transactional(noRollbackFor = AuthException.class)
    public Compte authentifier(String username, String motDePasse, String ip) {
        String u = username == null ? "" : username.toLowerCase(Locale.ROOT).strip();
        Optional<Map<String, Object>> ligne = jdbc.sql("select id, password_hash, active, locked_until, failed_attempts from auth_user where username = :u")
                .param("u", u).query().listOfRows().stream().findFirst();
        if (ligne.isEmpty()) {
            journal.tracer("LOGIN_FAILURE", u, null, ip, "Identifiant inconnu");
            throw AuthException.refuse("Identifiant ou mot de passe incorrect.");
        }
        Map<String, Object> l = ligne.get();
        long id = ((Number) l.get("id")).longValue();
        OffsetDateTime verrou = l.get("locked_until") == null ? null : ((java.sql.Timestamp) l.get("locked_until")).toInstant().atOffset(java.time.ZoneOffset.UTC);
        if (verrou != null && verrou.isAfter(OffsetDateTime.now())) {
            journal.tracer("LOGIN_FAILURE", u, null, ip, "Compte verrouillé");
            throw new AuthException(HttpStatus.LOCKED, List.of("Compte verrouillé après plusieurs échecs : réessayez dans quelques minutes ou contactez un administrateur."));
        }
        if (!Boolean.TRUE.equals(l.get("active"))) {
            journal.tracer("LOGIN_FAILURE", u, null, ip, "Compte désactivé");
            throw AuthException.refuse("Ce compte est désactivé.");
        }
        if (!encodeur.matches(motDePasse == null ? "" : motDePasse, (String) l.get("password_hash"))) {
            int essais = ((Number) l.get("failed_attempts")).intValue() + 1;
            boolean bloque = essais >= ESSAIS_MAX;
            jdbc.sql("update auth_user set failed_attempts = :n, locked_until = case when :b then now() + make_interval(mins => :m) else locked_until end where id = :i")
                    .param("n", bloque ? 0 : essais).param("b", bloque).param("m", VERROUILLAGE_MINUTES).param("i", id).update();
            journal.tracer(bloque ? "ACCOUNT_LOCKED" : "LOGIN_FAILURE", u, null, ip,
                    bloque ? "Verrouillé " + VERROUILLAGE_MINUTES + " min après " + ESSAIS_MAX + " échecs" : "Mot de passe incorrect (" + essais + "/" + ESSAIS_MAX + ")");
            throw AuthException.refuse("Identifiant ou mot de passe incorrect.");
        }
        jdbc.sql("update auth_user set failed_attempts = 0, locked_until = null, last_login_at = now() where id = :i").param("i", id).update();
        journal.tracer("LOGIN_SUCCESS", u, null, ip, null);
        return parId(id).orElseThrow();
    }

    // ---------- Sessions SSO ----------
    @Transactional
    public String ouvrirSession(Compte c, String ip, String agent) {
        byte[] b = new byte[32];
        HASARD.nextBytes(b);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        jdbc.sql("insert into auth_session (id, user_id, expires_at, ip, user_agent) values (:id, :u, now() + make_interval(secs => :s), :ip, :ua)")
                .param("id", id).param("u", c.id()).param("s", props.sessionValidity().toSeconds()).param("ip", ip)
                .param("ua", agent == null ? null : agent.substring(0, Math.min(300, agent.length()))).update();
        jdbc.sql("delete from auth_session where expires_at < now()").update();
        return id;
    }

    /** Compte de la session SSO si elle est valide et le compte actif. */
    @Transactional(readOnly = true)
    public Optional<Compte> session(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return jdbc.sql("select u.* from auth_session s join auth_user u on u.id = s.user_id where s.id = :id and s.expires_at > now() and u.active")
                .param("id", id).query(this::compte).optional();
    }

    @Transactional
    public void fermerSession(String id) {
        if (id != null) jdbc.sql("delete from auth_session where id = :id").param("id", id).update();
    }

    @Transactional
    public void fermerSessions(long userId) {
        jdbc.sql("delete from auth_session where user_id = :u").param("u", userId).update();
    }

    // ---------- Mots de passe ----------
    /** Politique : 10 caractères au moins, avec des lettres et des chiffres. */
    public static List<String> controlerMotDePasse(String mdp) {
        List<String> e = new ArrayList<>();
        if (mdp == null || mdp.length() < 10) e.add("Le mot de passe doit compter au moins 10 caractères.");
        if (mdp != null && (!mdp.matches(".*[A-Za-z].*") || !mdp.matches(".*\\d.*"))) e.add("Le mot de passe doit contenir des lettres et des chiffres.");
        return e;
    }

    @Transactional
    public void changerMotDePasse(String username, String actuel, String nouveau, String ip) {
        Compte c = parUsername(username).orElseThrow(() -> AuthException.introuvable("Compte introuvable."));
        String hash = jdbc.sql("select password_hash from auth_user where id = :i").param("i", c.id()).query(String.class).single();
        if (!encodeur.matches(actuel == null ? "" : actuel, hash)) throw AuthException.invalide("Le mot de passe actuel est incorrect.");
        List<String> e = controlerMotDePasse(nouveau);
        if (encodeur.matches(nouveau == null ? "" : nouveau, hash)) e.add("Le nouveau mot de passe doit être différent de l'actuel.");
        if (!e.isEmpty()) throw AuthException.invalide(e);
        definirMotDePasse(c.id(), nouveau, false);
        journal.tracer("PASSWORD_CHANGED", c.username(), c.username(), ip, null);
    }

    @Transactional
    public void definirMotDePasse(long id, String mdp, boolean aChanger) {
        jdbc.sql("update auth_user set password_hash = :h, must_change_password = :m, updated_at = now(), version = version + 1 where id = :i")
                .param("h", encodeur.encode(mdp)).param("m", aChanger).param("i", id).update();
    }

    public String encoder(String mdp) {
        return encodeur.encode(mdp);
    }

    /** Mot de passe provisoire lisible (12 caractères, lettres et chiffres). */
    public static String provisoire() {
        String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 12 || !sb.toString().matches(".*\\d.*") || !sb.toString().matches(".*[A-Za-z].*")) {
            if (sb.length() >= 12) sb.setLength(0);
            sb.append(alphabet.charAt(HASARD.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // ---------- Correspondance ----------
    private Compte compte(ResultSet rs, int i) throws SQLException {
        long id = rs.getLong("id");
        List<String> directs = jdbc.sql("select r.code from auth_user_role ur join auth_role r on r.id = ur.role_id where ur.user_id = :u order by r.code")
                .param("u", id).query(String.class).list();
        List<String> groupes = jdbc.sql("select g.code from auth_user_group ug join auth_group g on g.id = ug.group_id where ug.user_id = :u order by g.code")
                .param("u", id).query(String.class).list();
        List<String> roles = jdbc.sql("""
                select distinct r.code from auth_role r
                 where r.id in (select role_id from auth_user_role where user_id = :u
                                union select gr.role_id from auth_group_role gr join auth_user_group ug on ug.group_id = gr.group_id where ug.user_id = :u)
                 order by r.code
                """).param("u", id).query(String.class).list();
        List<String> permissions = jdbc.sql("""
                select distinct rp.permission_code from auth_role_permission rp
                 where rp.role_id in (select role_id from auth_user_role where user_id = :u
                                      union select gr.role_id from auth_group_role gr join auth_user_group ug on ug.group_id = gr.group_id where ug.user_id = :u)
                 order by 1
                """).param("u", id).query(String.class).list();
        return new Compte(id, rs.getString("username"), rs.getString("full_name"), rs.getString("email"), rs.getString("organisation"),
                rs.getString("language"), rs.getBoolean("active"), rs.getBoolean("must_change_password"), rs.getInt("failed_attempts"),
                rs.getObject("locked_until", OffsetDateTime.class), rs.getObject("last_login_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class), directs, groupes, roles, permissions);
    }
}
