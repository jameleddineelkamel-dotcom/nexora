package com.nexora.eservices.paiement;

import com.nexora.eservices.audit.Journal;
import com.nexora.eservices.deploiement.DeploiementService;
import com.nexora.eservices.deploiement.Paquet;
import com.nexora.eservices.dossier.Dossier;
import com.nexora.eservices.dossier.DossierService;
import com.nexora.eservices.message.Formats;
import com.nexora.eservices.message.MessageService;
import com.nexora.eservices.security.Habilitations;
import com.nexora.eservices.web.EservicesException;
import com.nexora.eservices.web.Json;
import org.operaton.bpm.engine.RuntimeService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * e-PAYMENT : avis de paiement (FIMEX_PAYMENT_NOTICE), confirmation par la banque (message entrant XML), corrélation
 * avec le workflow (message BPMN « PAIEMENT_CONFIRME »), suivi des encaissements par bénéficiaire (MINCOMMERCE, CNCC,
 * GUCE, DGI) et relevé CSV. Le traitement ne démarre qu'après paiement (RG3 UC-01).
 */
@Service
public class PaiementService implements MessageService.Traitement {

    public record Ligne(int lineNo, String feeCode, String beneficiary, String label, BigDecimal amount) {}

    public record Avis(long id, String noticeNo, UUID dossierId, String dossierNo, String status, String payee, String payerNiu, String payerName,
                       BigDecimal totalAmount, String currency, OffsetDateTime issuedAt, OffsetDateTime dueAt, OffsetDateTime paidAt,
                       String paymentReference, String bankCode, String channel, List<Ligne> lines) {}

    private final JdbcClient jdbc;
    private final MessageService messages;
    private final DossierService dossierService;
    private final DeploiementService deploiements;
    private final RuntimeService moteur;
    private final Journal journal;
    private final Json json;

    public PaiementService(JdbcClient jdbc, MessageService messages, DossierService dossierService,
                           DeploiementService deploiements, RuntimeService moteur, Journal journal, Json json) {
        this.jdbc = jdbc;
        this.messages = messages;
        this.dossierService = dossierService;
        this.deploiements = deploiements;
        this.moteur = moteur;
        this.journal = journal;
        this.json = json;
    }

    // ------------------------------------------------------------------ avis de paiement (appelé par le workflow)
    public Avis emettre(Dossier d, List<Map<String, Object>> frais, String codeMessage, String delai) {
        String numero = "AV" + d.dossierNo();
        BigDecimal total = frais.stream().map(f -> new BigDecimal(String.valueOf(f.get("amount")))).reduce(BigDecimal.ZERO, BigDecimal::add);
        OffsetDateTime echeance = delai == null ? null : OffsetDateTime.now().plus(duree(delai));
        long id = jdbc.sql("""
                insert into wfe.payment_notice (notice_no, dossier_id, payer_niu, payer_name, total_amount, currency, due_at)
                values (:n, :d, :niu, :nom, :t, :c, :due) returning notice_id
                """).param("n", numero).param("d", d.id()).param("niu", d.applicantNiu()).param("nom", d.applicantName()).param("t", total)
                .param("c", d.currency()).param("due", echeance).query(Long.class).single();
        int i = 0;
        for (Map<String, Object> f : frais)
            jdbc.sql("insert into wfe.payment_line values (:id, :l, :c, :b, :lib, :m)").param("id", id).param("l", ++i)
                    .param("c", String.valueOf(f.get("code"))).param("b", String.valueOf(f.get("beneficiary"))).param("lib", String.valueOf(f.get("label")))
                    .param("m", new BigDecimal(String.valueOf(f.get("amount")))).update();
        Avis a = avis(numero);
        if (codeMessage != null && !codeMessage.isBlank()) {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("paymentNoticeNumber", numero);
            ctx.put("requestId", d.dossierNo());
            ctx.put("paymentNoticeStatusCode", a.status());
            ctx.put("paymentNoticeeName", Objects.toString(d.applicantName(), d.applicantNiu()));
            ctx.put("paymentNoticeDatetime", a.issuedAt().toString());
            ctx.put("totalpaymentNoticeAmount", total.toPlainString());
            ctx.put("paymentNoticeCurrencyCode", d.currency());
            messages.emettre(d, codeMessage, ctx, null, null, null);
        }
        journal.tracer("AVIS_PAIEMENT_EMIS", d.id(), d.dossierNo(), Map.of("notice", numero, "total", total));
        return a;
    }

