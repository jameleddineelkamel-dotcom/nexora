package com.nexora.registration.registre;

import com.nexora.registration.security.Habilitations;
import com.nexora.registration.security.SecurityConfig;
import com.nexora.registration.web.Json;
import com.nexora.registration.web.RegistrationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Registre des opérateurs économiques. Les attributs de la fiche portent les noms utilisés par le pré-remplissage des
 * formulaires de procédures (champ « prefill » du studio) : un formulaire publié se pré-remplit sans code spécifique.
 */
@Service
public class RegistreService {

    /** Attribut de la fiche : nom d'API, colonne, type (T texte, N nombre, D date). */
    record Attribut(String cle, String colonne, char type, String libelle) {}

    public static final List<Attribut> ATTRIBUTS = List.of(
            new Attribut("legalName", "legal_name", 'T', "Nom / raison sociale"), new Attribut("partyTypeCode", "party_type_code", 'T', "Personnalité juridique"),
            new Attribut("nationality", "nationality", 'T', "Étranger / national"), new Attribut("nationalIdentifier", "national_identifier", 'T', "CNI / passeport"),
            new Attribut("contactPersonName", "contact_person_name", 'T', "Directeur général"), new Attribut("birthCountry", "birth_country", 'T', "Pays de naissance"),
            new Attribut("annualTurnover", "annual_turnover", 'N', "Chiffre d'affaires annuel"), new Attribut("street", "street", 'T', "Adresse"),
            new Attribut("addressLine2", "address_line2", 'T', "Complément d'adresse"), new Attribut("poBox", "po_box", 'T', "Boîte postale"),
            new Attribut("city", "city", 'T', "Ville"), new Attribut("countryCode", "country_code", 'T', "Pays"), new Attribut("email", "email", 'T', "Courriel"),
            new Attribut("phone", "phone", 'T', "Téléphone"), new Attribut("mobile", "mobile", 'T', "Mobile"), new Attribut("fax", "fax", 'T', "Fax"),
            new Attribut("taxCardFrom", "tax_card_from", 'D', "Carte de contribuable — délivrance"), new Attribut("taxCardTo", "tax_card_to", 'D', "Carte de contribuable — expiration"),
            new Attribut("registrationNumber", "registration_number", 'T', "Patente"), new Attribut("activitySectorCode", "activity_sector_code", 'T', "Activité principale"),
            new Attribut("industryClassificationCode", "industry_classification_code", 'T', "Activité secondaire"),
            new Attribut("taxRegimeCode", "tax_regime_code", 'T', "Régime d'imposition"), new Attribut("rccm", "rccm", 'T', "RCCM"),
            new Attribut("rccmDate", "rccm_date", 'D', "Date RCCM"), new Attribut("rccmPlace", "rccm_place", 'T', "Lieu RCCM"));

    public record Lien(String niu, String legalName, String relation) {}

    public record Compte(String niu, String username, String relation, boolean active, OffsetDateTime createdAt, String createdBy) {}

    public record Piece(long id, String docCode, String label, String fileName, String mimeType, long sizeBytes, String sha256, LocalDate validUntil,
                        String uploadedBy, OffsetDateTime uploadedAt) {}

    private final JdbcClient jdbc;
    private final Json json;

