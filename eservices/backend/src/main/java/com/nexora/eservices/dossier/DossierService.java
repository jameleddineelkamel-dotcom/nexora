package com.nexora.eservices.dossier;

import com.nexora.eservices.audit.Journal;
import com.nexora.eservices.deploiement.DeploiementService;
import com.nexora.eservices.deploiement.Paquet;
import com.nexora.eservices.registre.Services;
import com.nexora.eservices.security.Habilitations;
import com.nexora.eservices.security.SecurityConfig;
import com.nexora.eservices.web.EservicesException;
import com.nexora.eservices.web.Json;
import org.operaton.bpm.engine.RuntimeService;
import org.operaton.bpm.engine.runtime.ProcessInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.Year;
import java.util.*;

/**
 * Cycle d'une demande côté eFORCE (UC-01 à UC-03) : création d'un brouillon pré-rempli depuis le registre des
 * opérateurs (NIU), reprise du dossier source pour un renouvellement ou un amendement, pièces jointes, estimation des
 * frais, dépôt (CUR, critères, démarrage du workflow BPMN). Visibilité : l'opérateur voit ses dossiers, l'agent ceux qui
 * lui sont cotés (RG2 UC-04), le superviseur tous.
 */
@Service
public class DossierService {

    public record NouvelleDemande(String procedure, String requestType, String nature, String niu, String sourceDossierNo) {}

    private final DossierRepository repo;
    private final DeploiementService deploiements;
    private final Validation validation;
    private final CurService cur;
    private final Services services;
    private final RuntimeService moteur;
    private final Journal journal;
    private final JdbcClient jdbc;
    private final Json json;
    private final String tenant;

    public DossierService(DossierRepository repo, DeploiementService deploiements, Validation validation, CurService cur, Services services,
                          RuntimeService moteur, Journal journal, JdbcClient jdbc, Json json, @Value("${nexora.tenant}") String tenant) {
        this.repo = repo;
        this.deploiements = deploiements;
        this.validation = validation;
        this.cur = cur;
        this.services = services;
        this.moteur = moteur;
        this.journal = journal;
        this.jdbc = jdbc;
        this.json = json;
        this.tenant = tenant;
    }

    // ------------------------------------------------------------------ accès
    public Dossier lire(UUID id) {
        Dossier d = repo.parId(id).orElseThrow(() -> EservicesException.introuvable("Dossier introuvable."));
        if (!peutVoir(d)) throw EservicesException.interdit("Ce dossier ne vous est pas accessible.");
        return d;
    }

    public boolean peutVoir(Dossier d) {
        if (Habilitations.utilisateurA(SecurityConfig.SUPERVISER) || Habilitations.utilisateurA(SecurityConfig.ADMINISTRER)) return true;
        String moi = Habilitations.acteur();
        if (moi.equals(d.submittedBy()) || moi.equals(d.assignedAgent()) || moi.equals(d.signatory())) return true;
        return d.applicantNiu() != null && Habilitations.utilisateurA(SecurityConfig.DEPOSER)
                && services.mesParties().stream().anyMatch(p -> p.niu().equals(d.applicantNiu()));
    }

    private Dossier proprietaire(UUID id) {
        Dossier d = repo.parId(id).orElseThrow(() -> EservicesException.introuvable("Dossier introuvable."));
        if (!Habilitations.acteur().equals(d.submittedBy())) throw EservicesException.interdit("Seul l'auteur de la demande peut la modifier.");
        return d;
    }

    public Paquet paquet(Dossier d) {
        return deploiements.paquet(d.deploymentId());
    }

    public Set<String> lieuxActifs() {
        return new HashSet<>(jdbc.sql("select location_code from wfe.processing_location where is_active").query(String.class).list());
    }

