package com.nexora.procedures.agents;

import com.nexora.procedures.agents.AgentService.PropositionEnregistree;
import com.nexora.procedures.audit.Journal;
import com.nexora.procedures.studio.Modele.*;
import com.nexora.procedures.studio.StudioRepository;
import com.nexora.procedures.web.Json;
import com.nexora.procedures.web.ProceduresException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Revue humaine des propositions des agents (§14.2 : Accepter / Corriger / Rejeter). Accepter ou corriger applique la
 * proposition à la version brouillon (champ rapproché du SCRDM, pièce, acteur, frais, règle, BPMN, DRL, statut…) ;
 * la décision, son auteur et son commentaire sont conservés (RG-GOV-02, RG-GOV-03).
 */
@Service
public class RevueService {

    public record Revue(String decision, String comment, Map<String, Object> payload) {}

    private final AgentService agents;
    private final StudioRepository repo;
    private final Journal journal;
    private final JdbcClient jdbc;
    private final Json json;

    public RevueService(AgentService agents, StudioRepository repo, Journal journal, JdbcClient jdbc, Json json) {
        this.agents = agents;
        this.repo = repo;
        this.journal = journal;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public PropositionEnregistree revoir(long id, Revue r) {
        PropositionEnregistree p = agents.proposition(id);
        if (!p.reviewStatus().equals("pending")) throw ProceduresException.conflit("Cette proposition a déjà été revue (" + p.reviewStatus() + ").");
        String decision = r.decision() == null ? "" : r.decision().toLowerCase(Locale.ROOT);
        String statut = switch (decision) {
            case "accept", "accepted", "accepter" -> "accepted";
            case "correct", "corrected", "corriger" -> "corrected";
            case "reject", "rejected", "rejeter" -> "rejected";
            default -> throw ProceduresException.invalide("Décision attendue : accept, correct ou reject.");
        };
        if (statut.equals("rejected") && (r.comment() == null || r.comment().isBlank()))
            throw ProceduresException.invalide("Indiquez le motif du rejet.");
        Map<String, Object> payload = statut.equals("corrected") && r.payload() != null ? r.payload() : p.payload();
        Version v = repo.version(p.versionId()).orElseThrow();
        boolean modification = !List.of("FINDING", "REPORT").contains(p.kind());
        if (!statut.equals("rejected") && modification) {
            if (!v.lifecycleStatus().equals("draft"))
                throw ProceduresException.conflit("La version n'est plus en brouillon : la proposition ne peut plus être appliquée.");
            appliquer(p.kind(), payload, v);
            repo.toucher(v.id(), Journal.utilisateur());
        }
        jdbc.sql("""
                update studio.ai_proposal set review_status = cast(:s as studio.ai_review_status), reviewed_by = :u, reviewed_at = now(),
                       review_comment = :c, payload = cast(:p as jsonb) where id = :id
                """).param("s", statut).param("u", Journal.utilisateur()).param("c", r.comment()).param("p", json.ecrire(payload)).param("id", id).update();
        journal.tracer("PROPOSITION_" + statut.toUpperCase(Locale.ROOT), v.procedureCode(), v.id(), p.runId(), null,
                Map.of("proposal", id, "kind", p.kind(), "target", Objects.toString(p.target(), ""), "agent", p.agentCode()), null);
        return agents.proposition(id);
    }

    /** Accepte toutes les propositions en attente d'un type (ex. tous les rapprochements SCRDM d'un score suffisant). */
    @Transactional
    public Map<String, Object> accepterTout(long versionId, String kind, double confianceMin) {
        int n = 0;
        for (PropositionEnregistree p : agents.propositions(versionId, "pending")) {
            if (!p.kind().equals(kind) || p.confidence() == null || p.confidence() < confianceMin) continue;
            revoir(p.id(), new Revue("accept", "Acceptation groupée (confiance ≥ " + confianceMin + ")", null));
            n++;
        }
        return Map.of("accepted", n);
    }

    @SuppressWarnings("unchecked")
    private void appliquer(String kind, Map<String, Object> p, Version v) {
        long id = v.id();
        String auteur = Journal.utilisateur();
        switch (kind) {
            case "FIELD_MAPPING" -> {
                int n = jdbc.sql("update studio.form_field set bie_path = :b where version_id = :v and code = :c")
                        .param("b", texte(p, "biePath")).param("v", id).param("c", texte(p, "fieldCode")).update();
                if (n == 0) throw ProceduresException.introuvable("Champ introuvable : " + texte(p, "fieldCode"));
            }
            case "REFDATA" -> jdbc.sql("update studio.form_field set refdata_table = :t, data_type = 'CODE' where version_id = :v and code = :c")
                    .param("t", texte(p, "refdataTable")).param("v", id).param("c", texte(p, "fieldCode")).update();
            case "DOCUMENT" -> {
                List<RequiredDocument> l = new ArrayList<>(repo.pieces(id).stream().filter(d -> !d.code().equals(texte(p, "code"))).toList());
                l.add(new RequiredDocument(texte(p, "code"), texte(p, "labelFr"), texte(p, "labelEn"), Boolean.TRUE.equals(p.get("mandatory")),
                        texte(p, "conditionText"), (List<String>) p.getOrDefault("requestTypes", List.of()), texte(p, "observation"),
                        entier(p, "sortOrder")));
                l.sort(Comparator.comparingInt(RequiredDocument::sortOrder).thenComparing(RequiredDocument::code));
                repo.pieces(id, l);
            }
            case "ACTOR" -> {
                List<Actor> l = new ArrayList<>(repo.acteurs(id).stream().filter(a -> !a.code().equals(texte(p, "code"))).toList());
                l.add(new Actor(texte(p, "code"), texte(p, "labelFr"), texte(p, "labelEn"), texte(p, "kind"), texte(p, "systemCode"),
                        texte(p, "authRole"), texte(p, "responsibilities"), entier(p, "sortOrder")));
                repo.acteurs(id, l);
            }
            case "FEE" -> {
                List<FeeItem> l = new ArrayList<>(repo.frais(id).stream().filter(f -> !f.code().equals(texte(p, "code"))).toList());
                l.add(new FeeItem(texte(p, "code"), texte(p, "beneficiary"), texte(p, "labelFr"), texte(p, "labelEn"),
                        new BigDecimal(String.valueOf(p.get("amount"))), Objects.toString(p.get("currency"), "XAF"),
                        (List<String>) p.getOrDefault("requestTypes", List.of()), (List<String>) p.getOrDefault("natures", List.of()), entier(p, "sortOrder")));
                repo.frais(id, l);
            }
            case "STATUS" -> {
                List<CaseStatus> l = new ArrayList<>(repo.statuts(id).stream().filter(s -> !s.code().equals(texte(p, "code"))).toList());
                l.add(new CaseStatus(texte(p, "code"), texte(p, "labelFr"), texte(p, "labelEn"), Boolean.TRUE.equals(p.get("isFinal")),
                        Objects.toString(p.get("tone"), "neutral"), entier(p, "sortOrder")));
                l.sort(Comparator.comparingInt(CaseStatus::sortOrder));
                repo.statuts(id, l);
            }
            case "BUSINESS_RULE" -> {
                RuleSet r = repo.regles(id);
                List<BusinessRule> l = new ArrayList<>(r == null ? List.of() : r.businessRules());
                l.removeIf(b -> b.code().equals(texte(p, "code")));
                l.add(new BusinessRule(texte(p, "code"), texte(p, "text"), texte(p, "source"), texte(p, "drlRule")));
                repo.regles(id, new RuleSet(r == null ? "" : r.drl(), l, null, null), auteur);
            }
            case "DRL" -> {
                RuleSet r = repo.regles(id);
                repo.regles(id, new RuleSet(texte(p, "drl"), r == null ? List.of() : r.businessRules(), null, null), auteur);
            }
            case "BPMN" -> repo.processus(id, new ProcessModel(texte(p, "processKey"), texte(p, "bpmnXml"), null, null), auteur);
            case "SETTING" -> {
                Map<String, Object> s = new LinkedHashMap<>(v.settings());
                s.put(texte(p, "key"), p.get("value"));
                repo.parametres(id, new Parametres(s, v.changeNote()), auteur);
            }
            default -> throw ProceduresException.invalide("Type de proposition non applicable : " + kind);
        }
    }

    private static String texte(Map<String, Object> p, String cle) {
        Object v = p.get(cle);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private static int entier(Map<String, Object> p, String cle) {
        Object v = p.get(cle);
        return v instanceof Number n ? n.intValue() : v == null ? 0 : Integer.parseInt(String.valueOf(v));
    }

}
