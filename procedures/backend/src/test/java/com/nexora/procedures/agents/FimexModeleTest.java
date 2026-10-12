package com.nexora.procedures.agents;

import com.nexora.procedures.studio.Modele.*;
import com.nexora.regles.Antecedent;
import com.nexora.regles.Demande;
import com.nexora.regles.MoteurRegles;
import com.nexora.regles.Resultat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.operaton.bpm.model.bpmn.instance.ServiceTask;
import org.operaton.bpm.model.bpmn.instance.UserTask;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.Year;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Modèle FIMEX amorcé : workflow BPMN généré, règles DRL et extraction de la SFD, sans base de données. */
class FimexModeleTest {

    private static VersionComplete fimex;
    private static String drl;

    @BeforeAll
    static void charger() throws Exception {
        JsonMapper m = JsonMapper.builder().disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
        JsonNode r = m.readTree(FimexModeleTest.class.getResourceAsStream("/seed/fimex/version.json"));
        drl = new String(FimexModeleTest.class.getResourceAsStream("/seed/fimex/fimex.drl").readAllBytes(), StandardCharsets.UTF_8);
        Procedure p = m.treeToValue(r.get("procedure"), Procedure.class);
        Map<String, Object> settings = m.convertValue(r.get("settings"), new TypeReference<Map<String, Object>>() {});
        Version v = new Version(1, "FIMEX", 1, "draft", settings, "", null, "test", null, null, null, null, null, null, null, null, null, null);
        fimex = new VersionComplete(v, p, m.convertValue(r.get("requestTypes"), new TypeReference<List<RequestType>>() {}),
                m.convertValue(r.get("actors"), new TypeReference<List<Actor>>() {}), m.convertValue(r.get("statuses"), new TypeReference<List<CaseStatus>>() {}),
                m.convertValue(r.get("sections"), new TypeReference<List<Section>>() {}), m.convertValue(r.get("fields"), new TypeReference<List<Field>>() {}),
                m.convertValue(r.get("documents"), new TypeReference<List<RequiredDocument>>() {}), m.convertValue(r.get("fees"), new TypeReference<List<FeeItem>>() {}),
                m.convertValue(r.get("messages"), new TypeReference<List<MessageDef>>() {}), m.convertValue(r.get("flows"), new TypeReference<List<Flow>>() {}),
                null, new RuleSet(drl, m.convertValue(r.get("businessRules"), new TypeReference<List<BusinessRule>>() {}), null, null),
                m.convertValue(r.get("deliverables"), new TypeReference<List<Deliverable>>() {}));
    }

    @Test
    void workflowBpmnGenereEstValideEtUtiliseLeCatalogue() {
        String xml = BpmnGenerateur.generer(fimex, "fimex");
        BpmnModelInstance b = Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Bpmn.validateModel(b);
        Set<String> delegues = new TreeSet<>();
        b.getModelElementsByType(ServiceTask.class).forEach(t -> delegues.add(t.getOperatonDelegateExpression()));
        assertEquals(Set.of("${regles}", "${paiement}", "${cotation}", "${message}", "${delivrance}", "${statut}"), delegues);
        Map<String, String> assignes = new TreeMap<>();
        b.getModelElementsByType(UserTask.class).forEach(t -> assignes.put(t.getId(), t.getOperatonAssignee()));
        assertEquals(Map.of("Task_Complement", "${demandeur}", "Task_Signature", "${signataire}", "Task_Traitement", "${agentTraitement}"), assignes);
        assertTrue(xml.contains("stringValue=\"FIMEX_PAYMENT_NOTICE\""), "avis de paiement du catalogue");
        assertTrue(xml.contains("stringValue=\"FIMEX_CERTIFICATE,FIMEX_PARTY_UPDATE\""), "messages de délivrance");
        assertTrue(xml.contains("<bpmndi:BPMNShape"), "diagramme BPMN DI");
    }

    @Test
    void lesReglesFimexCompilentEtCalculentLesFrais() {
        assertEquals(List.of(), MoteurRegles.verifier(drl));
        assertEquals(new BigDecimal("46500"), frais("IMP", "INITIAL").total());
        assertEquals(new BigDecimal("31500"), frais("EXP", "INITIAL").total());
        assertEquals(new BigDecimal("46500"), frais("AS", "INITIAL").total());
        assertEquals(0, frais("IMP", "AMENDMENT").getFrais().size(), "l'amendement n'est pas payant");
    }

