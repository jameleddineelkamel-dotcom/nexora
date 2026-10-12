package com.nexora.procedures.agents;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Modèle de données métier livré sous forme de classeur Excel (ex. DM_TRANSACTION_eGUCE3G_eForce) : une feuille par
 * entité (ligne de titre, puis en-tête « Attribut | Libellé FR | Format | Liste de Codes | Obligatoire | Référence
 * UNTDED | Référence WCO Data Model | Référence UN/CEFACT (CCL) | Unique UN Assigned ID | Description ») et une feuille
 * de relations (« Entité source | Cardinalité | Entité cible | Clé de relation »). Lecture directe du format OOXML
 * (zip + XML), sans dépendance ; le classeur est une donnée : seules les cellules sont lues.
 */
final class ModeleDonnees {

    record Attribut(String code, String libelle, String format, String liste, boolean obligatoire, String untded, String wco, String den,
                    String uid, String description) {}

    record Entite(String nom, String description, List<Attribut> attributs) {}

    record Relation(String source, String cardinalite, String cible, String cle) {}

    private final String nom;
    private final Map<String, Entite> entites = new LinkedHashMap<>();
    private final List<Relation> relations = new ArrayList<>();

    private ModeleDonnees(String nom) {
        this.nom = nom;
    }

    static boolean estClasseur(String nom, byte[] contenu) {
        return nom != null && nom.toLowerCase(Locale.ROOT).endsWith(".xlsx") && contenu.length > 4 && contenu[0] == 'P' && contenu[1] == 'K';
    }

    static ModeleDonnees lire(String nom, byte[] contenu) {
        Map<String, List<List<String>>> feuilles = feuilles(contenu);
        ModeleDonnees m = new ModeleDonnees(nom);
        for (var f : feuilles.entrySet()) {
            List<List<String>> lignes = f.getValue();
            int entete = -1;
            for (int i = 0; i < Math.min(6, lignes.size()); i++) {
                String premiere = DocumentAgent.normaliser(String.join(" ", lignes.get(i)));
                if (premiere.startsWith("attribut") || premiere.contains(" attribut libelle")) { entete = i; break; }
                if (premiere.contains("entite source") && premiere.contains("entite cible")) {
                    for (List<String> l : lignes.subList(i + 1, lignes.size()))
                        if (l.size() >= 3 && !l.get(0).isBlank() && !l.get(2).isBlank())
                            m.relations.add(new Relation(l.get(0).strip(), l.get(1).strip(), l.get(2).strip(), l.size() > 3 ? l.get(3).strip() : ""));
                    break;
                }
            }
            if (entete < 0) continue;
            Map<String, Integer> col = new HashMap<>();
            List<String> e = lignes.get(entete);
            for (int c = 0; c < e.size(); c++) {
                String n = DocumentAgent.normaliser(e.get(c));
                if (n.startsWith("attribut")) col.putIfAbsent("code", c);
                else if (n.startsWith("libelle")) col.putIfAbsent("libelle", c);
                else if (n.startsWith("format")) col.putIfAbsent("format", c);
                else if (n.startsWith("liste")) col.putIfAbsent("liste", c);
                else if (n.startsWith("obligatoire")) col.putIfAbsent("obligatoire", c);
                else if (n.contains("untded")) col.putIfAbsent("untded", c);
                else if (n.contains("wco")) col.putIfAbsent("wco", c);
                else if (n.contains("unique")) col.putIfAbsent("uid", c);
                else if (n.contains("cefact") || n.contains("ccl")) col.putIfAbsent("den", c);
                else if (n.startsWith("description")) col.putIfAbsent("description", c);
            }
            if (!col.containsKey("code")) continue;
            List<Attribut> attributs = new ArrayList<>();
            for (List<String> l : lignes.subList(entete + 1, lignes.size())) {
                String code = cellule(l, col.get("code"));
                if (code.isBlank() || !code.matches("^[A-Za-z][A-Za-z0-9_]*$")) continue;
                attributs.add(new Attribut(code, cellule(l, col.get("libelle")), cellule(l, col.get("format")), propre(cellule(l, col.get("liste"))),
                        DocumentAgent.normaliser(cellule(l, col.get("obligatoire"))).startsWith("oui"), propre(cellule(l, col.get("untded"))),
                        propre(cellule(l, col.get("wco"))), propre(cellule(l, col.get("den"))), propre(cellule(l, col.get("uid"))),
                        cellule(l, col.get("description"))));
            }
            String titre = lignes.isEmpty() || lignes.getFirst().isEmpty() ? "" : String.join(" ", lignes.getFirst()).strip();
            m.entites.put(f.getKey(), new Entite(f.getKey(), titre, attributs));
        }
        return m;
    }

    String nom() { return nom; }

    Map<String, Entite> entites() { return entites; }

    List<Relation> relations() { return relations; }

    /** Entités filles (1 — N) d'une entité. */
    List<String> enfants(String entite) {
        return relations.stream().filter(r -> r.source().equals(entite) && r.cardinalite().replace(" ", "").matches("[01]—N|[01]-N|[01]–N"))
                .map(Relation::cible).filter(entites::containsKey).toList();
    }

    /** Entité parente (1 — N) d'une entité, ou null. */
    String parent(String entite) {
        return relations.stream().filter(r -> r.cible().equals(entite) && r.cardinalite().replace(" ", "").startsWith("1")).map(Relation::source)
                .filter(entites::containsKey).findFirst().orElse(null);
    }