    // ------------------------------------------------------------------ brouillon
    @Transactional
    public Dossier creer(NouvelleDemande n) {
        if (!Habilitations.utilisateurA(SecurityConfig.DEPOSER)) throw EservicesException.interdit("Le droit « eservices.deposer » est nécessaire.");
        journal.contexte();
        DeploiementService.Deploiement dep = deploiements.actif(n.procedure());
        Paquet p = deploiements.paquet(dep.id());
        Paquet.RequestType type = p.typeDemande(n.requestType()).orElseThrow(() -> EservicesException.invalide("Type de demande inconnu : " + n.requestType()));
        String nature = n.nature() == null || n.nature().isBlank() ? "INITIAL" : n.nature();
        if (!type.natures().contains(nature))
            throw EservicesException.invalide("La nature « " + nature + " » n'est pas autorisée pour « " + type.labelFr() + " ».");

        // Demandeur : l'opérateur pour lui-même (titulaire), le CDA pour lui ou pour un tiers (RG4, RG5)
        boolean mandataire = Habilitations.utilisateurA(SecurityConfig.MANDATAIRE);
        List<Services.Partie> miennes = services.mesParties();
        String niu = n.niu() == null || n.niu().isBlank() ? null : n.niu().strip().toUpperCase(Locale.ROOT);
        if (niu == null) {
            List<Services.Partie> titulaires = miennes.stream().filter(x -> "TITULAIRE".equals(x.relation())).toList();
            if (titulaires.size() == 1) niu = titulaires.getFirst().niu();
            else if (titulaires.isEmpty() && !mandataire)
                throw EservicesException.invalide("Votre compte n'est rattaché à aucun opérateur du registre : faites-vous enrôler au guichet unique.");
            else throw EservicesException.invalide("Renseignez le NIU de l'opérateur concerné.");
        }
        final String niuFinal = niu;
        boolean titulaire = miennes.stream().anyMatch(x -> x.niu().equals(niuFinal) && "TITULAIRE".equals(x.relation()));
        if (!titulaire && !mandataire)
            throw EservicesException.interdit("Un importateur ou exportateur ne peut initier une demande que pour lui-même (RG4).");
        Map<String, Object> partie = services.partie(niu).orElseThrow(() -> EservicesException.introuvable(
                "NIU inconnu du registre des opérateurs : " + niuFinal + ". L'opérateur doit être enrôlé au guichet unique."));

        Map<String, Object> donnees = new LinkedHashMap<>();
        Map<String, Object> precedentes = null;
        Dossier source = null;
        if (!nature.equals("INITIAL")) {
            source = source(p, n, niu, type.code());
            donnees.putAll(source.formData());
            if (nature.equals("AMENDMENT")) precedentes = new LinkedHashMap<>(source.formData());
        }
        // Pré-remplissage depuis le registre (les données à jour de l'opérateur priment, sauf pour un amendement)
        for (Paquet.Field f : p.fields()) {
            if (f.prefill() == null || f.prefill().isBlank()) continue;
            Object v = partie.get(f.prefill());
            if (v != null && !String.valueOf(v).isBlank() && (precedentes == null || !donnees.containsKey(f.code()))) donnees.put(f.code(), v);
        }
        String champNiu = p.reglage("applicantField", "taxIdentifier");
        donnees.put(champNiu, niu);
        if (source != null) donnees.put(p.reglage("previousField", "previousRequestNumber"), source.dossierNo());
        if (nature.equals("RENEWAL")) donnees.put(p.reglage("renewalYearField", "renewYear"), String.valueOf(Year.now().getValue() + 1));
        String nom = Objects.toString(partie.get("legalName"), null);
        UUID id = repo.creer(dep.id(), p.procedure().code(), p.versionNo(), type.code(), nature, source == null ? null : source.dossierNo(), niu, nom,
                Habilitations.acteur(), !titulaire, donnees, precedentes, tenant);
        repo.statut(id, "BROUILLON", false, null, Habilitations.acteur(), "Création du brouillon");

        // Pièces déjà disponibles : celles de l'enrôlement, puis celles du dossier source (UC-01, UC-03)
        Set<String> codes = new HashSet<>();
        p.documents().forEach(d -> codes.add(d.code()));
        for (Services.PieceRegistre pr : services.piecesPartie(niu)) {
            if (!codes.contains(pr.docCode())) continue;
            byte[] contenu = services.contenuPiece(niu, pr.id());
            repo.ajouterPiece(id, pr.docCode(), pr.fileName(), pr.mimeType(), contenu, sha256(contenu), "REGISTRY", Habilitations.acteur());
        }
        if (source != null) {
            Set<String> presentes = new HashSet<>();
            repo.pieces(id).forEach(x -> presentes.add(x.docCode()));
            for (DossierRepository.Piece x : repo.pieces(source.id())) {
                if (presentes.contains(x.docCode())) continue;
                var c = repo.contenuPiece(source.id(), x.id()).orElseThrow();
                repo.ajouterPiece(id, x.docCode(), x.fileName(), x.mimeType(), c.getValue(), x.sha256(), "SYSTEM", Habilitations.acteur());
            }
        }
        journal.tracer("DOSSIER_BROUILLON", id, null, Map.of("procedure", p.procedure().code(), "type", type.code(), "nature", nature, "niu", niu,
                "onBehalf", !titulaire));
        return repo.parId(id).orElseThrow();
    }

