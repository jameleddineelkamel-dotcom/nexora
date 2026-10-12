package com.nexora.procedures.agents;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.Inflater;

/**
 * Lecture d'un diagramme draw.io (diagrams.net) dessiné en notation BPMN : couloirs (acteurs), événements, tâches,
 * passerelles, documents, flux et leurs libellés, avec leurs positions. Le contenu compressé (deflate + Base64) des
 * anciennes versions de draw.io est pris en charge. Le fichier est une donnée : seuls les éléments graphiques sont lus.
 */
public final class DrawioSource {

    public enum Type { DEBUT, FIN, INTERMEDIAIRE, TACHE, PASSERELLE, DOCUMENT }

    public record Couloir(String id, String libelle, double x, double y, double l, double h) {
        boolean contient(double cx, double cy) {
            return cx >= x && cx <= x + l && cy >= y && cy <= y + h;
        }
    }

    public record Noeud(String id, Type type, String libelle, double x, double y, double l, double h, String couloir) {
        double cx() { return x + l / 2; }
        double cy() { return y + h / 2; }
    }

    public record Arc(String id, String source, String cible, String libelle, boolean pointille) {}

    /** Texte libre (hors couloirs et formes), ex. « Système e-Force », rattaché au couloir où il est posé. */
    public record Note(String texte, String couloir) {}

    private final String page;
    private final String titre;
    private final Couloir piscine;
    private final List<Couloir> couloirs;
    private final List<Noeud> noeuds;
    private final List<Arc> arcs;
    private final List<Note> notes;

    private DrawioSource(String page, String titre, Couloir piscine, List<Couloir> couloirs, List<Noeud> noeuds, List<Arc> arcs, List<Note> notes) {
        this.page = page;
        this.titre = titre;
        this.piscine = piscine;
        this.couloirs = couloirs;
        this.noeuds = noeuds;
        this.arcs = arcs;
        this.notes = notes;
    }

    public static boolean estDrawio(String contenu) {
        return contenu != null && (contenu.contains("<mxfile") || contenu.contains("<mxGraphModel"));
    }

