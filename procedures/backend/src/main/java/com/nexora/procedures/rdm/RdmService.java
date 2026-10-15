package com.nexora.procedures.rdm;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dictionnaire du Supply Chain Reference Data Model (UN/CEFACT D23B) chargé dans le schéma rdm. Un chemin BIE est
 * une suite de codes de propriétés depuis la racine du message maître ({@code scrdmMaster}), par exemple
 * {@code exchangedDocument/sender/postalTradeAddress/cityName} ; chaque segment intermédiaire est un ASBIE.
 */
@Service
public class RdmService {

    public static final String RACINE = "scrdmMaster";

    /** {@code source} : SCRDM (restriction contextualisée de l'ABIE BSP) ou BSP (ABIE de la bibliothèque repris sans restriction). */
    public record Abie(String code, String den, String definition, int depth, boolean root, String source) {}

    /** {@code codeList} : liste des valeurs admises du contenu ; {@code unitCodeList} : unités (measure, quantity) ou devises (amount). */
    public record Bie(String abie, String property, String den, String kind, String cardinality, String dataType, String target, String definition,
                      String codeList, String unitCodeList) {}

    /** Élément résolu d'un chemin BIE. */
    public record Element(String path, Bie bie, List<String> denChain) {}

    /** Restriction contextuelle : chemin (relatif à l'ABIE) exclu, propriété (BIE) ou composant supplémentaire (SUPPLEMENTARY). */
    public record Restriction(String abie, String path, String level) {}

    private final JdbcClient jdbc;
    private volatile Integer release;
    private volatile Map<String, Abie> abies;
    private volatile Map<String, List<Bie>> proprietes;
    /** Chemins de propriétés exclus (niveau BIE) par ABIE de contexte. */
    private volatile Map<String, Set<String>> exclusions;

