package com.nexora.procedures.agents;

import com.nexora.procedures.studio.VersionService;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Images jointes (PNG, JPEG : figures de la SFD, captures d'écran, maquettes exportées) lues par le modèle de vision
 * auto-hébergé, une seule fois par image (cache par empreinte) : un diagramme devient un diagramme draw.io synthétique
 * (repris par l'import BPMN), un écran devient une maquette (reprise par la composition du formulaire). Sans modèle de
 * vision, les images sont signalées et non exploitées. Ce que lit le modèle reste une proposition à revoir.
 */
@Component
public class LectureImages {

    /** Résultat de lecture d'une image. */
    public record Lecture(String nature, String titre, Maquette maquette, String drawio, String message) {}

    private static final String CONSIGNE = """
            Tu lis une image extraite d'une spécification fonctionnelle (SFD) de guichet unique du commerce extérieur.
            Elle montre soit un DIAGRAMME de processus (BPMN : couloirs, tâches, passerelles, événements, documents, flux),
            soit un ÉCRAN (formulaire de saisie), soit autre chose. Réponds uniquement par un objet JSON :
            {"kind":"DIAGRAM|SCREEN|OTHER","title":"…",
             "diagram":{"lanes":["…"],"nodes":[{"id":"n1","type":"START|END|TASK|GATEWAY|DOCUMENT|EVENT","label":"…","lane":"…"}],
                        "flows":[{"from":"n1","to":"n2","label":"…"}]},
             "screen":{"blocks":[{"title":"…","table":false,"fields":[{"label":"…","required":false,"readonly":false,"options":["…"],"format":"…"}]}]}}
            Recopie les libellés exactement tels qu'ils apparaissent (y compris ceux des flux) ; n'invente aucun élément absent
            de l'image ; un champ marqué d'un astérisque est obligatoire ; pour un tableau, les colonnes sont les champs.""";

    private final ModeleLangage llm;
    private final Map<String, Lecture> cache = new ConcurrentHashMap<>();

    public LectureImages(ModeleLangage llm) {
        this.llm = llm;
    }

    public boolean disponible() {
        return llm.visionDisponible();
    }

    public String modele() {
        return llm.nomVision();
    }

    /** Lecture d'une image ; vide si le modèle de vision est absent ou ne répond pas. */
    public Optional<Lecture> lire(String nom, byte[] image) {
        if (!llm.visionDisponible()) return Optional.empty();
        String cle = VersionService.sha256(new String(image, java.nio.charset.StandardCharsets.ISO_8859_1));
        Lecture deja = cache.get(cle);
        if (deja != null) return Optional.of(deja);
        Optional<ModeleLangage.Reponse> r = llm.jsonImage(CONSIGNE, "Image : " + nom, image, type(image));
        if (r.isEmpty()) return Optional.empty();
        Lecture l = interpreter(nom, r.get().json());
        cache.put(cle, l);
        return Optional.of(l);
    }

    static String type(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 ? "image/jpeg" : "image/png";
    }

    // ------------------------------------------------------------------ interprétation de la réponse
    @SuppressWarnings("unchecked")
    static Lecture interpreter(String nom, Map<String, Object> j) {
        String nature = Objects.toString(j.get("kind"), "OTHER").toUpperCase(Locale.ROOT);
        String titre = Objects.toString(j.get("title"), nom);
        if (nature.equals("SCREEN") && j.get("screen") instanceof Map<?, ?> ecran) {
            List<Maquette.Bloc> blocs = new ArrayList<>();
            for (Map<String, Object> b : liste(ecran.get("blocks"))) {
                List<Maquette.Champ> champs = new ArrayList<>();
                for (Map<String, Object> c : liste(b.get("fields"))) {
                    String libelle = Objects.toString(c.get("label"), "").replace("*", "").strip();
                    if (libelle.isBlank()) continue;
                    List<String> options = c.get("options") instanceof List<?> o ? o.stream().map(String::valueOf).filter(x -> !x.isBlank()).toList() : List.of();
                    champs.add(new Maquette.Champ(libelle, Boolean.TRUE.equals(c.get("required")) || Objects.toString(c.get("label"), "").contains("*"),
                            Boolean.TRUE.equals(c.get("readonly")), options.isEmpty() ? "input" : "select", options,
                            c.get("format") == null ? null : String.valueOf(c.get("format")), null));
                }
                if (!champs.isEmpty())
                    blocs.add(new Maquette.Bloc(Objects.toString(b.get("title"), "Formulaire"), false, champs, Boolean.TRUE.equals(b.get("table"))));
            }
            int n = blocs.stream().mapToInt(b -> b.champs().size()).sum();
            return new Lecture("SCREEN", titre, Maquette.de(List.of(new Maquette.Ecran(titre, blocs))), null,
                    "écran « " + titre + " » : " + blocs.size() + " bloc(s), " + n + " champ(s)");
        }
        if (nature.equals("DIAGRAM") && j.get("diagram") instanceof Map<?, ?> d) {
            String xml = drawio(titre, (Map<String, Object>) d);
            int noeuds = liste(d.get("nodes")).size(), flux = liste(d.get("flows")).size();
            return new Lecture("DIAGRAM", titre, null, xml, "diagramme « " + titre + " » : " + noeuds + " élément(s), " + flux + " flux");
        }
        return new Lecture("OTHER", titre, null, null, "ni diagramme ni écran");
    }

    /** Diagramme draw.io synthétique : un couloir par acteur, éléments disposés selon l'ordre des flux. */
    static String drawio(String titre, Map<String, Object> d) {
        List<String> couloirs = new ArrayList<>();
        if (d.get("lanes") instanceof List<?> l) l.forEach(x -> { if (!String.valueOf(x).isBlank()) couloirs.add(String.valueOf(x)); });
        List<Map<String, Object>> noeuds = liste(d.get("nodes")), flux = liste(d.get("flows"));
        for (Map<String, Object> n : noeuds) {
            String c = Objects.toString(n.get("lane"), "");
            if (!c.isBlank() && !couloirs.contains(c)) couloirs.add(c);
        }
        if (couloirs.isEmpty()) couloirs.add("Processus");
        // Colonne de chaque nœud : plus long chemin depuis un début (parcours en largeur borné)
        Map<String, Integer> colonne = new HashMap<>();
        Map<String, List<String>> suivants = new HashMap<>();
        for (Map<String, Object> f : flux) suivants.computeIfAbsent(String.valueOf(f.get("from")), k -> new ArrayList<>()).add(String.valueOf(f.get("to")));
        Deque<String> file = new ArrayDeque<>();
        Set<String> cibles = new HashSet<>();
        flux.forEach(f -> cibles.add(String.valueOf(f.get("to"))));
        for (Map<String, Object> n : noeuds) {
            String id = String.valueOf(n.get("id"));
            if ("START".equalsIgnoreCase(String.valueOf(n.get("type"))) || !cibles.contains(id)) { colonne.put(id, 0); file.add(id); }
        }
        int garde = 0;
        while (!file.isEmpty() && garde++ < 2000) {
            String id = file.poll();
            for (String s : suivants.getOrDefault(id, List.of()))
                if (!colonne.containsKey(s) || colonne.get(s) < colonne.get(id) + 1 && colonne.get(id) + 1 < noeuds.size()) {
                    colonne.put(s, colonne.get(id) + 1);
                    file.add(s);
                }
        }
        StringBuilder x = new StringBuilder("<mxfile><diagram name=\"").append(esc(titre)).append("\"><mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>\n");
        int hauteur = 220;
        for (int i = 0; i < couloirs.size(); i++)
            x.append("<mxCell id=\"lane").append(i).append("\" value=\"").append(esc(couloirs.get(i)))
                    .append("\" style=\"text;html=1;horizontal=0;\" vertex=\"1\" parent=\"1\"><mxGeometry x=\"54\" y=\"").append(40 + i * hauteur)
                    .append("\" width=\"26\" height=\"").append(hauteur).append("\" as=\"geometry\"/></mxCell>\n");
        Map<Integer, Integer> parColonne = new HashMap<>();
        for (Map<String, Object> n : noeuds) {
            String id = String.valueOf(n.get("id"));
            String type = Objects.toString(n.get("type"), "TASK").toUpperCase(Locale.ROOT);
            int c = colonne.getOrDefault(id, 0), rang = parColonne.merge(c * 100 + couloirs.indexOf(Objects.toString(n.get("lane"), couloirs.getFirst())), 1, Integer::sum) - 1;
            int ligne = Math.max(0, couloirs.indexOf(Objects.toString(n.get("lane"), couloirs.getFirst())));
            String style;
            int l, h;
            switch (type) {
                case "START", "END", "EVENT" -> { style = "ellipse;" + (type.equals("END") ? "strokeWidth=4;" : ""); l = 32; h = 32; }
                case "GATEWAY" -> { style = "rhombus;"; l = 50; h = 50; }
                case "DOCUMENT" -> { style = "shape=document;"; l = 140; h = 60; }
                default -> { style = "rounded=1;"; l = 160; h = 60; }
            }
            double cx = 140 + c * 200, cy = 40 + ligne * hauteur + hauteur / 2.0 + rang * 70 - 30;
            x.append("<mxCell id=\"").append(esc(id)).append("\" value=\"").append(esc(Objects.toString(n.get("label"), ""))).append("\" style=\"")
                    .append(style).append("\" vertex=\"1\" parent=\"1\"><mxGeometry x=\"").append(cx - l / 2.0).append("\" y=\"").append(cy - h / 2.0)
                    .append("\" width=\"").append(l).append("\" height=\"").append(h).append("\" as=\"geometry\"/></mxCell>\n");
        }
        int k = 0;
        for (Map<String, Object> f : flux)
            x.append("<mxCell id=\"e").append(k++).append("\" value=\"").append(esc(Objects.toString(f.get("label"), ""))).append("\" style=\"\" edge=\"1\" parent=\"1\" source=\"")
                    .append(esc(String.valueOf(f.get("from")))).append("\" target=\"").append(esc(String.valueOf(f.get("to"))))
                    .append("\"><mxGeometry relative=\"1\" as=\"geometry\"/></mxCell>\n");
        return x.append("</root></mxGraphModel></diagram></mxfile>").toString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> liste(Object o) {
        List<Map<String, Object>> r = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x instanceof Map<?, ?> m) r.add((Map<String, Object>) m);
        return r;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
