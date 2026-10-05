package com.nexora.refdata.io;

import com.nexora.refdata.web.Json;
import com.nexora.refdata.web.RefdataException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Lecture d'un fichier CSV, JSON ou Excel en lignes « en-tête → valeur ». */
public record FichierTabulaire(String format, List<String> entetes, List<Map<String, String>> lignes) {

    public static FichierTabulaire lire(String nom, byte[] contenu, Json json) {
        String n = nom == null ? "" : nom.toLowerCase(Locale.ROOT);
        if (n.endsWith(".xlsx") || n.endsWith(".xlsm")) return excel(contenu);
        if (n.endsWith(".json")) return json(contenu, json);
        if (n.endsWith(".csv") || n.endsWith(".txt") || n.endsWith(".tsv")) return csv(texte(contenu));
        // Sans extension reconnue : détection par le contenu
        String t = texte(contenu).stripLeading();
        if (t.startsWith("[") || t.startsWith("{")) return json(contenu, json);
        if (contenu.length > 1 && contenu[0] == 'P' && contenu[1] == 'K') return excel(contenu);
        return csv(t);
    }

    /** UTF-8 (avec ou sans BOM), sinon Windows-1252 (fichiers Excel enregistrés en CSV). */
    static String texte(byte[] b) {
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
            return s.startsWith("﻿") ? s.substring(1) : s;
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("windows-1252"));
        }
    }

    static FichierTabulaire csv(String texte) {
        String premiere = texte.lines().findFirst().orElse("");
        char sep = ';';
        int pv = compter(premiere, ';'), v = compter(premiere, ','), tab = compter(premiere, '\t');
        if (tab > pv && tab > v) sep = '\t';
        else if (v > pv) sep = ',';
        List<List<String>> lignes = new ArrayList<>();
        List<String> ligne = new ArrayList<>();
        StringBuilder cellule = new StringBuilder();
        boolean guillemets = false;
        for (int i = 0; i < texte.length(); i++) {
            char c = texte.charAt(i);
            if (guillemets) {
                if (c == '"' && i + 1 < texte.length() && texte.charAt(i + 1) == '"') { cellule.append('"'); i++; }
                else if (c == '"') guillemets = false;
                else cellule.append(c);
            } else if (c == '"') guillemets = true;
            else if (c == sep) { ligne.add(cellule.toString()); cellule.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < texte.length() && texte.charAt(i + 1) == '\n') i++;
                ligne.add(cellule.toString()); cellule.setLength(0);
                lignes.add(ligne); ligne = new ArrayList<>();
            } else cellule.append(c);
        }
        if (!cellule.isEmpty() || !ligne.isEmpty()) { ligne.add(cellule.toString()); lignes.add(ligne); }
        return depuisGrille("CSV", lignes);
    }

    @SuppressWarnings("unchecked")
    static FichierTabulaire json(byte[] contenu, Json json) {
        Object racine;
        try {
            racine = json.mapper().readValue(contenu, Object.class);
        } catch (RuntimeException e) {
            throw RefdataException.invalide("Fichier JSON illisible : " + e.getMessage());
        }
        if (racine instanceof Map<?, ?> m && m.get("entries") instanceof List<?> l) racine = l;
        if (!(racine instanceof List<?> liste)) throw RefdataException.invalide("Le JSON doit contenir une liste de codes (ou un objet avec « entries »).");
        LinkedHashSet<String> entetes = new LinkedHashSet<>();
        List<Map<String, String>> lignes = new ArrayList<>();
        for (Object o : liste) {
            if (!(o instanceof Map<?, ?> objet)) continue;
            Map<String, String> l = new LinkedHashMap<>();
            for (var e : ((Map<String, Object>) objet).entrySet()) {
                if ("attributes".equals(e.getKey()) && e.getValue() instanceof Map<?, ?> attrs) {
                    attrs.forEach((k, v) -> { if (v != null) l.put(k.toString(), v.toString()); });
                } else if (e.getValue() != null && !(e.getValue() instanceof Map) && !(e.getValue() instanceof List)) {
                    l.put(e.getKey(), e.getValue().toString());
                }
            }
            entetes.addAll(l.keySet());
            lignes.add(l);
        }
        return new FichierTabulaire("JSON", List.copyOf(entetes), lignes);
    }

    static FichierTabulaire excel(byte[] contenu) {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(contenu))) {
            Sheet feuille = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.FRANCE);
            List<List<String>> grille = new ArrayList<>();
            for (Row r : feuille) {
                List<String> l = new ArrayList<>();
                for (int c = 0; c < Math.max(r.getLastCellNum(), 0); c++) {
                    Cell cell = r.getCell(c);
                    if (cell == null) l.add("");
                    else if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell))
                        l.add(cell.getLocalDateTimeCellValue().toLocalDate().toString());
                    else l.add(fmt.formatCellValue(cell));
                }
                grille.add(l);
            }
            return depuisGrille("XLSX", grille);
        } catch (IOException | RuntimeException e) {
            throw RefdataException.invalide("Fichier Excel illisible : " + e.getMessage());
        }
    }

    /** La première ligne renseignée sert d'en-tête. */
    private static FichierTabulaire depuisGrille(String format, List<List<String>> grille) {
        int i = 0;
        while (i < grille.size() && grille.get(i).stream().allMatch(String::isBlank)) i++;
        if (i >= grille.size()) return new FichierTabulaire(format, List.of(), List.of());
        List<String> entetes = grille.get(i).stream().map(String::strip).toList();
        List<Map<String, String>> lignes = new ArrayList<>();
        for (int k = i + 1; k < grille.size(); k++) {
            List<String> l = grille.get(k);
            if (l.stream().allMatch(String::isBlank)) continue;
            Map<String, String> m = new LinkedHashMap<>();
            for (int c = 0; c < entetes.size(); c++) if (!entetes.get(c).isEmpty()) m.put(entetes.get(c), c < l.size() ? l.get(c) : "");
            lignes.add(m);
        }
        return new FichierTabulaire(format, entetes.stream().filter(e -> !e.isEmpty()).toList(), lignes);
    }

    private static int compter(String s, char c) {
        int n = 0;
        boolean q = false;
        for (char x : s.toCharArray()) { if (x == '"') q = !q; else if (x == c && !q) n++; }
        return n;
    }
}