    public RdmService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private synchronized void charger() {
        if (abies != null) return;
        release = jdbc.sql("select release_id from rdm.standard_release where family = 'SCRDM' order by release_code desc limit 1")
                .query(Integer.class).optional().orElse(null);
        Map<String, Abie> a = new LinkedHashMap<>();
        Map<String, List<Bie>> p = new HashMap<>();
        Map<String, Set<String>> x = new HashMap<>();
        if (release != null) {
            jdbc.sql("select abie_code, den, definition, coalesce(depth_from_root, 0), is_root, source from rdm.abie where release_id = :r order by abie_code")
                    .param("r", release).query((rs, i) -> a.put(rs.getString(1), new Abie(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getInt(4), rs.getBoolean(5), rs.getString(6)))).list();
            jdbc.sql("""
                    select b.abie_code, b.property_code, b.den, b.kind::text, b.cardinality, b.data_type, b.target_abie, b.definition,
                           t.code_list_id, t.unit_code_list_id
                      from rdm.bie b left join rdm.data_type t on t.release_id = b.release_id and t.code = b.data_type
                     where b.release_id = :r order by b.abie_code, b.property_code
                    """).param("r", release).query((rs, i) -> p.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                    .add(new Bie(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)))).list();
            jdbc.sql("select abie_code, path from rdm.context_restriction where release_id = :r and level = 'BIE'").param("r", release)
                    .query((rs, i) -> x.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2))).list();
        }
        exclusions = x;
        proprietes = p;
        abies = a;
    }

    public Map<String, Object> resume() {
        charger();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("family", "SCRDM");
        r.put("release", "D23B");
        r.put("abies", abies.size());
        r.put("bbies", proprietes.values().stream().flatMap(List::stream).filter(b -> b.kind().equals("BBIE")).count());
        r.put("asbies", proprietes.values().stream().flatMap(List::stream).filter(b -> b.kind().equals("ASBIE")).count());
        r.put("roots", proprietes.getOrDefault(RACINE, List.of()));
        r.put("untded", untdedTaille());
        r.put("partyRoles", roles().size());
        r.put("restrictions", exclusions.values().stream().mapToInt(Set::size).sum());
        r.put("codeLists", jdbc.sql("select count(*) from rdm.code_list").query(Long.class).single());
        r.put("codes", jdbc.sql("select count(*) from rdm.code_list_entry").query(Long.class).single());
        r.put("instruments", jdbc.sql("select count(*) from rdm.reference_instrument").query(Long.class).single());
        r.put("messages", jdbc.sql("select count(*) from rdm.exchange_message").query(Long.class).single());
        return r;
    }

    public List<Restriction> restrictions(String abie) {
        charger();
        return jdbc.sql("select abie_code, path, level from rdm.context_restriction where release_id = :r and abie_code = :a order by path")
                .param("r", release).param("a", abie).query((rs, i) -> new Restriction(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }

    /**
     * Vrai si le chemin (depuis la racine) traverse une propriété exclue par une restriction contextuelle : pour chaque ABIE
     * visité, la suite des segments qui le suivent est comparée aux chemins qu'il exclut (ex. sous logisticsPackage,
     * {@code includedTradeLineItem/barcodeId}).
     */
    static boolean exclu(List<String> abiesVisites, String[] segments, Map<String, Set<String>> exclusions) {
        for (int j = 0; j < abiesVisites.size(); j++) {
            Set<String> ex = exclusions.get(abiesVisites.get(j));
            if (ex == null) continue;
            for (int i = j; i < segments.length; i++)
                if (ex.contains(String.join("/", Arrays.asList(segments).subList(j, i + 1)))) return true;
        }
        return false;
    }

    public Optional<Abie> abie(String code) {
        charger();
        return Optional.ofNullable(abies.get(code));
    }

    public List<Bie> proprietes(String abie) {
        charger();
        return proprietes.getOrDefault(abie, List.of());
    }

    public List<Abie> abies() {
        charger();
        return List.copyOf(abies.values());
    }

    /** Recherche sans accents dans les DEN, codes et définitions des propriétés. */
    public List<Bie> rechercher(String q, int limite) {
        charger();
        String n = normaliser(q);
        if (n.isBlank()) return List.of();
        List<String> mots = List.of(n.split("\\s+"));
        return proprietes.values().stream().flatMap(List::stream)
                .filter(b -> {
                    String t = normaliser(b.den() + " " + b.property() + " " + Objects.toString(b.definition(), ""));
                    return mots.stream().allMatch(t::contains);
                })
                .sorted(Comparator.comparing((Bie b) -> !normaliser(b.den()).contains(n)).thenComparing(Bie::den))
                .limit(limite).toList();
    }

    /**
     * Résout un chemin BIE ; vide si un segment n'existe pas, si un segment intermédiaire n'est pas un ASBIE ou si une
     * restriction contextuelle du SCRDM exclut le chemin.
     */
    public Optional<Element> resoudre(String chemin) {
        charger();
        if (chemin == null || chemin.isBlank()) return Optional.empty();
        String abie = RACINE;
        Bie courant = null;
        List<String> dens = new ArrayList<>();
        List<String> visites = new ArrayList<>();
        String[] segments = chemin.strip().split("/");
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            String a = abie;
            visites.add(a);
            courant = proprietes.getOrDefault(a, List.of()).stream().filter(b -> b.property().equals(seg)).findFirst().orElse(null);
            if (courant == null) return Optional.empty();
            dens.add(courant.den());
            if (i < segments.length - 1) {
                if (!courant.kind().equals("ASBIE")) return Optional.empty();
                abie = courant.target();
            }
        }
        if (exclu(visites, segments, exclusions)) return Optional.empty();
        return Optional.of(new Element(chemin.strip(), courant, dens));
    }

    /** Vrai si le chemin existe dans le SCRDM mais est exclu par une restriction contextuelle. */
    public boolean excluParRestriction(String chemin) {
        charger();
        if (chemin == null || chemin.isBlank() || resoudre(chemin).isPresent()) return false;
        String abie = RACINE;
        List<String> visites = new ArrayList<>();
        String[] segments = chemin.strip().split("/");
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            visites.add(abie);
            Bie b = proprietes.getOrDefault(abie, List.of()).stream().filter(x -> x.property().equals(seg)).findFirst().orElse(null);
            if (b == null || (i < segments.length - 1 && !b.kind().equals("ASBIE"))) return false;
            abie = b.target();
        }
        return exclu(visites, segments, exclusions);
    }

    /** Chemins les plus courts depuis la racine jusqu'à un ABIE (parcours en largeur, profondeur bornée). */
    public List<String> cheminsVers(String cible, int profondeurMax, int limite) {
        charger();
        List<String> resultats = new ArrayList<>();
        Deque<Map.Entry<String, String>> file = new ArrayDeque<>();
        file.add(Map.entry(RACINE, ""));
        Map<String, Integer> vus = new HashMap<>();
        vus.put(RACINE, 0);
        while (!file.isEmpty() && resultats.size() < limite) {
            var e = file.poll();
            int profondeur = e.getValue().isEmpty() ? 0 : e.getValue().split("/").length;
            if (profondeur >= profondeurMax) continue;
            for (Bie b : proprietes.getOrDefault(e.getKey(), List.of())) {
                if (!b.kind().equals("ASBIE")) continue;
                String chemin = e.getValue().isEmpty() ? b.property() : e.getValue() + "/" + b.property();
                if (resoudre(chemin).isEmpty()) continue; // restriction contextuelle
                if (b.target().equals(cible)) resultats.add(chemin);
                Integer deja = vus.get(b.target());
                if (deja == null || deja >= profondeur + 1) {
                    vus.put(b.target(), profondeur + 1);
                    file.add(Map.entry(b.target(), chemin));
                }
            }
        }
        return resultats;
    }

    // ------------------------------------------------------------------ UNTDED 2005 (ISO 7372) et rôles des parties (RSM SCRDM)
    /** Élément du Trade Data Elements Directory : nom normalisé « Classe. Propriété.Représentation ». */
    public record Untded(String tag, String name, String description, String representation) {}

    /**
     * Rôle de partie ou lieu du Buy-Ship-Pay et son chemin BIE principal ; {@code actorGroup} : CUSTOMER, SUPPLIER,
     * INTERMEDIARY ou AUTHORITY ; {@code functionCode} : code fonction UNTDID 3035 (Trade_ Party. Role. Code).
     */
    public record RolePartie(String code, String category, String labelEn, String labelFr, String definition, String biePath,
                             String mappedTo, List<String> keywords, String actorGroup, String functionCode) {}

    private volatile Map<String, Untded> untded;
    private volatile List<RolePartie> roles;

    private Map<String, Untded> untdedMap() {
        Map<String, Untded> u = untded;
        if (u == null) {
            Map<String, Untded> m = new LinkedHashMap<>();
            jdbc.sql("select tag, name, description, representation from rdm.untded_element order by tag")
                    .query((rs, i) -> m.put(rs.getString(1), new Untded(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))).list();
            untded = u = m;
        }
        return u;
    }

    /** À appeler après le chargement de la table. */
    public void oublierUntded() {
        untded = null;
    }

    public boolean untdedCharge() {
        return !untdedMap().isEmpty();
    }

    public Optional<Untded> untded(String tag) {
        return Optional.ofNullable(tag == null ? null : untdedMap().get(tag.strip()));
    }

    public List<Untded> rechercherUntded(String q, int limite) {
        String n = normaliser(q);
        if (n.isBlank()) return untdedMap().values().stream().limit(limite).toList();
        List<String> mots = List.of(n.split("\\s+"));
        return untdedMap().values().stream()
                .filter(e -> {
                    String t = e.tag() + " " + normaliser(e.name() + " " + Objects.toString(e.description(), ""));
                    return mots.stream().allMatch(t::contains);
                })
                .sorted(Comparator.comparing((Untded e) -> !(e.tag().equals(n) || normaliser(e.name()).contains(n))).thenComparing(Untded::tag))
                .limit(limite).toList();
    }

    public int untdedTaille() {
        return untdedMap().size();
    }

    /**
     * Élément UNTDED correspondant à un BBIE SCRDM : même terme de représentation, propriété proche ; la classe d'objet
     * départage (ex. « Consignment. Gross Weight. Measure » → 6292 / 6012). Vide si aucun candidat n'est assez sûr.
     */
    public Optional<Map.Entry<Untded, Double>> untdedPour(String denBie) {
        if (denBie == null || denBie.isBlank()) return Optional.empty();
        String[] b = decouperDen(denBie);
        Set<String> classe = mots(b[0]), propriete = mots(b[1]);
        Untded meilleur = null;
        double max = 0;
        for (Untded e : untdedMap().values()) {
            String[] u = decouperDen(e.name());
            if (!normaliser(u[2]).equals(normaliser(b[2]))) continue;
            double s = 3 * jaccard(propriete, mots(u[1])) + 1.5 * jaccard(classe, mots(u[0]));
            if (s > max) {
                max = s;
                meilleur = e;
            }
        }
        // Propriété identique et classe d'objet au moins proche (ex. « Product. Classification » ≠ « Organisation. Classification »)
        return meilleur != null && max >= 3.5 ? Optional.of(Map.entry(meilleur, Math.min(0.95, max / 4.5))) : Optional.empty();
    }

    /** « Supply Chain_ Consignment. Gross Weight. Measure » → [classe, propriété, représentation] (qualifiants « X_ » retirés). */
    static String[] decouperDen(String den) {
        String[] p = den.split("\\.");
        String classe = p[0].replaceAll(".*_\\s*", "");
        String rep = p.length > 1 ? p[p.length - 1] : "";
        String prop = p.length > 2 ? String.join(" ", Arrays.asList(p).subList(1, p.length - 1)) : "";
        // UNTDED « Document. Type.Code » : propriété « Type » ; « Document. Identifier » : propriété vide
        prop = prop.replaceAll("[A-Za-z ]*_\\s*", "");
        return new String[]{classe.strip(), prop.strip(), rep.strip()};
    }

    private static Set<String> mots(String s) {
        Set<String> r = new HashSet<>(List.of(normaliser(s).split("\\s+")));
        r.remove("");
        return r;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1;
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> i = new HashSet<>(a);
        i.retainAll(b);
        Set<String> u = new HashSet<>(a);
        u.addAll(b);
        return (double) i.size() / u.size();
    }

    public List<RolePartie> roles() {
        List<RolePartie> r = roles;
        if (r == null)
            roles = r = jdbc.sql("""
                    select code, category, label_en, label_fr, definition, bie_path, mapped_to, keywords, actor_group, function_code
                      from rdm.party_role order by sort_order
                    """).query((rs, i) -> new RolePartie(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getString(7), List.of((String[]) rs.getArray(8).getArray()), rs.getString(9), rs.getString(10))).list();
        return r;
    }

    // ------------------------------------------------------------------ Types de données et listes de codes (BSP-RDM D23B)
    public record TypeDonnees(String code, String kind, String den, String definition, String codeList, String unitCodeList, String agencyCode) {}

    public record ListeCodes(String id, String agency, String name, String description, String agencyCode, String listRef, String listName,
                             String version, String issueDate, boolean closed, String sourceUri, long entries) {}

    public record Code(String code, String name, List<String> altNames) {}

    public List<TypeDonnees> typesDonnees() {
        charger();
        return jdbc.sql("select code, kind, den, definition, code_list_id, unit_code_list_id, agency_code from rdm.data_type where release_id = :r order by kind, code")
                .param("r", release).query((rs, i) -> new TypeDonnees(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7))).list();
    }

    private static final String LISTES = """
            select l.list_id, l.agency, l.name, l.description, l.agency_code, l.list_ref, l.list_name, l.list_version, l.issue_date, l.closed,
                   l.source_uri, (select count(*) from rdm.code_list_entry e where e.list_id = l.list_id)
              from rdm.code_list l
            """;

    private static ListeCodes liste(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ListeCodes(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getString(9), rs.getBoolean(10), rs.getString(11), rs.getLong(12));
    }

    public List<ListeCodes> listesCodes() {
        return jdbc.sql(LISTES + " order by l.agency desc, l.list_id").query((rs, i) -> liste(rs)).list();
    }

    public Optional<ListeCodes> listeCodes(String id) {
        return jdbc.sql(LISTES + " where l.list_id = :id").param("id", id).query((rs, i) -> liste(rs)).optional();
    }

    /** Valeurs d'une liste, filtrées par code ou libellé (sans accents). */
    public List<Code> codes(String liste, String q, int limite) {
        String n = normaliser(q);
        return jdbc.sql("select code, name, alt_names from rdm.code_list_entry where list_id = :l order by code").param("l", liste)
                .query((rs, i) -> new Code(rs.getString(1), rs.getString(2),
                        tableau(rs.getArray(3)))).list().stream()
                .filter(c -> n.isBlank() || normaliser(c.code() + " " + c.name() + " " + String.join(" ", c.altNames())).contains(n))
                .limit(limite).toList();
    }

    /** Vrai si la valeur appartient à la liste (toujours vrai pour une liste ouverte, sans valeurs énumérées). */
    public boolean codeAdmis(String liste, String code) {
        return listeCodes(liste).map(l -> !l.closed() || jdbc.sql("select count(*) from rdm.code_list_entry where list_id = :l and code = :c")
                .param("l", liste).param("c", code).query(Long.class).single() > 0).orElse(false);
    }

    // ------------------------------------------------------------------ Couche Buy-Ship-Pay (ISCRM / BSP-RDM)
    public record Domaine(String code, String labelFr, String labelEn, String description) {}

    public record Processus(String code, String phase, String domain, String labelFr, String labelEn, String description, List<String> documents) {}

    public record EntiteBsp(String code, String labelFr, String labelEn, String definition, String abie, String biePath, List<String> keyPaths, String note) {}

    public record RelationBsp(String from, String to, String cardinality, String rule) {}

    public record PartieEntite(String entity, String partyRole, String cardinality, String rule) {}

    private static List<String> tableau(java.sql.Array a) throws java.sql.SQLException {
        return a == null ? List.of() : List.of((String[]) a.getArray());
    }

    public Map<String, Object> bsp() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domains", jdbc.sql("select code, label_fr, label_en, description from rdm.bsp_domain order by sort_order")
                .query((rs, i) -> new Domaine(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list());
        r.put("processes", jdbc.sql("select code, phase, domain, label_fr, label_en, description, documents from rdm.bsp_process order by sort_order")
                .query((rs, i) -> new Processus(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        tableau(rs.getArray(7)))).list());
        r.put("entities", jdbc.sql("select code, label_fr, label_en, definition, abie_code, bie_path, key_paths, note from rdm.bsp_entity order by sort_order")
                .query((rs, i) -> new EntiteBsp(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        tableau(rs.getArray(7)), rs.getString(8))).list());
        r.put("relations", jdbc.sql("select from_entity, to_entity, cardinality, rule_fr from rdm.bsp_entity_relation order by from_entity, to_entity")
                .query((rs, i) -> new RelationBsp(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list());
        r.put("parties", jdbc.sql("select entity, party_role, cardinality, rule_fr from rdm.bsp_entity_party order by entity, party_role")
                .query((rs, i) -> new PartieEntite(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list());
        return r;
    }

    // ------------------------------------------------------------------ Instruments de référence et messages d'échange
    public record Instrument(String code, String family, String number, String titleFr, String docRef, String issued, String summaryFr, String url) {}

    public record LienInstrument(String instrument, String targetKind, String target, String note) {}

    public record Message(String code, String standard, String kind, String name, String nameFr, String phase, String description, String sourceUri) {}

    public Map<String, Object> instruments() {
        return Map.of(
                "instruments", jdbc.sql("select code, family, number, title_fr, doc_ref, issued, summary_fr, url from rdm.reference_instrument order by sort_order")
                        .query((rs, i) -> new Instrument(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                                rs.getString(6), rs.getString(7), rs.getString(8))).list(),
                "links", jdbc.sql("select instrument, target_kind, target, note from rdm.instrument_link order by instrument, target_kind, target")
                        .query((rs, i) -> new LienInstrument(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list());
    }

    public List<Message> messages() {
        return jdbc.sql("select code, standard, kind, name, name_fr, phase, description, source_uri from rdm.exchange_message order by standard, kind, code")
                .query((rs, i) -> new Message(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8))).list();
    }

    /** Rôle de partie désigné par un libellé (« Nom de l'expéditeur » → CONSIGNOR), le terme le plus long l'emportant. */
    public Optional<RolePartie> rolePour(String libelle) {
        String n = " " + normaliser(libelle) + " ";
        RolePartie meilleur = null;
        int long_ = 0;
        for (RolePartie r : roles())
            for (String k : r.keywords()) {
                String kn = normaliser(k);
                if (!kn.isEmpty() && n.contains(" " + kn + " ") && kn.length() > long_) {
                    long_ = kn.length();
                    meilleur = r;
                }
            }
        return Optional.ofNullable(meilleur);
    }

    /** Tous les BBIE (pour le rapprochement sémantique du Data Agent). */
    public List<Bie> bbies() {
        charger();
        return proprietes.values().stream().flatMap(List::stream).filter(b -> b.kind().equals("BBIE")).collect(Collectors.toList());
    }

    public static String normaliser(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").strip();
    }
}
