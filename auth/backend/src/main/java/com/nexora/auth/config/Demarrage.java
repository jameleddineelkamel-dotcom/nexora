package com.nexora.auth.config;

import com.nexora.auth.account.AccountService;
import com.nexora.auth.account.Journal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;

/** Premier démarrage : création du compte « admin » (groupe Administrateurs) si aucun utilisateur n'existe. */
@Component
public class Demarrage implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Demarrage.class);

    private final JdbcClient jdbc;
    private final AccountService comptes;
    private final Journal journal;
    private final AuthProperties props;

    public Demarrage(JdbcClient jdbc, AccountService comptes, Journal journal, AuthProperties props) {
        this.jdbc = jdbc;
        this.comptes = comptes;
        this.journal = journal;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (jdbc.sql("select count(*) from auth_user").query(Long.class).single() > 0) return;
        String mdp = props.adminPassword();
        boolean provisoire = mdp == null || mdp.isBlank();
        if (provisoire) mdp = AccountService.provisoire();
        long id = jdbc.sql("""
                insert into auth_user (username, full_name, password_hash, must_change_password)
                values ('admin', 'Administrateur NEXORA', :h, :m) returning id
                """).param("h", comptes.encoder(mdp)).param("m", provisoire).query(Long.class).single();
        jdbc.sql("insert into auth_user_group (user_id, group_id) select :u, id from auth_group where code = 'ADMINISTRATEURS'").param("u", id).update();
        journal.tracer("USER_CREATED", "admin", "systeme", null, "Compte d'administration initial");
        if (provisoire) log.warn("Compte « admin » créé avec le mot de passe provisoire : {} (à changer à la première connexion)", mdp);
        else log.info("Compte « admin » créé (mot de passe défini par AUTH_ADMIN_PASSWORD).");
    }

    /** Routes de l'interface Angular servies par index.html. */
    @Controller
    static class Interface {
        @GetMapping({"/login", "/compte", "/admin", "/admin/**"})
        String index() {
            return "forward:/index.html";
        }
    }
}