    public RegistreService(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ accès
    public List<Lien> mesOperateurs() {
        return jdbc.sql("""
                select p.niu, p.legal_name, u.relation from registre.party_user u join registre.party p on p.niu = u.niu
                 where u.username = :u and u.is_active and p.status = 'ACTIVE' order by u.relation desc, p.legal_name
                """).param("u", Habilitations.acteur()).query((rs, i) -> new Lien(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }

    private boolean rattache(String niu) {
        return mesOperateurs().stream().anyMatch(l -> l.niu().equals(niu));
    }

    private void lecture(String niu) {
        if (!Habilitations.utilisateurA(SecurityConfig.LIRE) && !Habilitations.utilisateurA(SecurityConfig.GERER) && !rattache(niu))
            throw RegistrationException.interdit("Cette fiche ne vous est pas accessible.");
    }

    private void gestion() {
        if (!Habilitations.utilisateurA(SecurityConfig.GERER)) throw RegistrationException.interdit("Le droit « registration.gerer » est nécessaire.");
    }

    // ------------------------------------------------------------------ fiches
    private static String colonnes() {
        StringBuilder s = new StringBuilder("niu, status, enrolled_at, updated_at, updated_by");
        ATTRIBUTS.forEach(a -> s.append(", ").append(a.colonne()));
        return s.toString();
    }

    private Map<String, Object> fiche(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("niu", rs.getString("niu"));
        for (Attribut a : ATTRIBUTS) {
            Object v = switch (a.type()) {
                case 'N' -> rs.getBigDecimal(a.colonne());
                case 'D' -> rs.getObject(a.colonne(), LocalDate.class);
                default -> rs.getString(a.colonne());
            };
            m.put(a.cle(), v);
        }
        m.put("status", rs.getString("status"));
        m.put("enrolledAt", rs.getObject("enrolled_at", OffsetDateTime.class));
        m.put("updatedAt", rs.getObject("updated_at", OffsetDateTime.class));
        m.put("updatedBy", rs.getString("updated_by"));
        return m;
    }

    public List<Map<String, Object>> rechercher(String q, int limite) {
        if (!Habilitations.utilisateurA(SecurityConfig.LIRE) && !Habilitations.utilisateurA(SecurityConfig.GERER))
            throw RegistrationException.interdit("Le droit « registration.lire » est nécessaire.");
        String motif = q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
        return jdbc.sql("select " + colonnes() + " from registre.party where (cast(:q as text) is null or lower(niu) like :q or lower(legal_name) like :q "
                        + "or lower(coalesce(rccm, '')) like :q or lower(coalesce(city, '')) like :q) order by legal_name limit :l")
                .param("q", motif).param("l", Math.min(Math.max(limite, 1), 500)).query((rs, i) -> fiche(rs)).list();
    }

    public Map<String, Object> lire(String niu) {
        lecture(niu);
        return jdbc.sql("select " + colonnes() + " from registre.party where niu = :n").param("n", niu).query((rs, i) -> fiche(rs)).optional()
                .orElseThrow(() -> RegistrationException.introuvable("Opérateur inconnu : " + niu));
    }

    @Transactional
    public Map<String, Object> enroler(Map<String, Object> corps) {
        gestion();
        String niu = Objects.toString(corps.get("niu"), "").strip().toUpperCase(Locale.ROOT);
        List<String> erreurs = controler(niu, corps, true);
        if (!erreurs.isEmpty()) throw RegistrationException.invalide(erreurs);
        if (jdbc.sql("select count(*) from registre.party where niu = :n").param("n", niu).query(Long.class).single() > 0)
            throw RegistrationException.conflit("Un opérateur existe déjà avec le NIU " + niu + ".");
        contexte("ENROLEMENT");
        StringBuilder c = new StringBuilder("niu, updated_by"), v = new StringBuilder(":niu, :u");
        for (Attribut a : ATTRIBUTS) if (corps.containsKey(a.cle())) { c.append(", ").append(a.colonne()); v.append(", ").append(":").append(a.cle()); }
        var q = jdbc.sql("insert into registre.party (" + c + ") values (" + v + ")").param("niu", niu).param("u", Habilitations.acteur());
        for (Attribut a : ATTRIBUTS) if (corps.containsKey(a.cle())) q = q.param(a.cle(), valeur(a, corps.get(a.cle())));
        q.update();
        return lire(niu);
    }

    @Transactional
    public Map<String, Object> modifier(String niu, Map<String, Object> corps) {
        gestion();
        lire(niu);
        List<String> erreurs = controler(niu, corps, false);
        if (!erreurs.isEmpty()) throw RegistrationException.invalide(erreurs);
        contexte("MODIFICATION");
        StringBuilder s = new StringBuilder("update registre.party set updated_at = now(), updated_by = :u, version = version + 1");
        for (Attribut a : ATTRIBUTS) if (corps.containsKey(a.cle())) s.append(", ").append(a.colonne()).append(" = :").append(a.cle());
        if (corps.containsKey("status")) s.append(", status = :status");
        var q = jdbc.sql(s + " where niu = :niu").param("niu", niu).param("u", Habilitations.acteur());
        for (Attribut a : ATTRIBUTS) if (corps.containsKey(a.cle())) q = q.param(a.cle(), valeur(a, corps.get(a.cle())));
        if (corps.containsKey("status")) q = q.param("status", String.valueOf(corps.get("status")));
        q.update();
        return lire(niu);
    }

    /**
     * Complétion après une procédure (RG2 UC-05 FIMEX) : seuls les attributs vides de la fiche sont renseignés, jamais
     * une valeur existante n'est écrasée.
     */
    @Transactional
    public Map<String, Object> completer(String niu, Map<String, Object> corps) {
        if (!Habilitations.utilisateurA(SecurityConfig.COMPLETER) && !Habilitations.utilisateurA(SecurityConfig.GERER))
            throw RegistrationException.interdit("Le droit « registration.completer » est nécessaire.");
        Map<String, Object> actuelle = jdbc.sql("select " + colonnes() + " from registre.party where niu = :n").param("n", niu).query((rs, i) -> fiche(rs))
                .optional().orElseThrow(() -> RegistrationException.introuvable("Opérateur inconnu : " + niu));
        List<String> completes = new ArrayList<>();
        contexte("COMPLETION");
        for (Attribut a : ATTRIBUTS) {
            Object nouveau = corps.get(a.cle());
            Object existant = actuelle.get(a.cle());
            if (nouveau == null || String.valueOf(nouveau).isBlank() || (existant != null && !String.valueOf(existant).isBlank())) continue;
            Object v;
            try {
                v = valeur(a, nouveau);
            } catch (RuntimeException e) {
                continue;
            }
            jdbc.sql("update registre.party set " + a.colonne() + " = :v, updated_at = now(), updated_by = :u, version = version + 1 where niu = :n")
                    .param("v", v).param("u", Habilitations.acteur()).param("n", niu).update();
            completes.add(a.cle());
        }
        return Map.of("niu", niu, "completed", completes);
    }

    private static List<String> controler(String niu, Map<String, Object> c, boolean creation) {
        List<String> e = new ArrayList<>();
        if (!niu.matches("^[A-Z0-9]{6,35}$")) e.add("NIU invalide (6 à 35 lettres majuscules ou chiffres).");
        if (creation || c.containsKey("legalName"))
            if (c.get("legalName") == null || String.valueOf(c.get("legalName")).isBlank()) e.add("La raison sociale est obligatoire.");
        if (creation || c.containsKey("partyTypeCode"))
            if (!List.of("PP", "PM", "SUC", "GIE").contains(Objects.toString(c.get("partyTypeCode"), ""))) e.add("Personnalité juridique attendue : PP, PM, SUC ou GIE.");
        if (c.containsKey("nationality") && !List.of("C", "E").contains(Objects.toString(c.get("nationality"), ""))) e.add("Nationalité attendue : C ou E.");
        Object m = c.get("email");
        if (m != null && !String.valueOf(m).isBlank() && !String.valueOf(m).matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) e.add("Adresse électronique invalide.");
        return e;
    }

    private static Object valeur(Attribut a, Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        String s = String.valueOf(v).strip();
        return switch (a.type()) {
            case 'N' -> new BigDecimal(s.replace(" ", "").replace(',', '.'));
            case 'D' -> LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
            default -> s;
        };
    }

    private void contexte(String operation) {
        jdbc.sql("""
                select set_config('nexora.user', :u, true), set_config('nexora.channel', :c, true), set_config('nexora.reason', :r, true),
                       set_config('nexora.operation', :o, true)
                """).param("u", Habilitations.acteur()).param("c", Optional.ofNullable(entete("X-Nexora-Channel")).orElse("API"))
                .param("r", Optional.ofNullable(entete("X-Nexora-Reason")).orElse("")).param("o", operation).query((rs, i) -> 1).single();
    }

    private static String entete(String nom) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a)) return null;
        HttpServletRequest r = a.getRequest();
        String v = r.getHeader(nom);
        return v == null || v.isBlank() ? null : URLDecoder.decode(v, StandardCharsets.UTF_8);
    }