    // ------------------------------------------------------------------ confirmation (message entrant de la banque)
    @Override
    public boolean accepte(Paquet.MessageDef def, Map<String, List<String>> valeurs) {
        return "PAYMENT".equals(def.messageType()) && valeur(def, valeurs, "paymentNoticeNumber") != null && valeur(def, valeurs, "paymentReferenceText") != null;
    }

    @Override
    public UUID traiter(UUID messageId, Paquet.MessageDef def, Map<String, List<String>> valeurs) {
        String numero = valeur(def, valeurs, "paymentNoticeNumber");
        Avis a = jdbc.sql("select notice_no from wfe.payment_notice where notice_no = :n").param("n", numero).query(String.class).optional()
                .map(this::avis).orElseThrow(() -> EservicesException.introuvable("Avis de paiement inconnu : " + numero));
        if (!"ISSUED".equals(a.status()) && !"OVERDUE".equals(a.status()))
            throw EservicesException.conflit("L'avis " + numero + " n'est pas en attente de paiement (statut " + a.status() + ").");
        String montant = valeur(def, valeurs, "paidAmount");
        if (montant != null && new BigDecimal(montant.replace(" ", "").replace(',', '.')).compareTo(a.totalAmount()) < 0)
            throw EservicesException.invalide("Montant payé inférieur au montant de l'avis (" + a.totalAmount().toPlainString() + ").");
        String devise = valeur(def, valeurs, "currencyCode");
        if (devise != null && !devise.equalsIgnoreCase(a.currency())) throw EservicesException.invalide("Devise du paiement différente de celle de l'avis.");
        jdbc.sql("""
                update wfe.payment_notice set status = 'PAID', paid_at = coalesce(cast(:p as timestamptz), now()), payment_reference = :r,
                       bank_code = :b, channel = coalesce(channel, 'BANQUE') where notice_id = :i
                """).param("p", horodatage(valeur(def, valeurs, "paidAt"))).param("r", valeur(def, valeurs, "paymentReferenceText"))
                .param("b", valeur(def, valeurs, "bankCode")).param("i", a.id()).update();
        journal.tracer("PAIEMENT_CONFIRME", a.dossierId(), a.dossierNo(), Map.of("notice", numero, "reference", valeur(def, valeurs, "paymentReferenceText"),
                "message", messageId.toString()));
        // Le workflow attend le message BPMN « PAIEMENT_CONFIRME » (passerelle événementielle)
        moteur.createMessageCorrelation("PAIEMENT_CONFIRME").processInstanceBusinessKey(a.dossierNo()).correlate();
        return a.dossierId();
    }

    private static String valeur(Paquet.MessageDef def, Map<String, List<String>> valeurs, String cle) {
        for (Paquet.MessageField f : def.fields())
            if (Formats.cle(f.path(), def.rootElement()).equals(cle)) {
                List<String> v = valeurs.getOrDefault(f.path(), List.of());
                return v.isEmpty() || v.getFirst() == null || v.getFirst().isBlank() ? null : v.getFirst().strip();
            }
        return null;
    }

