package com.nexora.auth.account;

import com.nexora.auth.web.AuthException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** Administration : utilisateurs, groupes, rôles et habilitations (permissions des rôles). */
@Service
public class AdminService {

    public record UtilisateurSaisi(String username, String fullName, String email, String organisation, String language,
                                   Boolean active, String password, Boolean mustChangePassword, List<String> groups, List<String> roles) {}

    public record GroupeSaisi(String code, String labelFr, String labelEn, String description, List<String> roles, List<String> members) {}

    public record RoleSaisi(String code, String labelFr, String labelEn, String description, List<String> permissions) {}

    public record Groupe(long id, String code, String labelFr, String labelEn, String description, List<String> roles, List<String> members,
                         OffsetDateTime updatedAt) {}

    public record Role(long id, String code, String labelFr, String labelEn, String description, boolean system, List<String> permissions,
                       long userCount, OffsetDateTime updatedAt) {}

    public record Permission(String code, String application, String labelFr, String labelEn, String description) {}

    public record Creation(Compte user, String temporaryPassword) {}

    private static final Pattern IDENTIFIANT = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,59}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    private final JdbcClient jdbc;
    private final AccountService comptes;
    private final Journal journal;

    public AdminService(JdbcClient jdbc, AccountService comptes, Journal journal) {
        this.jdbc = jdbc;
        this.comptes = comptes;
        this.journal = journal;
    }

    // ---------- Utilisateurs ----------
    @Transactional
    public Creation creerUtilisateur(UtilisateurSaisi s, String acteur, String ip) {
        String u = s.username() == null ? "" : s.username().toLowerCase(Locale.ROOT).strip();
        List<String> e = new ArrayList<>();
        if (!IDENTIFIANT.matcher(u).matches()) e.add("Identifiant : 2 à 60 caractères (minuscules, chiffres, « . », « _ », « - »).");
        else if (comptes.parUsername(u).isPresent()) e.add("L'identifiant « " + u + " » existe déjà.");
        validerUtilisateur(s, e);
        String mdp = s.password() == null || s.password().isBlank() ? null : s.password();
        if (mdp != null) e.addAll(AccountService.controlerMotDePasse(mdp));
        if (!e.isEmpty()) throw AuthException.invalide(e);
        String provisoire = mdp == null ? AccountService.provisoire() : null;
        long id = jdbc.sql("""
                insert into auth_user (username, full_name, email, organisation, language, active, password_hash, must_change_password)
                values (:u, :n, :e, :o, :l, :a, :h, :m) returning id
                """)
                .param("u", u).param("n", s.fullName().strip()).param("e", vide(s.email())).param("o", vide(s.organisation()))
                .param("l", langue(s.language())).param("a", s.active() == null || s.active())
                .param("h", comptes.encoder(mdp != null ? mdp : provisoire))
                .param("m", provisoire != null || Boolean.TRUE.equals(s.mustChangePassword()))
                .query(Long.class).single();
        affecter(id, s.groups(), s.roles());
        journal.tracer("USER_CREATED", u, acteur, ip, resume(s));
        return new Creation(comptes.parId(id).orElseThrow(), provisoire);
    }

    @Transactional
    public Compte modifierUtilisateur(long id, UtilisateurSaisi s, String acteur, String ip) {
        Compte avant = comptes.parId(id).orElseThrow(() -> AuthException.introuvable("Utilisateur introuvable."));
        List<String> e = new ArrayList<>();
        validerUtilisateur(s, e);
        boolean actif = s.active() == null || s.active();
        if (avant.username().equals(acteur) && !actif) e.add("Vous ne pouvez pas désactiver votre propre compte.");
        if (avant.username().equals(acteur) && avant.permissions().contains("auth.administration") && !resteAdministrateur(s))
            e.add("Vous ne pouvez pas retirer vos propres droits d'administration.");
        if (!e.isEmpty()) throw AuthException.invalide(e);
        jdbc.sql("""
                update auth_user set full_name = :n, email = :e, organisation = :o, language = :l, active = :a,
                       updated_at = now(), version = version + 1 where id = :i
                """)
                .param("n", s.fullName().strip()).param("e", vide(s.email())).param("o", vide(s.organisation()))
                .param("l", langue(s.language())).param("a", actif).param("i", id).update();
        jdbc.sql("delete from auth_user_group where user_id = :i").param("i", id).update();
        jdbc.sql("delete from auth_user_role where user_id = :i").param("i", id).update();
        affecter(id, s.groups(), s.roles());
        if (!actif) comptes.fermerSessions(id);
        journal.tracer(actif ? "USER_UPDATED" : "USER_DISABLED", avant.username(), acteur, ip, resume(s));
        return comptes.parId(id).orElseThrow();
    }

    @Transactional
    public String reinitialiserMotDePasse(long id, String acteur, String ip) {
        Compte c = comptes.parId(id).orElseThrow(() -> AuthException.introuvable("Utilisateur introuvable."));
        String mdp = AccountService.provisoire();
        comptes.definirMotDePasse(id, mdp, true);
        jdbc.sql("update auth_user set failed_attempts = 0, locked_until = null where id = :i").param("i", id).update();
        comptes.fermerSessions(id);
        journal.tracer("PASSWORD_RESET", c.username(), acteur, ip, null);
        return mdp;
    }

    @Transactional
    public Compte deverrouiller(long id, String acteur, String ip) {
        Compte c = comptes.parId(id).orElseThrow(() -> AuthException.introuvable("Utilisateur introuvable."));
        jdbc.sql("update auth_user set failed_attempts = 0, locked_until = null where id = :i").param("i", id).update();
        journal.tracer("ACCOUNT_UNLOCKED", c.username(), acteur, ip, null);
        return comptes.parId(id).orElseThrow();
    }

    private void validerUtilisateur(UtilisateurSaisi s, List<String> e) {
        if (s.fullName() == null || s.fullName().isBlank()) e.add("Le nom complet est obligatoire.");
        if (s.email() != null && !s.email().isBlank() && !s.email().strip().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) e.add("Adresse e-mail invalide.");
        controlerCodes("auth_group", s.groups(), "Groupe", e);
        controlerCodes("auth_role", s.roles(), "Rôle", e);
    }

    private boolean resteAdministrateur(UtilisateurSaisi s) {
        List<String> roles = s.roles() == null ? List.of() : s.roles();
        List<String> groupes = s.groups() == null ? List.of() : s.groups();
        return jdbc.sql("""
                select count(*) from auth_role_permission rp join auth_role r on r.id = rp.role_id
                 where rp.permission_code = 'auth.administration'
                   and (r.code = any(cast(:r as text[]))
                        or r.id in (select gr.role_id from auth_group_role gr join auth_group g on g.id = gr.group_id where g.code = any(cast(:g as text[]))))
                """).param("r", roles.toArray(String[]::new)).param("g", groupes.toArray(String[]::new)).query(Long.class).single() > 0;
    }

    private void affecter(long userId, List<String> groupes, List<String> roles) {
        for (String g : groupes == null ? List.<String>of() : groupes)
            jdbc.sql("insert into auth_user_group (user_id, group_id) select :u, id from auth_group where code = :c on conflict do nothing")
                    .param("u", userId).param("c", g).update();
        for (String r : roles == null ? List.<String>of() : roles)
            jdbc.sql("insert into auth_user_role (user_id, role_id) select :u, id from auth_role where code = :c on conflict do nothing")
                    .param("u", userId).param("c", r).update();
    }

    // ---------- Groupes ----------
    @Transactional(readOnly = true)
    public List<Groupe> groupes() {
        return jdbc.sql("select * from auth_group order by code").query((rs, i) -> {
            long id = rs.getLong("id");
            return new Groupe(id, rs.getString("code"), rs.getString("label_fr"), rs.getString("label_en"), rs.getString("description"),
                    jdbc.sql("select r.code from auth_group_role gr join auth_role r on r.id = gr.role_id where gr.group_id = :g order by 1").param("g", id).query(String.class).list(),
                    jdbc.sql("select u.username from auth_user_group ug join auth_user u on u.id = ug.user_id where ug.group_id = :g order by 1").param("g", id).query(String.class).list(),
                    rs.getObject("updated_at", OffsetDateTime.class));
        }).list();
    }

    @Transactional
    public Groupe enregistrerGroupe(Long id, GroupeSaisi s, String acteur, String ip) {
        List<String> e = new ArrayList<>();
        String code = s.code() == null ? "" : s.code().strip().toUpperCase(Locale.ROOT);
        if (id == null && !CODE.matcher(code).matches()) e.add("Code du groupe : majuscules, chiffres et « _ » (2 à 40 caractères).");
        if (s.labelFr() == null || s.labelFr().isBlank()) e.add("Le libellé du groupe est obligatoire.");
        controlerCodes("auth_role", s.roles(), "Rôle", e);
        for (String m : s.members() == null ? List.<String>of() : s.members()) if (comptes.parUsername(m).isEmpty()) e.add("Utilisateur « " + m + " » inconnu.");
        if (!e.isEmpty()) throw AuthException.invalide(e);
        long gid;
        if (id == null) {
            gid = jdbc.sql("insert into auth_group (code, label_fr, label_en, description) values (:c, :fr, :en, :d) returning id")
                    .param("c", code).param("fr", s.labelFr().strip()).param("en", vide(s.labelEn())).param("d", vide(s.description())).query(Long.class).single();
        } else {
            gid = id;
            if (jdbc.sql("update auth_group set label_fr = :fr, label_en = :en, description = :d, updated_at = now() where id = :i")
                    .param("fr", s.labelFr().strip()).param("en", vide(s.labelEn())).param("d", vide(s.description())).param("i", id).update() == 0)
                throw AuthException.introuvable("Groupe introuvable.");
            jdbc.sql("delete from auth_group_role where group_id = :g").param("g", gid).update();
            jdbc.sql("delete from auth_user_group where group_id = :g").param("g", gid).update();
        }
        for (String r : s.roles() == null ? List.<String>of() : s.roles())
            jdbc.sql("insert into auth_group_role (group_id, role_id) select :g, id from auth_role where code = :c").param("g", gid).param("c", r).update();
        for (String m : s.members() == null ? List.<String>of() : s.members())
            jdbc.sql("insert into auth_user_group (user_id, group_id) select id, :g from auth_user where username = :u").param("g", gid).param("u", m).update();
        journal.tracer(id == null ? "GROUP_CREATED" : "GROUP_UPDATED", null, acteur, ip,
                (id == null ? code : groupes().stream().filter(g -> g.id() == gid).map(Groupe::code).findFirst().orElse("")) + " — rôles " + s.roles() + ", membres " + s.members());
        return groupes().stream().filter(g -> g.id() == gid).findFirst().orElseThrow();
    }

    @Transactional
    public void supprimerGroupe(long id, String acteur, String ip) {
        String code = jdbc.sql("select code from auth_group where id = :i").param("i", id).query(String.class).optional()
                .orElseThrow(() -> AuthException.introuvable("Groupe introuvable."));
        jdbc.sql("delete from auth_group where id = :i").param("i", id).update();
        journal.tracer("GROUP_DELETED", null, acteur, ip, code);
    }

    // ---------- Rôles et habilitations ----------
    @Transactional(readOnly = true)
    public List<Role> roles() {
        return jdbc.sql("select * from auth_role order by system desc, code").query((rs, i) -> {
            long id = rs.getLong("id");
            long n = jdbc.sql("""
                    select count(distinct u) from (select user_id u from auth_user_role where role_id = :r
                     union select ug.user_id from auth_user_group ug join auth_group_role gr on gr.group_id = ug.group_id where gr.role_id = :r) x
                    """).param("r", id).query(Long.class).single();
            return new Role(id, rs.getString("code"), rs.getString("label_fr"), rs.getString("label_en"), rs.getString("description"),
                    rs.getBoolean("system"),
                    jdbc.sql("select permission_code from auth_role_permission where role_id = :r order by 1").param("r", id).query(String.class).list(),
                    n, rs.getObject("updated_at", OffsetDateTime.class));
        }).list();
    }

    @Transactional
    public Role enregistrerRole(Long id, RoleSaisi s, String acteur, String ip) {
        List<String> e = new ArrayList<>();
        String code = s.code() == null ? "" : s.code().strip().toUpperCase(Locale.ROOT);
        if (id == null && !CODE.matcher(code).matches()) e.add("Code du rôle : majuscules, chiffres et « _ » (2 à 40 caractères).");
        if (s.labelFr() == null || s.labelFr().isBlank()) e.add("Le libellé du rôle est obligatoire.");
        Set<String> connues = new HashSet<>(jdbc.sql("select code from auth_permission").query(String.class).list());
        for (String p : s.permissions() == null ? List.<String>of() : s.permissions()) if (!connues.contains(p)) e.add("Permission « " + p + " » inconnue.");
        if (id != null) {
            String c = jdbc.sql("select code from auth_role where id = :i").param("i", id).query(String.class).optional().orElse(null);
            if ("ADMINISTRATEUR".equals(c) && (s.permissions() == null || !s.permissions().contains("auth.administration")))
                e.add("Le rôle Administrateur doit conserver le droit d'administrer les utilisateurs.");
        }
        if (!e.isEmpty()) throw AuthException.invalide(e);
        long rid;
        if (id == null) {
            rid = jdbc.sql("insert into auth_role (code, label_fr, label_en, description) values (:c, :fr, :en, :d) returning id")
                    .param("c", code).param("fr", s.labelFr().strip()).param("en", vide(s.labelEn())).param("d", vide(s.description())).query(Long.class).single();
        } else {
            rid = id;
            if (jdbc.sql("update auth_role set label_fr = :fr, label_en = :en, description = :d, updated_at = now() where id = :i")
                    .param("fr", s.labelFr().strip()).param("en", vide(s.labelEn())).param("d", vide(s.description())).param("i", id).update() == 0)
                throw AuthException.introuvable("Rôle introuvable.");
            jdbc.sql("delete from auth_role_permission where role_id = :r").param("r", rid).update();
        }
        for (String p : s.permissions() == null ? List.<String>of() : s.permissions())
            jdbc.sql("insert into auth_role_permission (role_id, permission_code) values (:r, :p)").param("r", rid).param("p", p).update();
        Role r = roles().stream().filter(x -> x.id() == rid).findFirst().orElseThrow();
        journal.tracer(id == null ? "ROLE_CREATED" : "ROLE_UPDATED", null, acteur, ip, r.code() + " — " + r.permissions());
        return r;
    }

    @Transactional
    public void supprimerRole(long id, String acteur, String ip) {
        Role r = roles().stream().filter(x -> x.id() == id).findFirst().orElseThrow(() -> AuthException.introuvable("Rôle introuvable."));
        if (r.system()) throw AuthException.conflit("Le rôle « " + r.labelFr() + " » est livré avec la plateforme et ne peut pas être supprimé.");
        jdbc.sql("delete from auth_role where id = :i").param("i", id).update();
        journal.tracer("ROLE_DELETED", null, acteur, ip, r.code());
    }

    @Transactional(readOnly = true)
    public List<Permission> permissions() {
        return jdbc.sql("select * from auth_permission order by application, sort_order, code")
                .query((rs, i) -> new Permission(rs.getString("code"), rs.getString("application"), rs.getString("label_fr"),
                        rs.getString("label_en"), rs.getString("description"))).list();
    }

    // ---------- Utilitaires ----------
    private void controlerCodes(String table, List<String> codes, String nom, List<String> e) {
        if (codes == null || codes.isEmpty()) return;
        Set<String> connus = new HashSet<>(jdbc.sql("select code from " + table).query(String.class).list());
        for (String c : codes) if (!connus.contains(c)) e.add(nom + " « " + c + " » inconnu.");
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String langue(String l) {
        return "en".equals(l) ? "en" : "fr";
    }

    private static String resume(UtilisateurSaisi s) {
        return "groupes " + (s.groups() == null ? "[]" : s.groups()) + ", rôles " + (s.roles() == null ? "[]" : s.roles())
                + (Boolean.FALSE.equals(s.active()) ? ", désactivé" : "");
    }
}