    public List<Map<String, Object>> historique(String niu) {
        lecture(niu);
        return jdbc.sql("select operation, actor, channel, reason, changes::text, occurred_at from registre.party_history where niu = :n order by history_id desc")
                .param("n", niu).query((rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("operation", rs.getString(1));
                    m.put("actor", rs.getString(2));
                    m.put("channel", rs.getString(3));
                    m.put("reason", rs.getString(4));
                    m.put("changes", json.map(rs.getString(5)));
                    m.put("occurredAt", rs.getObject(6, OffsetDateTime.class));
                    return m;
                }).list();
    }

    // ------------------------------------------------------------------ comptes rattachés
    public List<Compte> comptes(String niu) {
        lecture(niu);
        return jdbc.sql("select niu, username, relation, is_active, created_at, created_by from registre.party_user where niu = :n order by relation desc, username")
                .param("n", niu).query((rs, i) -> new Compte(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getObject(5, OffsetDateTime.class), rs.getString(6))).list();
    }

    @Transactional
    public List<Compte> rattacher(String niu, Compte c) {
        gestion();
        lire(niu);
        if (c.username() == null || !c.username().matches("^[a-z0-9][a-z0-9._-]{1,59}$")) throw RegistrationException.invalide("Identifiant de compte invalide.");
        if (!List.of("TITULAIRE", "MANDATAIRE").contains(c.relation())) throw RegistrationException.invalide("Relation attendue : TITULAIRE ou MANDATAIRE.");
        jdbc.sql("""
                insert into registre.party_user (niu, username, relation, is_active, created_by) values (:n, :u, :r, :a, :c)
                on conflict (niu, username) do update set relation = excluded.relation, is_active = excluded.is_active
                """).param("n", niu).param("u", c.username()).param("r", c.relation()).param("a", c.active()).param("c", Habilitations.acteur()).update();
        return comptes(niu);
    }