    /** Diagrammes du fichier (un par page draw.io). */
    public static List<DrawioSource> lire(String contenu) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(contenu.getBytes(StandardCharsets.UTF_8)));
            List<DrawioSource> r = new ArrayList<>();
            NodeList pages = d.getElementsByTagName("diagram");
            if (pages.getLength() == 0) {
                r.add(analyser("Diagramme", d.getDocumentElement()));
                return r;
            }
            for (int i = 0; i < pages.getLength(); i++) {
                Element page = (Element) pages.item(i);
                String nom = page.getAttribute("name");
                NodeList modeles = page.getElementsByTagName("mxGraphModel");
                Element modele;
                if (modeles.getLength() > 0) modele = (Element) modeles.item(0);
                else {
                    String xml = decompresser(page.getTextContent().strip());
                    modele = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
                }
                r.add(analyser(nom.isBlank() ? "Page " + (i + 1) : nom, modele));
            }
            return r;
        } catch (Exception e) {
            throw new IllegalArgumentException("Diagramme draw.io illisible : " + e.getMessage(), e);
        }
    }

    /** Format compressé de draw.io : Base64 → deflate brut → URL-encodage. */
    private static String decompresser(String b64) throws Exception {
        byte[] brut = Base64.getDecoder().decode(b64);
        Inflater inf = new Inflater(true);
        inf.setInput(brut);
        byte[] tampon = new byte[1 << 16];
        StringBuilder s = new StringBuilder();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while (!inf.finished()) {
            int n = inf.inflate(tampon);
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
            out.write(tampon, 0, n);
        }
        s.append(out.toString(StandardCharsets.UTF_8));
        return URLDecoder.decode(s.toString(), StandardCharsets.UTF_8);
    }

    private record Cellule(String id, String valeur, Map<String, String> style, boolean sommet, boolean arc, String source, String cible,
                           double x, double y, double l, double h) {}

    private static DrawioSource analyser(String nom, Element modele) {
        List<Cellule> cellules = new ArrayList<>();
        NodeList l = modele.getElementsByTagName("mxCell");
        for (int i = 0; i < l.getLength(); i++) {
            Element c = (Element) l.item(i);
            // Une cellule peut être enveloppée dans <object label="…"> ou <UserObject label="…">
            String valeur = c.getAttribute("value");
            String id = c.getAttribute("id");
            if (c.getParentNode() instanceof Element parent && !parent.getTagName().equals("root")) {
                if (valeur.isBlank()) valeur = parent.getAttribute("label");
                if (id.isBlank()) id = parent.getAttribute("id");
            }
            double x = 0, y = 0, w = 0, h = 0;
            NodeList g = c.getElementsByTagName("mxGeometry");
            if (g.getLength() > 0) {
                Element geo = (Element) g.item(0);
                x = nombre(geo.getAttribute("x"));
                y = nombre(geo.getAttribute("y"));
                w = nombre(geo.getAttribute("width"));
                h = nombre(geo.getAttribute("height"));
            }
            cellules.add(new Cellule(id, texte(valeur), style(c.getAttribute("style")), "1".equals(c.getAttribute("vertex")),
                    "1".equals(c.getAttribute("edge")), c.getAttribute("source"), c.getAttribute("target"), x, y, w, h));
        }

        // Couloirs : cellules « swimlane » / « pool », ou en-têtes de texte verticaux (horizontal=0)
        List<Couloir> bandes = new ArrayList<>();
        for (Cellule c : cellules) {
            if (!c.sommet() || c.valeur().isBlank()) continue;
            String forme = c.style().getOrDefault("shape", "");
            boolean swimlane = c.style().containsKey("swimlane") || forme.contains("pool") || forme.contains("swimlane");
            boolean enteteVertical = "0".equals(c.style().get("horizontal")) && c.h() > c.l() * 2;
            if (swimlane || enteteVertical) bandes.add(new Couloir(c.id(), c.valeur(), c.x(), c.y(), swimlane ? c.l() : Double.MAX_VALUE / 4, c.h()));
        }
        // La piscine est la bande qui contient verticalement toutes les autres
        Couloir piscine = null;
        for (Couloir b : bandes)
            if (bandes.size() > 1 && bandes.stream().filter(o -> o != b).allMatch(o -> o.y() >= b.y() - 1 && o.y() + o.h() <= b.y() + b.h() + 1)) piscine = b;
        List<Couloir> couloirs = new ArrayList<>();
        for (Couloir b : bandes) if (b != piscine) couloirs.add(b);
        couloirs.sort(Comparator.comparingDouble(Couloir::y));

        Map<String, Integer> entrants = new HashMap<>(), sortants = new HashMap<>();
        for (Cellule c : cellules)
            if (c.arc() && !c.source().isBlank() && !c.cible().isBlank()) {
                sortants.merge(c.source(), 1, Integer::sum);
                entrants.merge(c.cible(), 1, Integer::sum);
            }

        Set<String> idsBandes = new HashSet<>();
        bandes.forEach(b -> idsBandes.add(b.id()));
        List<Noeud> noeuds = new ArrayList<>();
        List<Note> notes = new ArrayList<>();
        for (Cellule c : cellules) {
            if (!c.sommet() || idsBandes.contains(c.id())) continue;
            Map<String, String> s = c.style();
            String forme = s.getOrDefault("shape", "");
            boolean relie = entrants.containsKey(c.id()) || sortants.containsKey(c.id());
            Type type;
            if (s.containsKey("ellipse") || forme.contains("bpmn.event") || forme.equals("ellipse")) {
                String contour = s.getOrDefault("outline", "");
                if (contour.equals("end") || (!sortants.containsKey(c.id()) && entrants.containsKey(c.id()))) type = Type.FIN;
                else if (contour.equals("standard") || !entrants.containsKey(c.id())) type = Type.DEBUT;
                else type = Type.INTERMEDIAIRE;
            } else if (s.containsKey("rhombus") || forme.contains("gateway") || forme.equals("rhombus")) type = Type.PASSERELLE;
            else if (forme.equals("document") || forme.contains("dataObject") || forme.contains("note")) type = Type.DOCUMENT;
            else if (s.containsKey("text") && !relie) {
                if (!c.valeur().isBlank()) notes.add(new Note(c.valeur(), couloirDe(couloirs, c.x() + c.l() / 2, c.y() + c.h() / 2)));
                continue;
            } else if (!relie) continue;   // cadre ou décoration sans flux
            else type = Type.TACHE;
            noeuds.add(new Noeud(c.id(), type, c.valeur(), c.x(), c.y(), c.l(), c.h(), couloirDe(couloirs, c.x() + c.l() / 2, c.y() + c.h() / 2)));
        }
        Set<String> ids = new HashSet<>();
        noeuds.forEach(n -> ids.add(n.id()));
        List<Arc> arcs = new ArrayList<>();
        for (Cellule c : cellules)
            if (c.arc() && ids.contains(c.source()) && ids.contains(c.cible()))
                arcs.add(new Arc(c.id(), c.source(), c.cible(), c.valeur(), "1".equals(c.style().get("dashed"))));
        return new DrawioSource(nom, piscine != null ? piscine.libelle() : nom, piscine, couloirs, noeuds, arcs, notes);
    }

    private static String couloirDe(List<Couloir> couloirs, double cx, double cy) {
        for (Couloir c : couloirs) if (cy >= c.y() && cy <= c.y() + c.h() && cx >= c.x()) return c.id();
        return null;
    }

    private static Map<String, String> style(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null) return m;
        for (String p : s.split(";")) {
            if (p.isBlank()) continue;
            int i = p.indexOf('=');
            if (i < 0) m.put(p.strip(), "");
            else m.put(p.substring(0, i).strip(), p.substring(i + 1).strip());
        }
        return m;
    }

    /** Libellé sans balises HTML ni entités (draw.io stocke souvent du HTML). */
    static String texte(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?i)<br\\s*/?>", " ").replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'").replace(' ', ' ');
        return s.replaceAll("\\s+", " ").strip();
    }

    private static double nombre(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Nom de la page draw.io. */
    public String page() { return page; }

    public String titre() { return titre; }

    public Couloir piscine() { return piscine; }

    public List<Couloir> couloirs() { return couloirs; }

    public List<Noeud> noeuds() { return noeuds; }

    public List<Arc> arcs() { return arcs; }

    public List<Note> notes() { return notes; }

    public Optional<Couloir> couloir(String id) {
        return couloirs.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    /** Pertinence comme diagramme principal d'une procédure : un début, des tâches et au moins une décision. */
    public int score() {
        long taches = noeuds.stream().filter(n -> n.type() == Type.TACHE).count();
        long passerelles = noeuds.stream().filter(n -> n.type() == Type.PASSERELLE).count();
        boolean debut = noeuds.stream().anyMatch(n -> n.type() == Type.DEBUT);
        return (debut ? 100 : 0) + (int) Math.min(taches, 20) * 3 + (int) Math.min(passerelles, 10) * 5 + couloirs.size() * 2;
    }
}
