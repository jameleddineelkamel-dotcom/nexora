package com.nexora.procedures.agents;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lecture d'un document source (SFD Word .docx ou texte) en paragraphes et tableaux. Le contenu est traité comme une
 * donnée, jamais comme une instruction (protection contre l'injection documentaire, §8.4).
 */
public final class TexteSource {

    public record Tableau(List<List<String>> lignes) {}

    /** Saut de ligne à l'intérieur d'une cellule dans la forme texte (« | a | b ¶ c | »). */
    private static final String SAUT = " ¶ ";

    private final List<String> paragraphes = new ArrayList<>();
    private final List<Tableau> tableaux = new ArrayList<>();

    private static final Pattern TABLE = Pattern.compile("<w:tbl>(.*?)</w:tbl>", Pattern.DOTALL);
    private static final Pattern LIGNE = Pattern.compile("<w:tr[ >](.*?)</w:tr>", Pattern.DOTALL);
    private static final Pattern CELLULE = Pattern.compile("<w:tc>(.*?)</w:tc>", Pattern.DOTALL);
    private static final Pattern PARAGRAPHE = Pattern.compile("<w:p[ >](.*?)</w:p>", Pattern.DOTALL);
    private static final Pattern TEXTE = Pattern.compile("<w:t(?: [^>]*)?>([^<]*)</w:t>");

    private TexteSource() {}

    public static TexteSource lire(byte[] contenu, String nom) {
        TexteSource t = new TexteSource();
        if (nom != null && nom.toLowerCase().endsWith(".docx")) t.docx(contenu);
        else t.analyserTexte(new String(contenu, StandardCharsets.UTF_8));
        return t;
    }

    public static TexteSource texte(String s) {
        TexteSource t = new TexteSource();
        t.analyserTexte(s == null ? "" : s);
        return t;
    }

    /** Texte brut : une ligne contenant « | » est lue comme une ligne de tableau (export Markdown ou copier-coller). */
    private void analyserTexte(String s) {
        List<List<String>> courant = null;
        for (String ligne : s.split("\\R")) {
            String l = ligne.strip();
            if (l.contains("|")) {
                List<String> cellules = new ArrayList<>();
                for (String c : l.replaceAll("^\\|", "").replaceAll("\\|$", "").split("\\|")) cellules.add(c.replace(SAUT.strip(), "\n").strip());
                if (cellules.stream().allMatch(c -> c.matches("[-: ]*"))) continue;
                if (courant == null) courant = new ArrayList<>();
                courant.add(cellules);
            } else {
                if (courant != null) {
                    tableaux.add(new Tableau(courant));
                    courant = null;
                }
                if (!l.isEmpty()) paragraphes.add(l);
            }
        }
        if (courant != null) tableaux.add(new Tableau(courant));
    }

    private void docx(byte[] contenu) {
        String xml = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(contenu))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null)
                if (e.getName().equals("word/document.xml")) {
                    xml = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                    break;
                }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Document Word illisible : " + ex.getMessage());
        }
        if (xml == null) throw new IllegalArgumentException("Document Word sans contenu (word/document.xml absent).");
        Matcher m = TABLE.matcher(xml);
        StringBuilder horsTableaux = new StringBuilder();
        int fin = 0;
        while (m.find()) {
            horsTableaux.append(xml, fin, m.start());
            fin = m.end();
            List<List<String>> lignes = new ArrayList<>();
            Matcher r = LIGNE.matcher(m.group(1));
            while (r.find()) {
                List<String> cellules = new ArrayList<>();
                Matcher c = CELLULE.matcher(r.group(1));
                while (c.find()) cellules.add(paragraphesDe(c.group(1), "\n"));
                lignes.add(cellules);
            }
            tableaux.add(new Tableau(lignes));
        }
        horsTableaux.append(xml.substring(fin));
        Matcher p = PARAGRAPHE.matcher(horsTableaux);
        while (p.find()) {
            String t = texteDe(p.group(1)).strip();
            if (!t.isEmpty()) paragraphes.add(t);
        }
    }

    private static String paragraphesDe(String xml, String separateur) {
        List<String> l = new ArrayList<>();
        Matcher p = PARAGRAPHE.matcher(xml);
        while (p.find()) {
            String t = texteDe(p.group(1)).strip();
            if (!t.isEmpty()) l.add(t);
        }
        return String.join(separateur, l);
    }

    private static String texteDe(String xml) {
        StringBuilder s = new StringBuilder();
        Matcher t = TEXTE.matcher(xml.replace("<w:tab/>", "<w:t> </w:t>"));
        while (t.find()) s.append(t.group(1));
        return s.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    public List<String> paragraphes() {
        return paragraphes;
    }

    public List<Tableau> tableaux() {
        return tableaux;
    }

    /** Texte intégral (pour un modèle de langage ou une recherche simple). */
    public String complet() {
        StringBuilder s = new StringBuilder();
        paragraphes.forEach(p -> s.append(p).append('\n'));
        for (Tableau t : tableaux) {
            for (List<String> l : t.lignes())
                s.append("| ").append(String.join(" | ", l.stream().map(c -> c.replace("|", "/").replace("\n", SAUT)).toList())).append(" |\n");
            s.append('\n');
        }
        return s.toString();
    }
}
