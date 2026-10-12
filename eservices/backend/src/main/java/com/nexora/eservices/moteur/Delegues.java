package com.nexora.eservices.moteur;

import com.nexora.eservices.audit.Journal;
import com.nexora.eservices.cotation.CotationService;
import com.nexora.eservices.deploiement.DeploiementService;
import com.nexora.eservices.deploiement.Paquet;
import com.nexora.eservices.dossier.Dossier;
import com.nexora.eservices.dossier.DossierRepository;
import com.nexora.eservices.dossier.DossierService;
import com.nexora.eservices.livrable.LivrableService;
import com.nexora.eservices.message.MessageService;
import com.nexora.eservices.paiement.PaiementService;
import com.nexora.eservices.registre.Services;
import com.nexora.eservices.security.Habilitations;
import com.nexora.eservices.dossier.Validation;
import com.nexora.eservices.web.Json;
import org.operaton.bpm.engine.delegate.DelegateExecution;
import org.operaton.bpm.engine.delegate.ExecutionListener;
import org.operaton.bpm.engine.delegate.Expression;
import org.operaton.bpm.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Délégués génériques du workflow BPMN, appelés par expression ({@code ${statut}}, {@code ${regles}}, {@code ${paiement}},
 * {@code ${cotation}}, {@code ${message}}, {@code ${delivrance}}). Ils ne connaissent aucune procédure : tout vient du
 * paquet publié (statuts, règles DRL, frais, catalogue de messages, livrables). Portée « prototype » : les champs
 * injectés par le moteur (camunda:field) sont propres à chaque appel.
 */
public final class Delegues {

    private Delegues() {}

    /** Dossier et paquet de l'exécution courante (variable de processus « dossierId »). */
    record Ctx(Dossier dossier, Paquet paquet) {}

    static Ctx contexte(DelegateExecution e, DossierRepository repo, DeploiementService deploiements) {
        UUID id = UUID.fromString(String.valueOf(e.getVariable("dossierId")));
        Dossier d = repo.parId(id).orElseThrow(() -> new IllegalStateException("Dossier introuvable pour l'instance " + e.getProcessInstanceId()));
        return new Ctx(d, deploiements.paquet(d.deploymentId()));
    }

