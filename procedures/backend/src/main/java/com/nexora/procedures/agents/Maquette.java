package com.nexora.procedures.agents;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maquettes d'écrans (wireframes HTML) d'une SFD : écrans, blocs (panneaux), champs (libellé, obligation, lecture
 * seule / auto-renseigné / calculé, liste d'options, format indicatif « AN..30 », « N..100 », « JJ/MM/AAAA ») et
 * tableaux (groupes répétitifs). Lecture tolérante du balisage courant (div.screen, h1, div.panel-head, label,
 * input / select / textarea, table / th). Le HTML est une donnée : aucun script n'est interprété.
 */
final class Maquette {

    record Champ(String libelle, boolean obligatoire, boolean lectureSeule, String nature, List<String> options, String format, String aide) {}

    record Bloc(String titre, boolean obligatoire, List<Champ> champs, boolean tableau) {}

    record Ecran(String titre, List<Bloc> blocs) {
        int nombreDeChamps() {
            return blocs.stream().mapToInt(b -> b.champs().size()).sum();
        }
    }

    /** Classe « screen » exacte (pas « screen-header » ni « screen-body »). */
    private static final Pattern ECRAN = Pattern.compile("<div[^>]*class=\"(?:[^\"]*\\s)?screen(?:\\s[^\"]*)?\"[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITRE = Pattern.compile("<h1[^>]*>([\\s\\S]*?)</h1>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOC = Pattern.compile("<div[^>]*class=\"[^\"]*\\b(panel-head|section-title)\\b[^\"]*\"[^>]*>([\\s\\S]*?)</div>", Pattern.CASE_INSENSITIVE);
    /** Libellé suivi de sa saisie ; la liste d'un select est bornée à son propre « </select> » (pas de débordement sur les champs suivants). */
    private static final Pattern CHAMP = Pattern.compile("<label[^>]*>([\\s\\S]*?)</label>\\s*(<(select)\\b[^>]*>[\\s\\S]*?</select>|<(input|textarea)\\b[^>]*>)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLEAU = Pattern.compile("<table[^>]*>([\\s\\S]*?)</table>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TH = Pattern.compile("<th[^>]*>([\\s\\S]*?)</th>", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPTION = Pattern.compile("<option[^>]*>([^<]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AIDE = Pattern.compile("^\\s*<div[^>]*class=\"[^\"]*\\bhint\\b[^\"]*\"[^>]*>([\\s\\S]*?)</div>", Pattern.CASE_INSENSITIVE);

    private final List<Ecran> ecrans;

    private Maquette(List<Ecran> ecrans) {
        this.ecrans = ecrans;
    }

    static boolean estMaquette(String nom, String contenu) {
        String n = nom == null ? "" : nom.toLowerCase(Locale.ROOT);
        return (n.endsWith(".html") || n.endsWith(".htm")) && contenu != null && contenu.toLowerCase(Locale.ROOT).contains("<label");
    }

    static Maquette lire(String html) {
        String h = html.replaceAll("(?is)<script\\b.*?</script>", "").replaceAll("(?is)<style\\b.*?</style>", "");
        List<Integer> debuts = new ArrayList<>();
        Matcher e = ECRAN.matcher(h);
        while (e.find()) debuts.add(e.start());
        if (debuts.isEmpty()) debuts.add(0);
        List<Ecran> ecrans = new ArrayList<>();
        for (int i = 0; i < debuts.size(); i++) {
            String s = h.substring(debuts.get(i), i + 1 < debuts.size() ? debuts.get(i + 1) : h.length());
            Matcher t = TITRE.matcher(s);
            String titre = t.find() ? texte(t.group(1)) : "Écran " + (i + 1);
            ecrans.add(new Ecran(titre, blocs(s)));
        }
        ecrans.removeIf(x -> x.nombreDeChamps() == 0);
        return new Maquette(ecrans);
    }

    private static List<Bloc> blocs(String s) {
        List<int[]> positions = new ArrayList<>();
        List<String> titres = new ArrayList<>();
        Matcher b = BLOC.matcher(s);
        while (b.find()) {
            positions.add(new int[]{b.start(), b.end()});
            titres.add(b.group(2));
        }
        List<Bloc> r = new ArrayList<>();
        if (positions.isEmpty()) {
            Bloc x = bloc("Formulaire", s);
            if (!x.champs().isEmpty()) r.add(x);
            return r;
        }
        for (int i = 0; i < positions.size(); i++) {
            String corps = s.substring(positions.get(i)[1], i + 1 < positions.size() ? positions.get(i + 1)[0] : s.length());
            Bloc x = bloc(titres.get(i), corps);
            if (!x.champs().isEmpty()) r.add(x);
        }
        return r;
    }

    private static Bloc bloc(String titreHtml, String corps) {
        boolean obligatoire = titreHtml.contains("req");
        String titre = texte(titreHtml.replaceAll("(?is)<span[^>]*>.*?</span>", "")).replaceAll("\\*", "").strip();
        List<Champ> champs = new ArrayList<>();
        // Tableau : chaque colonne saisissable est un champ du groupe répétitif
        Matcher tb = TABLEAU.matcher(corps);
        if (tb.find()) {
            List<String> colonnes = new ArrayList<>();
            Matcher th = TH.matcher(tb.group(1));
            while (th.find()) colonnes.add(texte(th.group(1)));
            List<String[]> saisies = new ArrayList<>();
            Matcher td = Pattern.compile("<td[^>]*>([\\s\\S]*?)</td>", Pattern.CASE_INSENSITIVE).matcher(tb.group(1));
            int c = 0;
            while (td.find() && c < colonnes.size()) {
                Matcher in = Pattern.compile("<(input|select|textarea)\\b([^>]*)>", Pattern.CASE_INSENSITIVE).matcher(td.group(1));
                saisies.add(in.find() ? new String[]{in.group(1).toLowerCase(Locale.ROOT), attribut(in.group(2), "placeholder")} : null);
                c++;
            }
            for (int i = 0; i < colonnes.size() && i < saisies.size(); i++) {
                String l = colonnes.get(i);
                if (saisies.get(i) == null || l.isBlank() || l.matches("N°|#|No")) continue;
                champs.add(new Champ(l, false, false, saisies.get(i)[0], List.of(), saisies.get(i)[1], null));
            }
            return new Bloc(titre, obligatoire, champs, true);
        }
        Matcher m = CHAMP.matcher(corps);
        while (m.find()) {
            String label = m.group(1);
            boolean requis = label.contains("req") || label.contains("*");
            String libelle = texte(label.replaceAll("(?is)<span[^>]*>.*?</span>", "")).replace("*", "").strip();
            String balise = m.group(2), nature = (m.group(3) != null ? m.group(3) : m.group(4)).toLowerCase(Locale.ROOT);
            String type = attribut(balise, "type");
            if ("checkbox".equals(type) || "radio".equals(type) || "file".equals(type) || "hidden".equals(type) || libelle.isBlank()) continue;
            boolean lecture = balise.toLowerCase(Locale.ROOT).contains("readonly") || balise.toLowerCase(Locale.ROOT).contains("disabled");
            List<String> options = new ArrayList<>();
            if (nature.equals("select")) {
                Matcher o = OPTION.matcher(balise);
                while (o.find()) {
                    String v = texte(o.group(1));
                    if (!v.isBlank() && !v.startsWith("—") && !DocumentAgent.normaliser(v).startsWith("selectionner")) options.add(v);
                }
            }
            Matcher a = AIDE.matcher(corps.substring(m.end()));
            champs.add(new Champ(libelle, requis, lecture, nature, options, attribut(balise, "placeholder"), a.find() ? texte(a.group(1)) : null));
        }
        return new Bloc(titre, obligatoire, champs, false);
    }

    /** Maquette construite par une autre source (export Figma, image lue par le modèle de vision). */
    static Maquette de(List<Ecran> ecrans) {
        return new Maquette(new ArrayList<>(ecrans.stream().filter(e -> e.nombreDeChamps() > 0).toList()));
    }

    List<Ecran> ecrans() {
        return ecrans;
    }

    private static String attribut(String balise, String nom) {
        Matcher m = Pattern.compile("\\b" + nom + "=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(balise);
        return m.find() ? texte(m.group(1)) : null;
    }

    static String texte(String html) {
        return DrawioSource.texte(html);
    }
}
