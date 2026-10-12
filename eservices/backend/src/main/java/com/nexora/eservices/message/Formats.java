package com.nexora.eservices.message;

import com.nexora.eservices.deploiement.Paquet;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Formats de contenu des messages (JSON, XML, CSV, TXT) : sérialisation d'un message du catalogue à partir des valeurs
 * de ses champs, et extraction des valeurs d'un contenu reçu. Les chemins du catalogue sont des JSONPath simples
 * ({@code $.a.b}, {@code $.liste[*].x}), des chemins XML ({@code Racine/a/b}), des noms de colonnes CSV ou des clés TXT.
 */
public final class Formats {

    private Formats() {}

    public static String typeMime(String format) {
        return switch (format) {
            case "JSON" -> "application/json";
            case "XML" -> "application/xml";
            case "CSV" -> "text/csv";
            case "TXT" -> "text/plain";
            case "EDIFACT" -> "application/edifact";
            default -> "application/octet-stream";
        };
    }

    /** Segments d'un chemin, sans « $ » ni élément racine ; « x[*] » marque une liste. */
    static List<String> segments(String chemin, String racine) {
        String c = chemin.strip();
        List<String> s = new ArrayList<>();
        if (c.startsWith("$.")) s.addAll(List.of(c.substring(2).split("\\.")));
        else if (c.startsWith("$")) s.addAll(List.of(c.substring(1).split("\\.")));
        else s.addAll(List.of(c.split("[/.]")));
        if (racine != null && !s.isEmpty() && s.getFirst().equals(racine)) s.removeFirst();
        s.removeIf(String::isBlank);
        return s;
    }

    /** Nom court d'un chemin (dernier segment, sans marqueur de liste). */
    public static String cle(String chemin, String racine) {
        List<String> s = segments(chemin, racine);
        return s.isEmpty() ? chemin : s.getLast().replace("[*]", "");
    }

    // ------------------------------------------------------------------ sérialisation
    public static byte[] ecrire(Paquet.MessageDef def, Map<String, List<String>> valeurs, Map<String, String> libelles, String entete, JsonMapper json) {
        return switch (def.contentFormat()) {
            case "JSON" -> json.writerWithDefaultPrettyPrinter().writeValueAsBytes(versJson(def, valeurs, json));
            case "XML" -> versXml(def, valeurs).getBytes(StandardCharsets.UTF_8);
            case "CSV" -> versCsv(def, valeurs).getBytes(StandardCharsets.UTF_8);
            default -> versTexte(def, valeurs, libelles, entete).getBytes(StandardCharsets.UTF_8);
        };
    }

    private static ObjectNode versJson(Paquet.MessageDef def, Map<String, List<String>> valeurs, JsonMapper json) {
        ObjectNode racine = json.createObjectNode();
        for (Paquet.MessageField f : def.fields()) {
            List<String> v = valeurs.getOrDefault(f.path(), List.of());
            List<String> s = segments(f.path(), def.rootElement());
            if (s.isEmpty()) continue;
            int liste = -1;
            for (int i = 0; i < s.size(); i++) if (s.get(i).endsWith("[*]")) { liste = i; break; }
            if (liste < 0) {
                if (v.isEmpty()) continue;
                ObjectNode o = racine;
                for (int i = 0; i < s.size() - 1; i++) o = o.has(s.get(i)) ? (ObjectNode) o.get(s.get(i)) : o.putObject(s.get(i));
                o.put(s.getLast(), v.getFirst());
            } else {
                ObjectNode o = racine;
                for (int i = 0; i < liste; i++) o = o.has(s.get(i)) ? (ObjectNode) o.get(s.get(i)) : o.putObject(s.get(i));
                String nomListe = s.get(liste).replace("[*]", "");
                ArrayNode a = o.has(nomListe) ? (ArrayNode) o.get(nomListe) : o.putArray(nomListe);
                for (int k = 0; k < v.size(); k++) {
                    while (a.size() <= k) a.addObject();
                    ObjectNode e = (ObjectNode) a.get(k);
                    for (int i = liste + 1; i < s.size() - 1; i++) e = e.has(s.get(i)) ? (ObjectNode) e.get(s.get(i)) : e.putObject(s.get(i));
                    if (v.get(k) != null) e.put(s.getLast(), v.get(k));
                }
            }
        }
        return racine;
    }