    /** Attribut technique (identifiant, clés de liaison, traçabilité) : jamais un champ de formulaire. */
    static boolean technique(Attribut a) {
        String c = a.code();
        String liste = Objects.toString(a.liste(), "");
        return c.equals("id") || liste.contains("REF_BSP") || c.endsWith("Id") && !liste.matches(".*REF_[A-Z].*")
                || Set.of("activeIndicator", "createdDate", "updatedDate", "createdBy", "updatedBy").contains(c);
    }

    private static String cellule(List<String> l, Integer c) {
        return c == null || c >= l.size() || l.get(c) == null ? "" : l.get(c).strip();
    }

    private static String propre(String s) {
        return s == null || s.isBlank() || s.matches("[—–-]+") ? null : s;
    }

    // ------------------------------------------------------------------ lecture OOXML
    private static Map<String, List<List<String>>> feuilles(byte[] contenu) {
        try {
            Map<String, byte[]> fichiers = new HashMap<>();
            try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(contenu))) {
                ZipEntry e;
                long total = 0;
                while ((e = z.getNextEntry()) != null) {
                    if (!e.getName().startsWith("xl/") || !e.getName().endsWith(".xml") && !e.getName().endsWith(".rels")) continue;
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    for (int n; (n = z.read(b)) > 0; ) {
                        total += n;
                        if (total > 200L * 1024 * 1024) throw new IllegalArgumentException("classeur trop volumineux");
                        o.write(b, 0, n);
                    }
                    fichiers.put(e.getName(), o.toByteArray());
                }
            }
            DocumentBuilder db = constructeur();
            List<String> chaines = new ArrayList<>();
            if (fichiers.containsKey("xl/sharedStrings.xml")) {
                NodeList si = db.parse(new ByteArrayInputStream(fichiers.get("xl/sharedStrings.xml"))).getElementsByTagName("si");
                for (int i = 0; i < si.getLength(); i++) chaines.add(textes((Element) si.item(i)));
            }
            Map<String, String> cibles = new HashMap<>();
            NodeList rels = db.parse(new ByteArrayInputStream(fichiers.get("xl/_rels/workbook.xml.rels"))).getElementsByTagName("Relationship");
            for (int i = 0; i < rels.getLength(); i++) {
                Element r = (Element) rels.item(i);
                String t = r.getAttribute("Target");
                cibles.put(r.getAttribute("Id"), t.startsWith("/") ? t.substring(1) : "xl/" + t);
            }
            Map<String, List<List<String>>> r = new LinkedHashMap<>();
            NodeList sheets = db.parse(new ByteArrayInputStream(fichiers.get("xl/workbook.xml"))).getElementsByTagName("sheet");
            for (int i = 0; i < sheets.getLength(); i++) {
                Element s = (Element) sheets.item(i);
                byte[] xml = fichiers.get(cibles.get(s.getAttribute("r:id")));
                if (xml == null) continue;
                r.put(s.getAttribute("name"), lignes(db.parse(new ByteArrayInputStream(xml)), chaines));
            }
            return r;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Classeur Excel illisible : " + e.getMessage(), e);
        }
    }

    private static List<List<String>> lignes(Document d, List<String> chaines) {
        List<List<String>> r = new ArrayList<>();
        NodeList rows = d.getElementsByTagName("row");
        for (int i = 0; i < rows.getLength(); i++) {
            List<String> l = new ArrayList<>();
            NodeList cs = ((Element) rows.item(i)).getElementsByTagName("c");
            for (int j = 0; j < cs.getLength(); j++) {
                Element c = (Element) cs.item(j);
                int col = colonne(c.getAttribute("r"));
                while (l.size() < col) l.add("");
                String t = c.getAttribute("t");
                NodeList v = c.getElementsByTagName("v");
                String val;
                if (t.equals("s") && v.getLength() > 0) {
                    int k = Integer.parseInt(v.item(0).getTextContent().strip());
                    val = k < chaines.size() ? chaines.get(k) : "";
                } else if (t.equals("inlineStr")) val = textes(c);
                else val = v.getLength() > 0 ? v.item(0).getTextContent() : "";
                l.add(val);
            }
            // Première colonne vide fréquente (marge) : on la retire si toute la feuille commence par elle
            r.add(l);
        }
        boolean marge = !r.isEmpty() && r.stream().filter(l -> !l.isEmpty()).allMatch(l -> l.getFirst().isBlank());
        if (marge) r.replaceAll(l -> l.isEmpty() ? l : new ArrayList<>(l.subList(1, l.size())));
        return r;
    }

    private static int colonne(String ref) {
        int n = 0;
        for (char ch : ref.toCharArray()) {
            if (!Character.isLetter(ch)) break;
            n = n * 26 + (Character.toUpperCase(ch) - 'A' + 1);
        }
        return Math.max(0, n - 1);
    }

    private static String textes(Element e) {
        StringBuilder s = new StringBuilder();
        NodeList t = e.getElementsByTagName("t");
        for (int i = 0; i < t.getLength(); i++) s.append(t.item(i).getTextContent());
        return s.toString();
    }

    private static DocumentBuilder constructeur() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        f.setNamespaceAware(false);
        return f.newDocumentBuilder();
    }
}