    /** Dossier source d'un renouvellement ou d'un amendement : le dernier dossier validé du demandeur pour ce type. */
    private Dossier source(Paquet p, NouvelleDemande n, String niu, String type) {
        if (n.sourceDossierNo() != null && !n.sourceDossierNo().isBlank()) {
            Dossier s = repo.parNumero(n.sourceDossierNo().strip()).orElseThrow(() -> EservicesException.introuvable("Dossier source introuvable : " + n.sourceDossierNo()));
            if (!niu.equals(s.applicantNiu()) || !s.procedureCode().equals(p.procedure().code()) || !s.requestType().equals(type))
                throw EservicesException.invalide("Le dossier source ne correspond pas à cet opérateur ou à ce type de demande.");
            if (!"VALIDE".equals(s.status())) throw EservicesException.invalide("Le dossier source n'est pas validé.");
            return s;
        }
        return repo.duDemandeur(niu, p.procedure().code(), new UUID(0, 0)).stream()
                .filter(x -> x.requestType().equals(type) && "VALIDE".equals(x.status()))
                .max(Comparator.comparing(Dossier::submittedAt)).orElseThrow(() -> EservicesException.invalide(
                        "Aucune inscription validée pour ce type de demande : un renouvellement ou un amendement exige une inscription existante."));
    }

    @Transactional
    public Dossier modifier(UUID id, Map<String, Object> donnees) {
        Dossier d = proprietaire(id);
        boolean complement = "COMPLEMENT_DEMANDE".equals(d.status());
        if (!"DRAFT".equals(d.lifecycle()) && !complement)
            throw EservicesException.conflit("La demande est déposée : elle n'est modifiable que sur demande de complément.");
        journal.contexte();
        Paquet p = paquet(d);
        Map<String, Object> fusion = new LinkedHashMap<>(d.formData());
        Set<String> verrouilles = new HashSet<>();
        if ("AMENDMENT".equals(d.requestNature()))
            p.fields().stream().filter(f -> !f.amendable()).forEach(f -> verrouilles.add(f.code()));
        verrouilles.add(p.reglage("applicantField", "taxIdentifier"));
        verrouilles.add(p.reglage("previousField", "previousRequestNumber"));
        donnees.forEach((k, v) -> { if (!verrouilles.contains(k)) fusion.put(k, v); });
        repo.donnees(id, fusion, null, null);
        return repo.parId(id).orElseThrow();
    }

