package com.nexora.procedures.rdm;

import com.nexora.procedures.web.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.io.InputStream;
import java.util.List;

/**
 * Chargement de l'UNTDED 2005 (UN Trade Data Elements Directory, ISO 7372) dans rdm.untded_element au premier démarrage.
 * Source : seed/reference/untded2005.json, extrait du document UNTDED 2005 (tag, nom normalisé, description, représentation).
 */
@Component
public class ChargementUntded implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ChargementUntded.class);

    private final JdbcClient jdbc;
    private final Json json;
    private final RdmService rdm;

    public ChargementUntded(JdbcClient jdbc, Json json, RdmService rdm) {
        this.jdbc = jdbc;
        this.json = json;
        this.rdm = rdm;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (jdbc.sql("select count(*) from rdm.untded_element").query(Long.class).single() > 0) return;
        ClassPathResource r = new ClassPathResource("seed/reference/untded2005.json");
        if (!r.exists()) return;
        List<RdmService.Untded> l;
        try (InputStream in = r.getInputStream()) {
            l = json.mapper().readValue(in, new TypeReference<List<RdmService.Untded>>() {});
        }
        for (RdmService.Untded e : l)
            jdbc.sql("insert into rdm.untded_element (tag, name, description, representation) values (:t, :n, :d, :r) on conflict do nothing")
                    .param("t", e.tag()).param("n", e.name()).param("d", e.description()).param("r", e.representation()).update();
        rdm.oublierUntded();
        LOG.info("UNTDED 2005 chargé : {} éléments.", l.size());
    }
}