    private static OffsetDateTime horodatage(String s) {
        if (s == null) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Paiement en ligne depuis eFORCE (démonstration du module e-PAYMENT) : la banque choisie renvoie sa confirmation,
     * au format XML du catalogue, par le même canal qu'un message bancaire réel.
     */
    @Transactional
    public MessageService.Entete payer(String numero, String banque, String canal) {
        Avis a = avis(numero);
        Dossier d = dossierService.lire(a.dossierId());
        if (!Habilitations.acteur().equals(d.submittedBy()) && !Habilitations.utilisateurA("eservices.encaisser"))
            throw EservicesException.interdit("Seul le déposant (ou une banque) peut régler cet avis.");
        Paquet p = deploiements.paquet(d.deploymentId());
        Map<String, String> typeActeur = new HashMap<>();
        p.actors().forEach(x -> typeActeur.put(x.code(), x.kind()));
        Paquet.MessageDef def = p.messages().stream().filter(m -> "PAYMENT".equals(m.messageType()))
                .filter(m -> p.usage(m.code()).map(u -> "BANQUE".equals(typeActeur.get(u.message().senderActor()))).orElse(false))
                .findFirst().orElseThrow(() -> EservicesException.introuvable("Aucun message de confirmation bancaire dans le catalogue de la procédure."));
        String reference = "QT" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(OffsetDateTime.now()) + String.format("%04d", new Random().nextInt(10000));
        Map<String, String> v = Map.of("paymentNoticeNumber", numero, "paymentReferenceText", reference, "paidAmount", a.totalAmount().toPlainString(),
                "currencyCode", a.currency(), "paidAt", OffsetDateTime.now().toString(), "bankCode", banque == null ? "BANQUE" : banque);
        Map<String, List<String>> valeurs = new LinkedHashMap<>();
        def.fields().forEach(f -> valeurs.put(f.path(), v.containsKey(Formats.cle(f.path(), def.rootElement()))
                ? List.of(v.get(Formats.cle(f.path(), def.rootElement()))) : List.of()));
        byte[] contenu = Formats.ecrire(def, valeurs, Map.of(), null, json.mapper());
        jdbc.sql("update wfe.payment_notice set channel = :c where notice_id = :i").param("c", canal == null ? "EN_LIGNE" : canal).param("i", a.id()).update();
        return messages.recevoir(def.code(), contenu, "PAY:" + numero, banque);
    }

    // ------------------------------------------------------------------ consultation
    public Avis avis(String numero) {
        List<Ligne> lignes = jdbc.sql("""
                select l.line_no, l.fee_code, l.beneficiary, l.label, l.amount from wfe.payment_line l join wfe.payment_notice n on n.notice_id = l.notice_id
                 where n.notice_no = :n order by l.line_no
                """).param("n", numero).query((rs, i) -> new Ligne(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5))).list();
        return jdbc.sql("""
                select n.notice_id, n.notice_no, n.dossier_id, d.dossier_no, n.status, n.payee, n.payer_niu, n.payer_name, n.total_amount, n.currency,
                       n.issued_at, n.due_at, n.paid_at, n.payment_reference, n.bank_code, n.channel
                  from wfe.payment_notice n join wfe.dossier d on d.dossier_id = n.dossier_id where n.notice_no = :n
                """).param("n", numero).query((rs, i) -> new Avis(rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBigDecimal(9), rs.getString(10),
                rs.getObject(11, OffsetDateTime.class), rs.getObject(12, OffsetDateTime.class), rs.getObject(13, OffsetDateTime.class), rs.getString(14),
                rs.getString(15), rs.getString(16), lignes)).optional().orElseThrow(() -> EservicesException.introuvable("Avis de paiement introuvable : " + numero));
    }

    public List<Avis> duDossier(UUID dossier) {
        return jdbc.sql("select notice_no from wfe.payment_notice where dossier_id = :d order by notice_id").param("d", dossier).query(String.class).list()
                .stream().map(this::avis).toList();
    }

    /** Annulation des avis non payés d'un dossier (délai de paiement dépassé). */
    public void annuler(UUID dossier) {
        jdbc.sql("update wfe.payment_notice set status = 'CANCELLED' where dossier_id = :d and status in ('ISSUED', 'OVERDUE')").param("d", dossier).update();
    }