    // ------------------------------------------------------------------ pièces
    @Transactional
    public DossierRepository.Piece ajouterPiece(UUID id, String code, String nom, String type, byte[] contenu) {
        Dossier d = proprietaire(id);
        if (!"DRAFT".equals(d.lifecycle()) && !"COMPLEMENT_DEMANDE".equals(d.status()))
            throw EservicesException.conflit("Les pièces ne sont modifiables qu'avant le dépôt ou sur demande de complément.");
        Paquet p = paquet(d);
        if (p.documents().stream().noneMatch(x -> x.code().equals(code))) throw EservicesException.invalide("Pièce inconnue pour cette procédure : " + code);
        if (contenu.length == 0) throw EservicesException.invalide("Fichier vide.");
        journal.contexte();
        long pid = repo.ajouterPiece(id, code, nom, type == null ? "application/octet-stream" : type, contenu, sha256(contenu), "UPLOAD", Habilitations.acteur());
        journal.tracer("PIECE_AJOUTEE", id, d.dossierNo(), Map.of("doc", code, "file", nom, "size", contenu.length));
        return repo.pieces(id).stream().filter(x -> x.id() == pid).findFirst().orElseThrow();
    }

    @Transactional
    public void retirerPiece(UUID id, long piece) {
        Dossier d = proprietaire(id);
        if (!"DRAFT".equals(d.lifecycle()) && !"COMPLEMENT_DEMANDE".equals(d.status()))
            throw EservicesException.conflit("Les pièces ne sont modifiables qu'avant le dépôt ou sur demande de complément.");
        repo.retirerPiece(id, piece);
    }

    // ------------------------------------------------------------------ estimation et dépôt
    public Validation.Bilan bilan(Dossier d) {
        Paquet p = paquet(d);
        Set<String> pieces = new HashSet<>();
        repo.pieces(d.id()).forEach(x -> pieces.add(x.docCode()));
        List<Validation.Erreur> generiques = validation.formulaire(p, d.requestType(), d.requestNature(), d.formData(), pieces, lieuxActifs());
        boolean titulaire = !d.onBehalf();
        boolean mandataire = d.onBehalf() || Habilitations.utilisateurA(SecurityConfig.MANDATAIRE);
        var demande = Validation.demande(p, d, pieces, mandataire, titulaire);
        var antecedents = Validation.antecedents(repo.duDemandeur(d.applicantNiu(), d.procedureCode(), d.id()), expirations(d.applicantNiu()),
                p.reglage("renewalYearField", "renewYear"));
        return validation.evaluer(p, d.deploymentId(), demande, antecedents, generiques);
    }

    /** Date d'expiration du livrable actif de chaque dossier du demandeur. */
    private Map<String, LocalDate> expirations(String niu) {
        Map<String, LocalDate> m = new HashMap<>();
        if (niu == null) return m;
        jdbc.sql("""
                select d.dossier_no, c.valid_until from wfe.issued_document c join wfe.dossier d on d.dossier_id = c.dossier_id
                 where c.holder_niu = :n and c.status = 'ACTIVE'
                """).param("n", niu).query((rs, i) -> m.put(rs.getString(1), rs.getObject(2, LocalDate.class))).list();
        // Un amendement prolonge le titre du dossier source : le dossier source reste « valide » tant que le titre amendé l'est
        jdbc.sql("""
                select d.parent_dossier_no, c.valid_until from wfe.issued_document c join wfe.dossier d on d.dossier_id = c.dossier_id
                 where c.holder_niu = :n and c.status = 'ACTIVE' and d.request_nature = 'AMENDMENT' and d.parent_dossier_no is not null
                """).param("n", niu).query((rs, i) -> m.merge(rs.getString(1), rs.getObject(2, LocalDate.class), (a, b) -> a.isAfter(b) ? a : b)).list();
        return m;
    }