    @Test
    void lesReglesDeValidationAppliquentLaSfd() {
        Demande d = demande("AS", "RENEWAL");
        assertTrue(champs(valider(d, List.of())).contains("nature"), "RG1 : pas de renouvellement des AS");

        d = demande("IMP", "INITIAL");
        d.getChamps().put("foreignerCitizen", "E");
        assertTrue(champs(valider(d, List.of())).contains("P7"), "P7 obligatoire pour un étranger");
        d.getPieces().add("P7");
        assertEquals(List.of(), valider(d, List.of()).getErreurs());

        d = demande("IMP", "INITIAL");
        d.setTitulaire(false);
        assertTrue(champs(valider(d, List.of())).contains("taxIdentifier"), "RG4 : pas de dépôt pour un tiers");
        d.setMandataire(true);
        assertEquals(List.of(), valider(d, List.of()).getErreurs(), "RG5 : un CDA peut déposer pour un tiers");

        int annee = Year.now().getValue();
        d = demande("IMP", "RENEWAL");
        d.getChamps().put("renewYear", String.valueOf(annee));
        Antecedent valide = new Antecedent("CM" + annee + "IFI000001", "IMP", "INITIAL", "VALIDE", annee, LocalDate.of(annee, 12, 31), true, false);
        assertTrue(champs(valider(d, List.of(valide))).contains("renewYear"), "RG05 : année n+1");
        d.getChamps().put("renewYear", String.valueOf(annee + 1));
        assertEquals(List.of(), valider(d, List.of(valide)).getErreurs());
        Antecedent enCours = new Antecedent("CM" + annee + "IFI000009", "IMP", "RENEWAL", "EN_ATTENTE_TRAITEMENT", annee + 1, null, false, true);
        assertTrue(champs(valider(d, List.of(valide, enCours))).contains("renewYear"), "RG07 : un seul renouvellement");

        d = demande("EXP", "AMENDMENT");
        assertTrue(champs(valider(d, List.of())).contains("previousRequestNumber"), "UC-03 : amendement sans attestation valide");

        d = demande("IMP", "INITIAL");
        d.getGroupes().put("PRODUITS", List.of(Map.of("commodityClassificationId", "080390")));
        assertTrue(champs(valider(d, List.of())).contains("countryOfOriginCode"), "pays d'origine à l'import");
    }

    @Test
    void leRulesAgentRegenereLesFraisSansToucherAuxReglesManuelles() {
        String regenere = DrlGenerateur.remplacerSection(drl, "frais", DrlGenerateur.frais(fimex.fees()));
        regenere = DrlGenerateur.remplacerSection(regenere, "pieces", DrlGenerateur.pieces(fimex.documents()));
        assertEquals(List.of(), MoteurRegles.verifier(regenere));
        assertTrue(regenere.contains("RG05 - Année de renouvellement n+1"));
        assertEquals(new BigDecimal("46500"), MoteurRegles.evaluer("regen", regenere, "frais", demande("IMP", "INITIAL"), List.of()).total());
    }