    private static String versXml(Paquet.MessageDef def, Map<String, List<String>> valeurs) {
        String racine = def.rootElement() == null || def.rootElement().isBlank() ? def.code() : def.rootElement();
        // Arbre d'éléments ordonné : chemin → valeur(s)
        Map<String, Object> arbre = new LinkedHashMap<>();
        for (Paquet.MessageField f : def.fields()) {
            List<String> v = valeurs.getOrDefault(f.path(), List.of());
            List<String> s = segments(f.path(), racine);
            if (s.isEmpty() || v.isEmpty()) continue;
            int liste = -1;
            for (int i = 0; i < s.size(); i++) if (s.get(i).endsWith("[*]")) { liste = i; break; }
            if (liste < 0) placer(arbre, s, v.getFirst());
            else {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> elements = (List<Map<String, Object>>) arbre.computeIfAbsent(String.join("/", s.subList(0, liste + 1)), k -> new ArrayList<Map<String, Object>>());
                for (int k = 0; k < v.size(); k++) {
                    while (elements.size() <= k) elements.add(new LinkedHashMap<>());
                    placer(elements.get(k), s.subList(liste + 1, s.size()), v.get(k));
                }
            }
        }
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<").append(racine).append(">\n");
        ecrireXml(x, arbre, 1);
        return x.append("</").append(racine).append(">\n").toString();
    }

    @SuppressWarnings("unchecked")
    private static void placer(Map<String, Object> arbre, List<String> s, String valeur) {
        Map<String, Object> m = arbre;
        for (int i = 0; i < s.size() - 1; i++) m = (Map<String, Object>) m.computeIfAbsent(s.get(i), k -> new LinkedHashMap<String, Object>());
        if (valeur != null) m.put(s.getLast(), valeur);
    }

    @SuppressWarnings("unchecked")
    private static void ecrireXml(StringBuilder x, Map<String, Object> m, int niveau) {
        String ind = "  ".repeat(niveau);
        for (var e : m.entrySet()) {
            if (e.getValue() instanceof List<?> l) {
                String[] p = e.getKey().split("/");
                String nom = p[p.length - 1].replace("[*]", "");
                for (int i = 0; i < p.length - 1; i++) x.append(ind).append("<").append(p[i]).append(">\n");
                for (Object o : l) {
                    x.append(ind).append("<").append(nom).append(">\n");
                    ecrireXml(x, (Map<String, Object>) o, niveau + 1);
                    x.append(ind).append("</").append(nom).append(">\n");
                }
                for (int i = p.length - 2; i >= 0; i--) x.append(ind).append("</").append(p[i]).append(">\n");
            } else if (e.getValue() instanceof Map<?, ?> sous) {
                x.append(ind).append("<").append(e.getKey()).append(">\n");
                ecrireXml(x, (Map<String, Object>) sous, niveau + 1);
                x.append(ind).append("</").append(e.getKey()).append(">\n");
            } else {
                x.append(ind).append("<").append(e.getKey()).append(">").append(echapperXml(String.valueOf(e.getValue())))
                        .append("</").append(e.getKey()).append(">\n");
            }
        }
    }

    private static String versCsv(Paquet.MessageDef def, Map<String, List<String>> valeurs) {
        List<String> colonnes = def.fields().stream().map(f -> cle(f.path(), def.rootElement())).toList();
        int lignes = def.fields().stream().mapToInt(f -> valeurs.getOrDefault(f.path(), List.of()).size()).max().orElse(0);
        StringBuilder s = new StringBuilder(String.join(";", colonnes)).append("\r\n");
        for (int i = 0; i < lignes; i++) {
            List<String> l = new ArrayList<>();
            for (Paquet.MessageField f : def.fields()) {
                List<String> v = valeurs.getOrDefault(f.path(), List.of());
                // Une valeur unique (en-tête) est répétée sur chaque ligne
                l.add(csv(v.isEmpty() ? "" : v.size() == 1 ? v.getFirst() : i < v.size() ? v.get(i) : ""));
            }
            s.append(String.join(";", l)).append("\r\n");
        }
        return s.toString();
    }

    private static String versTexte(Paquet.MessageDef def, Map<String, List<String>> valeurs, Map<String, String> libelles, String entete) {
        StringBuilder s = new StringBuilder(entete == null ? def.labelFr() : entete).append("\n").append("=".repeat(60)).append("\n");
        for (Paquet.MessageField f : def.fields()) {
            String cle = cle(f.path(), def.rootElement());
            s.append(cle).append(": ").append(String.join(" | ", valeurs.getOrDefault(f.path(), List.of())).replace("\n", " ")).append("\n");
        }
        if (libelles != null && !libelles.isEmpty()) {
            s.append("-".repeat(60)).append("\n");
            libelles.forEach((k, v) -> s.append("# ").append(k).append(" = ").append(v).append("\n"));
        }
        return s.toString();
    }