    @Transactional
    public Dossier deposer(UUID id) {
        Dossier d = proprietaire(id);
        if (!"DRAFT".equals(d.lifecycle())) throw EservicesException.conflit("La demande est déjà déposée (" + d.dossierNo() + ").");
        journal.contexte();
        Paquet p = paquet(d);
        Validation.Bilan b = bilan(d);
        if (!b.valide()) throw EservicesException.invalide(b.erreurs().stream().map(e -> e.message()).distinct().toList());
        Paquet.RequestType type = p.typeDemande(d.requestType()).orElseThrow();
        String numero = cur.prochain(p.reglage("country", "CM"), type.curCode(), p.reglage("timezone", "Africa/Douala"));
        String lieu = Objects.toString(d.formData().get(p.reglage("processingLocationField", "processingLocation")), null);
        List<Map<String, Object>> frais = b.frais().stream().map(f -> Map.<String, Object>of("code", f.code(), "beneficiary", f.beneficiaire(),
                "label", f.libelle(), "amount", f.montant())).toList();
        repo.deposer(id, numero, lieu, frais, b.total(), p.reglage("currency", "XAF"), null);
        repo.criteres(id, criteres(p, d.formData()));
        Map<String, Object> variables = new HashMap<>();
        variables.put("dossierId", id.toString());
        variables.put("dossierNo", numero);
        variables.put("procedure", p.procedure().code());
        variables.put("typeDemande", d.requestType());
        variables.put("nature", d.requestNature());
        variables.put("demandeur", d.submittedBy());
        variables.put("delaiPaiement", p.reglage("paymentDelay", "P30D"));
        variables.put("deploymentId", d.deploymentId());
        DeploiementService.Deploiement dep = deploiements.deploiement(d.deploymentId());
        journal.tracer("DOSSIER_DEPOSE", id, numero, Map.of("type", d.requestType(), "nature", d.requestNature(), "total", b.total(),
                "rules", b.regles()));
        ProcessInstance pi = moteur.createProcessInstanceById(dep.processDefinitionId()).businessKey(numero).setVariables(variables).execute();
        repo.instance(id, pi.getId());
        return repo.parId(id).orElseThrow();
    }

    /** Critères de recherche : champs déclarés « recherchables » (y compris dans les groupes répétitifs). */
    static Map<String, List<String>> criteres(Paquet p, Map<String, Object> donnees) {
        Map<String, List<String>> c = new LinkedHashMap<>();
        for (Paquet.Field f : p.fields()) {
            if (!f.searchable()) continue;
            Optional<Paquet.Section> s = p.section(f.sectionCode());
            List<String> valeurs = new ArrayList<>();
            if (s.isPresent() && s.get().repeating()) {
                if (donnees.get(s.get().code()) instanceof List<?> l)
                    for (Object o : l) if (o instanceof Map<?, ?> m && m.get(f.code()) != null) valeurs.add(String.valueOf(m.get(f.code())));
            } else if (donnees.get(f.code()) != null) valeurs.add(String.valueOf(donnees.get(f.code())));
            if (!valeurs.isEmpty()) c.put(f.code(), valeurs);
        }
        return c;
    }

    // ------------------------------------------------------------------ listes
    public record Ligne(UUID id, String dossierNo, String procedureCode, String requestType, String requestNature, String applicantNiu,
                        String applicantName, String submittedBy, String status, String lifecycle, String processingLocation,
                        String assignedAgent, String signatory, java.math.BigDecimal totalAmount, java.time.OffsetDateTime createdAt,
                        java.time.OffsetDateTime submittedAt, java.time.OffsetDateTime slaDueAt) {}

