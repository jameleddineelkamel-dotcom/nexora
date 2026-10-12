package com.nexora.eservices.cotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Paramétrage de démonstration de la cotation (ESERVICES_DEMO=true, aucun agent rattaché) : les comptes de test
 * test.agent (traitement) et test.signataire (signature) sont rattachés à toutes les unités, afin que tout lieu de
 * traitement puisse être coté. En production, l'administrateur rattache les vrais agents par unité.
 */
@Component
public class Demonstration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Demonstration.class);

    private final JdbcClient jdbc;
    private final boolean actif;

    public Demonstration(JdbcClient jdbc, @Value("${nexora.eservices.demo:true}") boolean actif) {
        this.jdbc = jdbc;
        this.actif = actif;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!actif || jdbc.sql("select count(*) from wfe.unit_member").query(Long.class).single() > 0) return;
        int n = jdbc.sql("""
                insert into wfe.unit_member (unit_code, username, role, full_name)
                select unit_code, m.username, m.role, m.nom from wfe.admin_unit,
                  (values ('test.agent', 'TRAITEMENT', 'Agent de traitement (test)'), ('test.signataire', 'SIGNATAIRE', 'Agent signataire (test)')) m(username, role, nom)
                """).update();
        log.info("Cotation : {} rattachements de démonstration (test.agent, test.signataire).", n);
    }
}