    @Test
    void leDocumentAgentExtraitLaSfdFimex() throws Exception {
        Path sfd = Path.of(System.getProperty("user.home"), "Downloads", "SFD_FIMEX_e-GUCE3G_V1.4.docx");
        String texte = Files.exists(sfd) ? TexteSource.lire(Files.readAllBytes(sfd), sfd.getFileName().toString()).complet()
                : "| P9 | Pièce d'essai | o | Commune |\n| Coût total pour une inscription au fichier des importateurs | 46 500 FCFA | Répartition ¶ -MINCOMMERCE : 30 000 CFA ¶ -CNCC : 15 000 FCFA |";
        // Version sans pièces, sans frais ni acteurs : tout est à extraire
        VersionComplete vide = new VersionComplete(fimex.version(), fimex.procedure(), fimex.requestTypes(), List.of(), fimex.statuses(),
                fimex.sections(), fimex.fields(), List.of(), List.of(), fimex.messages(), fimex.flows(), null, null, fimex.deliverables());
        Agents.Sortie s = new DocumentAgent(sansModele()).executer(new Agents.Contexte(vide, "test", texte, sfd.getFileName().toString()));
        Map<String, Long> parType = new TreeMap<>();
        s.propositions().forEach(p -> parType.merge(p.kind(), 1L, Long::sum));
        System.out.println("Document Agent : " + s.resume());
        s.propositions().stream().filter(p -> p.kind().equals("FEE")).forEach(p -> System.out.println("  " + p.title()));
        assertTrue(parType.getOrDefault("DOCUMENT", 0L) >= 1);
        assertTrue(parType.getOrDefault("FEE", 0L) >= 1);
        if (Files.exists(sfd)) {
            assertEquals(8, parType.get("DOCUMENT"), "pièces P1 à P8");
            assertTrue(parType.getOrDefault("ACTOR", 0L) >= 5, "acteurs du processus");
            assertTrue(parType.getOrDefault("BUSINESS_RULE", 0L) >= 10, "règles RG / RM");
            assertTrue(s.propositions().stream().anyMatch(p -> p.kind().equals("FEE") && p.target().equals("GUCE")
                    && new BigDecimal(String.valueOf(p.payload().get("amount"))).compareTo(new BigDecimal("3000")) == 0), "commission GUCE 10 %");
        }
    }

    private static Demande demande(String type, String nature) {
        Demande d = new Demande("FIMEX", type, nature, Year.now().getValue());
        d.setTitulaire(true);
        d.getChamps().put("foreignerCitizen", "C");
        d.getGroupes().put("PRODUITS", List.of(Map.of("commodityClassificationId", "080390", "countryOfOriginCode", "CI", "destinationCountryCode", "FR")));
        return d;
    }

    private static Resultat frais(String type, String nature) {
        return MoteurRegles.evaluer("fimex", drl, "frais", demande(type, nature), List.of());
    }

    private static Resultat valider(Demande d, List<Antecedent> a) {
        return MoteurRegles.evaluer("fimex", drl, "validation", d, a);
    }

    private static Set<String> champs(Resultat r) {
        Set<String> s = new TreeSet<>();
        r.getErreurs().forEach(e -> s.add(e.champ()));
        return s;
    }

    /** Cas réel de la domiciliation bancaire (SFD et diagramme draw.io), si les fichiers sont présents sur le poste. */
    private static final Path DOMI = Path.of("D:/DEV_2026/GUCE_2026/SFD_2026/SFD_IMPORT/02-J19JUIN-CIRCUIT-TRANSACTION/SFD_J2_VALID/DOMICILIATION");

    private static VersionComplete vide() {
        return new VersionComplete(fimex.version(), new Procedure("DOMI", "Domiciliation bancaire", null, "TRANSACTION", null, null, true, null, null),
                List.of(), List.of(), fimex.statuses(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, null, List.of());
    }

    @Test
    void leDiagrammeDrawioDeLaDomiciliationDevientUnWorkflowExecutable() throws Exception {
        Path f = DOMI.resolve("BPMN1_domiciliation_import.drawio");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(f), "fichier de domiciliation absent");
        DrawioSource d = DrawioSource.lire(Files.readString(f)).getFirst();
        System.out.println("draw.io : " + d.titre() + " — couloirs " + d.couloirs().stream().map(DrawioSource.Couloir::libelle).toList()
                + ", " + d.noeuds().size() + " nœuds, " + d.arcs().size() + " flux");
        assertEquals(3, d.couloirs().size());
        BpmnImport.Resultat r = BpmnImport.convertir(d, vide(), "domi");
        System.out.println("Rôles : " + r.roles());
        System.out.println("Étapes : " + String.join(" → ", r.etapes()));
        r.alertes().forEach(a -> System.out.println("  ! " + a));
        BpmnModelInstance m = Bpmn.readModelFromStream(new ByteArrayInputStream(r.xml().getBytes(StandardCharsets.UTF_8)));
        Bpmn.validateModel(m);
        UserTask banque = m.getModelElementsByType(UserTask.class).stream().filter(u -> u.getName().contains("domiciliation")).findFirst().orElseThrow();
        assertEquals("${agentTraitement}", banque.getOperatonAssignee());
        assertTrue(r.xml().contains("value=\"COMPLEMENT,VALIDER,REJETER\""));
        assertTrue(m.getModelElementsByType(ServiceTask.class).stream().anyMatch(s -> "${cotation}".equals(s.getOperatonDelegateExpression())));
        assertTrue(m.getModelElementsByType(ServiceTask.class).stream().anyMatch(s -> "${delivrance}".equals(s.getOperatonDelegateExpression())));
        assertFalse(m.getModelElementsByType(org.operaton.bpm.model.bpmn.instance.ParallelGateway.class).isEmpty());
        assertTrue(r.statuts().containsAll(List.of("REJETE", "VALIDE", "COMPLEMENT_DEMANDE")), r.statuts().toString());
        Files.writeString(Path.of("target", "domi-importe.bpmn"), r.xml());
    }