    // ------------------------------------------------------------------ pièces d'enrôlement
    public List<Piece> pieces(String niu) {
        lecture(niu);
        return jdbc.sql("""
                select document_id, doc_code, label, file_name, mime_type, size_bytes, sha256, valid_until, uploaded_by, uploaded_at
                  from registre.party_document where niu = :n and is_active order by doc_code
                """).param("n", niu).query((rs, i) -> new Piece(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getLong(6), rs.getString(7), rs.getObject(8, LocalDate.class), rs.getString(9), rs.getObject(10, OffsetDateTime.class))).list();
    }

    public Map.Entry<Piece, byte[]> contenu(String niu, long id) {
        lecture(niu);
        Piece p = pieces(niu).stream().filter(x -> x.id() == id).findFirst().orElseThrow(() -> RegistrationException.introuvable("Pièce introuvable."));
        byte[] b = jdbc.sql("select content from registre.party_document where document_id = :i").param("i", id).query(byte[].class).single();
        return Map.entry(p, b);
    }

    @Transactional
    public Piece ajouterPiece(String niu, String code, String libelle, String nom, String type, byte[] contenu, LocalDate validite) {
        if (!Habilitations.utilisateurA(SecurityConfig.GERER) && !mesOperateurs().stream().anyMatch(l -> l.niu().equals(niu) && "TITULAIRE".equals(l.relation())))
            throw RegistrationException.interdit("Seuls le titulaire et les gestionnaires du registre peuvent déposer une pièce.");
        if (code == null || !code.matches("^[A-Z][A-Z0-9_]{0,9}$")) throw RegistrationException.invalide("Code de pièce invalide (ex. P2).");
        if (contenu.length == 0) throw RegistrationException.invalide("Fichier vide.");
        jdbc.sql("update registre.party_document set is_active = false where niu = :n and doc_code = :c and is_active").param("n", niu).param("c", code).update();
        long id = jdbc.sql("""
                insert into registre.party_document (niu, doc_code, label, file_name, mime_type, size_bytes, sha256, content, valid_until, uploaded_by)
                values (:n, :c, :l, :f, :t, :s, :h, :b, :v, :u) returning document_id
                """).param("n", niu).param("c", code).param("l", libelle).param("f", nom).param("t", type == null ? "application/octet-stream" : type)
                .param("s", contenu.length).param("h", sha256(contenu)).param("b", contenu).param("v", validite).param("u", Habilitations.acteur())
                .query(Long.class).single();
        return pieces(niu).stream().filter(x -> x.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public void retirerPiece(String niu, long id) {
        gestion();
        jdbc.sql("update registre.party_document set is_active = false where niu = :n and document_id = :i").param("n", niu).param("i", id).update();
    }

    public Map<String, Object> tableauDeBord() {
        return jdbc.sql("""
                select count(*), count(*) filter (where status = 'ACTIVE'), count(*) filter (where party_type_code = 'PM'),
                       count(*) filter (where nationality = 'E'), (select count(*) from registre.party_document where is_active),
                       (select count(*) from registre.party_user where is_active)
                  from registre.party
                """).query((rs, i) -> Map.<String, Object>of("parties", rs.getLong(1), "active", rs.getLong(2), "legalEntities", rs.getLong(3),
                "foreign", rs.getLong(4), "documents", rs.getLong(5), "accounts", rs.getLong(6))).single();
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