    static String texte(Expression x, DelegateExecution e) {
        if (x == null) return null;
        Object v = x.getValue(e);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).strip();
    }

    // ------------------------------------------------------------------ ${statut}
    /** Statut du dossier (terminologie GUCE) : tâche de service ou écouteur d'exécution, champ « code ». */
    @Component("statut")
    @Scope("prototype")
    public static class Statut implements JavaDelegate, ExecutionListener {
        private final DossierRepository repo;
        private final DeploiementService deploiements;
        private final PaiementService paiements;
        private Expression code;

        public Statut(DossierRepository repo, DeploiementService deploiements, PaiementService paiements) {
            this.repo = repo;
            this.deploiements = deploiements;
            this.paiements = paiements;
        }

        public void setCode(Expression code) { this.code = code; }

        @Override
        public void execute(DelegateExecution e) { appliquer(e); }

        @Override
        public void notify(DelegateExecution e) { appliquer(e); }

        private void appliquer(DelegateExecution e) {
            String c = texte(code, e);
            if (c == null) return;
            Ctx ctx = contexte(e, repo, deploiements);
            boolean fin = ctx.paquet().statut(c).map(Paquet.CaseStatus::isFinal).orElse(false);
            Object motif = e.getVariable("motifStatut");
            repo.statut(ctx.dossier().id(), c, fin, e.getCurrentActivityId(), Habilitations.acteur(), motif == null ? null : String.valueOf(motif));
            e.removeVariable("motifStatut");
            if ("ANNULE".equals(c)) paiements.annuler(ctx.dossier().id());
        }
    }

    // ------------------------------------------------------------------ ${regles}
    /** Évaluation des règles DRL au point « point » ; pour « frais » : variables paiementRequis et montant. */
    @Component("regles")
    @Scope("prototype")
    public static class Regles implements JavaDelegate {
        private final DossierRepository repo;
        private final DeploiementService deploiements;
        private final DossierService dossiers;
        private final JdbcClient jdbc;
        private final Journal journal;
        private final Json json;
        private Expression point;

        public Regles(DossierRepository repo, DeploiementService deploiements, DossierService dossiers, JdbcClient jdbc, Journal journal, Json json) {
            this.repo = repo;
            this.deploiements = deploiements;
            this.dossiers = dossiers;
            this.jdbc = jdbc;
            this.journal = journal;
            this.json = json;
        }

        public void setPoint(Expression point) { this.point = point; }

        @Override
        public void execute(DelegateExecution e) {
            Ctx ctx = contexte(e, repo, deploiements);
            String p = Optional.ofNullable(texte(point, e)).orElse("frais");
            Validation.Bilan b = dossiers.bilan(ctx.dossier());
            if (p.equals("frais")) {
                List<Map<String, Object>> frais = b.frais().stream().map(f -> Map.<String, Object>of("code", f.code(), "beneficiary", f.beneficiaire(),
                        "label", f.libelle(), "amount", f.montant())).toList();
                jdbc.sql("update wfe.dossier set fees = cast(:f as jsonb), total_amount = :t where dossier_id = :d")
                        .param("f", json.ecrire(frais)).param("t", b.total()).param("d", ctx.dossier().id()).update();
                e.setVariable("paiementRequis", b.total().signum() > 0);
                e.setVariable("montant", b.total().toPlainString());
            } else {
                e.setVariable("erreursRegles", b.erreurs().stream().map(Validation.Erreur::message).toList());
            }
            journal.tracer("REGLES_EVALUEES", ctx.dossier().id(), ctx.dossier().dossierNo(), Map.of("point", p, "total", b.total(), "rules", b.regles()));
        }
    }

    // ------------------------------------------------------------------ ${paiement}
    /** Avis de paiement à partir des frais calculés ; champ « message » : message du catalogue à émettre. */
    @Component("paiement")
    @Scope("prototype")
    public static class Paiement implements JavaDelegate {
        private final DossierRepository repo;
        private final DeploiementService deploiements;
        private final PaiementService paiements;
        private Expression message;

        public Paiement(DossierRepository repo, DeploiementService deploiements, PaiementService paiements) {
            this.repo = repo;
            this.deploiements = deploiements;
            this.paiements = paiements;
        }

        public void setMessage(Expression message) { this.message = message; }

        @Override
        public void execute(DelegateExecution e) {
            Ctx ctx = contexte(e, repo, deploiements);
            paiements.emettre(ctx.dossier(), ctx.dossier().fees(), texte(message, e), String.valueOf(e.getVariable("delaiPaiement")));
        }
    }

    // ------------------------------------------------------------------ ${cotation}
    /** Cotation automatique, puis transmission de la demande à eGOV (premier message REQUEST du catalogue, avec les pièces). */
    @Component("cotation")
    @Scope("prototype")
    public static class Cotation implements JavaDelegate {
        private final DossierRepository repo;
        private final DeploiementService deploiements;
        private final CotationService cotation;
        private final MessageService messages;
        private final JdbcClient jdbc;

        public Cotation(DossierRepository repo, DeploiementService deploiements, CotationService cotation, MessageService messages, JdbcClient jdbc) {
            this.repo = repo;
            this.deploiements = deploiements;
            this.cotation = cotation;
            this.messages = messages;
            this.jdbc = jdbc;
        }

        @Override
        public void execute(DelegateExecution e) {
            Ctx ctx = contexte(e, repo, deploiements);
            CotationService.Affectation a = cotation.coter(ctx.dossier(), ctx.paquet());
            e.setVariable("agentTraitement", a.agent());
            e.setVariable("signataire", a.signataire());
            Optional<Paquet.MessageDef> demande = ctx.paquet().premierMessage("REQUEST");
            boolean dejaEmise = demande.isPresent() && jdbc.sql("select count(*) from wfe.message where dossier_id = :d and message_code = :c")
                    .param("d", ctx.dossier().id()).param("c", demande.get().code()).query(Long.class).single() > 0;
            if (demande.isPresent() && !dejaEmise) {
                List<Long> pieces = repo.pieces(ctx.dossier().id()).stream().map(DossierRepository.Piece::id).toList();
                messages.emettre(repo.parId(ctx.dossier().id()).orElseThrow(), demande.get().code(), Map.of(), null, null, pieces);
            }
        }
    }

    // ------------------------------------------------------------------ ${message}
    /** Émission d'un message du catalogue (champ « code ») avec l'observation et la décision en cours. */
    @Component("message")
    @Scope("prototype")
    public static class Message implements JavaDelegate {
        private final DossierRepository repo;
        private final DeploiementService deploiements;
        private final MessageService messages;
        private Expression code;

        public Message(DossierRepository repo, DeploiementService deploiements, MessageService messages) {
            this.repo = repo;
            this.deploiements = deploiements;
            this.messages = messages;
        }

        public void setCode(Expression code) { this.code = code; }

        @Override
        public void execute(DelegateExecution e) {
            String c = texte(code, e);
            if (c == null) return;
            Ctx ctx = contexte(e, repo, deploiements);
            Map<String, Object> m = new HashMap<>();
            m.put("dossierNo", ctx.dossier().dossierNo());
            m.put("observation", Objects.toString(ctx.dossier().lastObservation(), ""));
            m.put("decision", Objects.toString(e.getVariable("decision"), ""));
            m.put("decidedBy", Objects.toString(e.getVariable("decideur"), Habilitations.acteur()));
            messages.emettre(ctx.dossier(), c, m, ctx.dossier().lastObservation(), null, null);
        }
    }

    // ------------------------------------------------------------------ ${delivrance}
    /**
     * Délivrance du livrable (champ « livrable »), émission des messages de délivrance (champ « messages ») et mise à jour
     * du registre des opérateurs (RG2 UC-05), puis statut final (champ « statut »).
     */
    @Component("delivrance")
    @Scope("prototype")
    public static class Delivrance implements JavaDelegate {
        private static final Logger log = LoggerFactory.getLogger(Delivrance.class);
        private final DossierRepository repo;
        private final DeploiementService deploiements;
        private final LivrableService livrables;
        private final MessageService messages;
        private final Services services;
        private final Journal journal;
        private Expression livrable;
        private Expression messagesCodes;
        private Expression statut;

        public Delivrance(DossierRepository repo, DeploiementService deploiements, LivrableService livrables, MessageService messages, Services services,
                          Journal journal) {
            this.repo = repo;
            this.deploiements = deploiements;
            this.livrables = livrables;
            this.messages = messages;
            this.services = services;
            this.journal = journal;
        }

        public void setLivrable(Expression livrable) { this.livrable = livrable; }
        public void setMessages(Expression messages) { this.messagesCodes = messages; }
        public void setStatut(Expression statut) { this.statut = statut; }

        @Override
        public void execute(DelegateExecution e) {
            Ctx ctx = contexte(e, repo, deploiements);
            Dossier d = ctx.dossier();
            Paquet p = ctx.paquet();
            String code = texte(livrable, e);
            LivrableService.Document doc = null;
            if (code != null) {
                String signataire = Objects.toString(e.getVariable("decideur"), Habilitations.acteur());
                doc = livrables.delivrer(d, code, Habilitations.utilisateur().orElse(signataire), Objects.toString(e.getVariable("nomDecideur"), signataire));
                e.setVariable("documentNo", doc.docNo());
            }
            String liste = texte(messagesCodes, e);
            if (liste != null)
                for (String m : liste.split(",")) {
                    String c = m.strip();
                    Paquet.MessageDef def = p.message(c).orElse(null);
                    if (def == null) continue;
                    if ("NOTIFICATION".equals(def.messageType()) && p.acteur(p.usage(c).map(u -> u.message().receiverActor()).orElse(""))
                            .map(a -> !"DEMANDEUR".equals(a.kind())).orElse(false)) {
                        majRegistre(d, p, c);
                        continue;
                    }
                    Map<String, Object> ctxMessage = new HashMap<>();
                    if (doc != null) {
                        ctxMessage.put("documentReferenceNumber", doc.baseDocNo());
                        ctxMessage.put("documentVersionId", String.valueOf(doc.versionNo()));
                        ctxMessage.put("signedDatetime", doc.signedAt().toString());
                        ctxMessage.put("expirationDatetime", Objects.toString(doc.validUntil(), ""));
                        ctxMessage.put("qrCodeText", doc.qrText());
                        ctxMessage.put("electronicSealText", doc.sealText());
                        ctxMessage.put("fileURIIdentifier", "/api/v1/documents/" + doc.docNo() + "/pdf");
                    }
                    messages.emettre(d, c, ctxMessage, null, null, null);
                }
            String s = texte(statut, e);
            if (s != null) repo.statut(d.id(), s, p.statut(s).map(Paquet.CaseStatus::isFinal).orElse(true), e.getCurrentActivityId(),
                    Habilitations.acteur(), doc == null ? "Livrable délivré" : "Livrable délivré : " + doc.docNo());
        }

        /** RG2 UC-05 : les données manquantes de l'opérateur sont complétées dans le registre ; le message en garde la trace. */
        private void majRegistre(Dossier d, Paquet p, String code) {
            UUID id = messages.emettre(d, code, Map.of(), null, null, null);
            if (!"true".equalsIgnoreCase(p.reglage("registryUpdate", "false")) || d.applicantNiu() == null) return;
            Map<String, Object> attributs = new LinkedHashMap<>();
            for (Paquet.Field f : p.fields())
                if (f.prefill() != null && !f.prefill().isBlank() && d.formData().get(f.code()) != null && !(d.formData().get(f.code()) instanceof Collection<?>))
                    attributs.put(f.prefill(), d.formData().get(f.code()));
            try {
                Map<String, Object> r = services.completer(d.applicantNiu(), attributs);
                if ("OUT_SENT".equals(messages.entete(id).state())) messages.etat(id, "OUT_ACKED", "Registre mis à jour : " + r.getOrDefault("completed", List.of()));
                journal.tracer("REGISTRE_COMPLETE", d.id(), d.dossierNo(), Map.of("niu", d.applicantNiu(), "completed", r.getOrDefault("completed", List.of())));
            } catch (RuntimeException ex) {
                log.warn("Mise à jour du registre impossible pour {} : {}", d.applicantNiu(), ex.getMessage());
                if ("OUT_SENT".equals(messages.entete(id).state())) messages.etat(id, "OUT_ERROR", "Registre indisponible : " + ex.getMessage());
            }
        }
    }
}