    private static String csv(String v) {
        if (v == null) return "";
        return v.contains(";") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static String echapperXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------ extraction (messages entrants)
    public static Map<String, List<String>> lire(Paquet.MessageDef def, byte[] contenu, JsonMapper json) {
        Map<String, List<String>> r = new LinkedHashMap<>();
        String texte = new String(contenu, StandardCharsets.UTF_8).replace("﻿", "");
        switch (def.contentFormat()) {
            case "JSON" -> {
                JsonNode racine = json.readTree(texte);
                for (Paquet.MessageField f : def.fields()) r.put(f.path(), valeursJson(racine, segments(f.path(), def.rootElement())));
            }
            case "XML" -> {
                Element racine = xml(contenu).getDocumentElement();
                String nomRacine = def.rootElement() == null ? racine.getLocalName() : def.rootElement();
                if (!nomRacine.equals(racine.getTagName()) && !nomRacine.equals(racine.getLocalName()))
                    throw new IllegalArgumentException("Élément racine attendu : " + nomRacine + " (reçu : " + racine.getTagName() + ").");
                for (Paquet.MessageField f : def.fields()) {
                    List<String> v = new ArrayList<>();
                    valeursXml(racine, segments(f.path(), nomRacine), 0, v);
                    r.put(f.path(), v);
                }
            }
            case "CSV" -> {
                List<String> lignes = texte.lines().filter(l -> !l.isBlank()).toList();
                if (lignes.isEmpty()) break;
                String sep = lignes.getFirst().contains(";") ? ";" : ",";
                List<String> entete = List.of(lignes.getFirst().split(sep, -1));
                for (Paquet.MessageField f : def.fields()) {
                    int col = entete.indexOf(cle(f.path(), def.rootElement()));
                    List<String> v = new ArrayList<>();
                    if (col >= 0) for (String l : lignes.subList(1, lignes.size())) {
                        String[] c = l.split(sep, -1);
                        v.add(col < c.length ? c[col].replaceAll("^\"|\"$", "") : "");
                    }
                    r.put(f.path(), v);
                }
            }
            default -> {
                Map<String, String> cles = new HashMap<>();
                for (String l : texte.lines().toList()) {
                    int i = l.indexOf(':');
                    if (i < 0) i = l.indexOf('=');
                    if (i > 0) cles.put(l.substring(0, i).strip(), l.substring(i + 1).strip());
                }
                for (Paquet.MessageField f : def.fields()) {
                    String v = cles.get(cle(f.path(), def.rootElement()));
                    r.put(f.path(), v == null ? List.of() : List.of(v));
                }
            }
        }
        return r;
    }

    private static List<String> valeursJson(JsonNode n, List<String> s) {
        List<JsonNode> courants = List.of(n);
        for (String seg : s) {
            List<JsonNode> suivants = new ArrayList<>();
            boolean liste = seg.endsWith("[*]");
            String nom = seg.replace("[*]", "");
            for (JsonNode c : courants) {
                JsonNode x = c.get(nom);
                if (x == null || x.isNull()) continue;
                if (liste && x.isArray()) x.forEach(suivants::add);
                else suivants.add(x);
            }
            courants = suivants;
        }
        return courants.stream().map(x -> x.isValueNode() ? x.asString() : x.toString()).toList();
    }

    private static void valeursXml(Element e, List<String> s, int i, List<String> sortie) {
        if (i == s.size()) {
            sortie.add(e.getTextContent().strip());
            return;
        }
        String nom = s.get(i).replace("[*]", "");
        NodeList enfants = e.getChildNodes();
        for (int k = 0; k < enfants.getLength(); k++) {
            Node n = enfants.item(k);
            if (n instanceof Element c && (nom.equals(c.getLocalName()) || nom.equals(c.getTagName()))) valeursXml(c, s, i + 1, sortie);
        }
    }

    /** Analyseur XML sécurisé : ni DTD ni entités externes (XXE). */
    static Document xml(byte[] contenu) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(true);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(contenu));
        } catch (Exception e) {
            throw new IllegalArgumentException("XML illisible : " + e.getMessage());
        }
    }
}
