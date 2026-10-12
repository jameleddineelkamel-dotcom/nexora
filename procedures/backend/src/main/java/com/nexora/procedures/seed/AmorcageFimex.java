package com.nexora.procedures.seed;

import com.nexora.procedures.agents.BpmnGenerateur;
import com.nexora.procedures.audit.Journal;
import com.nexora.procedures.studio.Modele.*;
import com.nexora.procedures.studio.StudioRepository;
import com.nexora.procedures.web.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Amorçage : la procédure FIMEX (SFD e-GUCE 3G v1.4, modèle de données DM_FIMEX v1.1) est créée en version 1
 * brouillon si elle n'existe pas. Le workflow BPMN est produit par le générateur du Single Window Agent, les règles DRL
 * sont celles du fichier seed/fimex/fimex.drl. La version suit ensuite le circuit normal : agents, revue, soumission,
 * approbation par une autre personne, publication.
 */
@Component
public class AmorcageFimex implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AmorcageFimex.class);

    private final StudioRepository repo;
    private final Journal journal;
    private final Json json;
    private final boolean actif;

    public AmorcageFimex(StudioRepository repo, Journal journal, Json json, @Value("${nexora.procedures.seed.enabled:true}") boolean actif) {
        this.repo = repo;
        this.journal = journal;
        this.json = json;
        this.actif = actif;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        if (!actif || repo.procedure("FIMEX").isPresent()) return;
        JsonNode racine;
        try (InputStream in = new ClassPathResource("seed/fimex/version.json").getInputStream()) {
            racine = json.mapper().readTree(in);
        }
        String drl = new String(new ClassPathResource("seed/fimex/fimex.drl").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        var m = json.mapper();
        Procedure p = m.treeToValue(racine.get("procedure"), Procedure.class);
        String auteur = "systeme";
        repo.creerProcedure(new Procedure(p.code(), p.labelFr(), p.labelEn(), p.circuitCode(), p.authority(), p.description(), true, null, null), auteur);
        Map<String, Object> settings = m.convertValue(racine.get("settings"), new TypeReference<Map<String, Object>>() {});
        long id = repo.creerVersion(p.code(), 1, settings, racine.get("changeNote").asString(), auteur);
        repo.typesDemande(id, liste(racine, "requestTypes", new TypeReference<>() {}));
        repo.acteurs(id, liste(racine, "actors", new TypeReference<>() {}));
        repo.statuts(id, liste(racine, "statuses", new TypeReference<>() {}));
        repo.formulaire(id, liste(racine, "sections", new TypeReference<>() {}), liste(racine, "fields", new TypeReference<>() {}));
        repo.pieces(id, liste(racine, "documents", new TypeReference<>() {}));
        repo.frais(id, liste(racine, "fees", new TypeReference<>() {}));
        repo.messages(id, liste(racine, "messages", new TypeReference<>() {}));
        repo.flux(id, liste(racine, "flows", new TypeReference<>() {}));
        repo.livrables(id, liste(racine, "deliverables", new TypeReference<>() {}));
        List<BusinessRule> regles = liste(racine, "businessRules", new TypeReference<>() {});
        repo.regles(id, new RuleSet(drl, regles, null, null), auteur);
        VersionComplete v = repo.complete(id);
        repo.processus(id, new ProcessModel("fimex", BpmnGenerateur.generer(v, "fimex"), null, null), auteur);
        journal.tracer("AMORCAGE", p.code(), id, Map.of("source", racine.get("source").asString()));
        log.info("Procédure FIMEX amorcée (version 1 brouillon) à partir de la SFD v1.4.");
    }

    private <T> List<T> liste(JsonNode racine, String cle, TypeReference<List<T>> type) {
        return json.mapper().convertValue(racine.get(cle), type);
    }
}