    /** Encaissements par bénéficiaire (avis payés), filtrables par procédure et période. */
    public Map<String, Object> encaissements(String procedure, String du, String au) {
        List<Map<String, Object>> parBeneficiaire = jdbc.sql("""
                select l.beneficiary, count(distinct n.notice_id), sum(l.amount), max(n.currency)
                  from wfe.payment_line l join wfe.payment_notice n on n.notice_id = l.notice_id join wfe.dossier d on d.dossier_id = n.dossier_id
                 where n.status = 'PAID' and (cast(:p as text) is null or d.procedure_code = :p)
                   and (cast(:du as date) is null or n.paid_at >= cast(:du as date)) and (cast(:au as date) is null or n.paid_at < cast(:au as date) + 1)
                 group by l.beneficiary order by sum(l.amount) desc
                """).param("p", vide(procedure)).param("du", vide(du)).param("au", vide(au)).query((rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("beneficiary", rs.getString(1));
            m.put("notices", rs.getLong(2));
            m.put("amount", rs.getBigDecimal(3));
            m.put("currency", rs.getString(4));
            return m;
        }).list();
        Map<String, Object> totaux = jdbc.sql("""
                select count(*) filter (where n.status = 'PAID'), coalesce(sum(n.total_amount) filter (where n.status = 'PAID'), 0),
                       count(*) filter (where n.status = 'ISSUED'), coalesce(sum(n.total_amount) filter (where n.status = 'ISSUED'), 0)
                  from wfe.payment_notice n join wfe.dossier d on d.dossier_id = n.dossier_id where (cast(:p as text) is null or d.procedure_code = :p)
                """).param("p", vide(procedure)).query((rs, i) -> Map.<String, Object>of("paidCount", rs.getLong(1), "paidAmount", rs.getBigDecimal(2),
                "pendingCount", rs.getLong(3), "pendingAmount", rs.getBigDecimal(4))).single();
        return Map.of("byBeneficiary", parBeneficiaire, "totals", totaux);
    }

    /** Relevé CSV des encaissements d'un bénéficiaire, émis comme message REPORT du catalogue (flux de reversement). */
    @Transactional
    public MessageService.Entete releve(String procedure, String beneficiaire) {
        journal.contexte();
        DeploiementService.Deploiement dep = deploiements.actif(procedure);
        Paquet p = deploiements.paquet(dep.id());
        Paquet.MessageDef def = p.premierMessage("REPORT").orElseThrow(() -> EservicesException.introuvable("Aucun message de relevé (REPORT) dans le catalogue de " + procedure + "."));
        List<Map<String, Object>> lignes = jdbc.sql("""
                select d.dossier_no, n.notice_no, l.beneficiary, l.amount, n.currency, n.paid_at
                  from wfe.payment_line l join wfe.payment_notice n on n.notice_id = l.notice_id join wfe.dossier d on d.dossier_id = n.dossier_id
                 where n.status = 'PAID' and d.procedure_code = :p and (cast(:b as text) is null or l.beneficiary = :b) order by n.paid_at
                """).param("p", procedure).param("b", vide(beneficiaire)).query((rs, i) -> {
            Map<String, Object> m = new HashMap<>();
            m.put("dossierNo", rs.getString(1));
            m.put("paymentNoticeNumber", rs.getString(2));
            m.put("beneficiary", rs.getString(3));
            m.put("amount", rs.getBigDecimal(4).toPlainString());
            m.put("currency", rs.getString(5));
            m.put("paidAt", rs.getObject(6, OffsetDateTime.class).toString());
            return m;
        }).list();
        Map<String, Object> ctx = new HashMap<>();
        for (String cle : List.of("dossierNo", "paymentNoticeNumber", "beneficiary", "amount", "currency", "paidAt"))
            ctx.put(cle, lignes.stream().map(l -> l.get(cle)).toList());
        UUID id = messages.emettreRapport(p, def.code(), ctx, "Relevé des encaissements" + (beneficiaire == null ? "" : " — " + beneficiaire));
        return messages.entete(id);
    }

    private static String vide(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Délai ISO 8601 du paquet : durée (PT48H) ou période en jours (P30D). */
    static java.time.Duration duree(String iso) {
        try {
            return java.time.Duration.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            return java.time.Duration.ofDays(java.time.Period.parse(iso).getDays());
        }
    }
}