    @Test
    void leCycleCompletDeLaDomiciliationEstExecutable() throws Exception {
        Path f = DOMI.resolve("BPMN3_orchestration global.drawio");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(f), "fichier de domiciliation absent");
        DrawioSource d = DrawioSource.lire(Files.readString(f)).getFirst();
        BpmnImport.Resultat r = BpmnImport.convertir(d, vide(), "domi");
        System.out.println("Cycle complet : " + String.join(" → ", r.etapes()));
        System.out.println("Livrables : " + r.livrables() + " ; statuts propres : " + r.statutsPropres());
        BpmnModelInstance m = Bpmn.readModelFromStream(new ByteArrayInputStream(r.xml().getBytes(StandardCharsets.UTF_8)));
        Bpmn.validateModel(m);
        assertTrue(r.xml().contains("value=\"OUI,NON\""), "décision oui / non de l'apurement");
        assertEquals(3, r.livrables().size());
        assertEquals(3, r.livrables().stream().map(BpmnImport.Livrable::docCode).distinct().count());
        assertTrue(r.statutsPropres().stream().anyMatch(s -> !s.fin()), "attestation de domiciliation délivrée en cours de processus");
        assertTrue(r.statutsPropres().stream().filter(BpmnImport.Statut::fin).count() >= 2);
        Files.writeString(Path.of("target", "domi-cycle-complet.bpmn"), r.xml());
    }

    @Test
    void lesDonneesDeLaDomiciliationSontTireesDuTexteDeLaSfd() throws Exception {
        Path f = DOMI.resolve("SFD_IMPORT-TRANSACTION_DOMICILIATION_e-GUCE3G_V1.1.docx");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(f), "SFD de domiciliation absente");
        String texte = TexteSource.lire(Files.readAllBytes(f), f.getFileName().toString()).complet();
        List<Agents.Proposition> props = new ArrayList<>();
        DonneesSfd.proposer(TexteSource.texte(texte), texte, vide(), props);
        props.forEach(p -> System.out.println("  " + p.kind() + " " + p.title()));
        Set<String> codes = new HashSet<>();
        props.stream().filter(p -> p.kind().equals("FIELD")).forEach(p -> codes.add(p.target()));
        assertTrue(codes.containsAll(List.of("circuitNumber", "importDeclarationNumber", "domiciliationReference", "domiciliationDate", "processingLocation", "taxIdentifier")), codes.toString());
        Agents.Proposition ref = props.stream().filter(p -> p.target().equals("domiciliationReference")).findFirst().orElseThrow();
        assertEquals(5, ref.payload().get("maxLength"));
        assertTrue(props.stream().anyMatch(p -> p.kind().equals("REQUEST_TYPE")));
    }

    private static ModeleLangage sansModele() {
        return new ModeleLangage("", "", "", java.time.Duration.ofSeconds(1), 20000, new com.nexora.procedures.web.Json(JsonMapper.builder().build()));
    }

    @Test
    void lesReponsesDuModeleSontValidees() {
        String doc = DocumentAgent.normaliser("La Chambre de Commerce émet un avis consultatif sur la demande.");
        assertTrue(DocumentAgent.cite("Chambre de Commerce émet un avis", doc));
        assertFalse(DocumentAgent.cite("Cette phrase n'existe pas dans le document", doc));
        assertFalse(DocumentAgent.cite("avis", doc));
        assertEquals("{\"a\":1}", ModeleLangage.extraireObjet("```json\n{\"a\":1}\n```"));
        assertEquals(3, ModeleLangage.decouper("a\n".repeat(3000), 2000).size());
        assertFalse(sansModele().disponible());
    }
}