    /**
     * Dossiers visibles : « mine » (eFORCE : déposés par moi ou pour mes NIU), « assigned » (eGOV : cotés à moi), « all »
     * (supervision). Filtres : procédure, statut, texte (CUR, NIU, raison sociale) et critère indexé « code=valeur ».
     */
    public List<Ligne> liste(String portee, String procedure, String statut, String texte, String critere, int limite) {
        StringBuilder sql = new StringBuilder("""
                select dossier_id, dossier_no, procedure_code, request_type, request_nature, applicant_niu, applicant_name, submitted_by, status,
                       lifecycle::text, processing_location, assigned_agent, signatory, total_amount, created_at, submitted_at, sla_due_at
                  from wfe.dossier where true
                """);
        Map<String, Object> params = new HashMap<>();
        String moi = Habilitations.acteur();
        switch (portee == null ? "mine" : portee) {
            case "all" -> {
                if (!Habilitations.utilisateurA(SecurityConfig.SUPERVISER) && !Habilitations.utilisateurA(SecurityConfig.ADMINISTRER))
                    throw EservicesException.interdit("La liste de tous les dossiers est réservée à la supervision.");
                sql.append(" and dossier_no is not null");
            }
            case "assigned" -> {
                sql.append(" and (assigned_agent = :moi or signatory = :moi)");
                params.put("moi", moi);
            }
            default -> {
                List<String> nius = services.mesParties().stream().map(Services.Partie::niu).toList();
                sql.append(" and (submitted_by = :moi or applicant_niu = any(cast(:nius as text[])))");
                params.put("moi", moi);
                params.put("nius", nius.toArray(String[]::new));
            }
        }
        if (procedure != null && !procedure.isBlank()) { sql.append(" and procedure_code = :p"); params.put("p", procedure); }
        if (statut != null && !statut.isBlank()) { sql.append(" and status = :s"); params.put("s", statut); }
        if (texte != null && !texte.isBlank()) {
            sql.append(" and (dossier_no ilike :t or applicant_niu ilike :t or applicant_name ilike :t)");
            params.put("t", "%" + texte.strip() + "%");
        }
        if (critere != null && critere.contains("=")) {
            String[] kv = critere.split("=", 2);
            sql.append(" and search_criteria @> cast(:c as jsonb)");
            params.put("c", json.ecrire(Map.of(kv[0].strip(), List.of(kv[1].strip()))));
        }
        sql.append(" order by coalesce(submitted_at, created_at) desc limit :l");
        params.put("l", Math.min(Math.max(limite, 1), 500));
        var q = jdbc.sql(sql.toString());
        for (var e : params.entrySet()) q = q.param(e.getKey(), e.getValue());
        return q.query((rs, i) -> new Ligne(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getString(13), rs.getBigDecimal(14), rs.getObject(15, java.time.OffsetDateTime.class), rs.getObject(16, java.time.OffsetDateTime.class),
                rs.getObject(17, java.time.OffsetDateTime.class))).list();
    }

    /** Titres en cours de validité de l'opérateur (amendement : choix de l'attestation à modifier, UC-03). */
    public List<Map<String, Object>> titresValides(String procedure) {
        List<String> nius = services.mesParties().stream().map(Services.Partie::niu).toList();
        boolean mandataire = Habilitations.utilisateurA(SecurityConfig.MANDATAIRE);
        return jdbc.sql("""
                select d.dossier_no, d.request_type, d.applicant_niu, d.applicant_name, c.doc_no, c.base_doc_no, c.valid_until
                  from wfe.issued_document c join wfe.dossier d on d.dossier_id = c.dossier_id
                 where c.status = 'ACTIVE' and c.valid_until >= current_date and d.procedure_code = :p
                   and (:m or d.applicant_niu = any(cast(:n as text[])) or d.submitted_by = :u)
                 order by d.applicant_name, d.request_type
                """).param("p", procedure).param("m", mandataire).param("n", nius.toArray(String[]::new)).param("u", Habilitations.acteur())
                .query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("dossierNo", rs.getString(1));
                    m.put("requestType", rs.getString(2));
                    m.put("applicantNiu", rs.getString(3));
                    m.put("applicantName", rs.getString(4));
                    m.put("docNo", rs.getString(5));
                    m.put("baseDocNo", rs.getString(6));
                    m.put("validUntil", rs.getObject(7, LocalDate.class));
                    return m;
                }).list();
    }

    public List<DossierRepository.Piece> pieces(UUID id) {
        return repo.pieces(id);
    }

    public List<DossierRepository.Statut> historique(UUID id) {
        return repo.historique(id);
    }

    public Optional<Map.Entry<DossierRepository.Piece, byte[]>> contenuPiece(UUID id, long piece) {
        lire(id);
        return repo.contenuPiece(id, piece);
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
