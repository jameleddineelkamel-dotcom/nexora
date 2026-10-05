package com.nexora.refdata.io;

import com.nexora.refdata.catalogue.CatalogueModel;
import com.nexora.refdata.catalogue.CatalogueModel.ColumnDef;
import com.nexora.refdata.catalogue.CatalogueModel.TableDef;
import com.nexora.refdata.catalogue.CatalogueRepository;
import com.nexora.refdata.catalogue.CatalogueService;
import com.nexora.refdata.entry.Entry;
import com.nexora.refdata.entry.EntryRepository;
import com.nexora.refdata.web.Json;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;

/** Export des listes de codes en CSV, JSON ou Excel (fichiers ré-importables). */
@Service
public class ExportService {

    private final CatalogueService catalogue;
    private final CatalogueRepository catalogueRepo;
    private final EntryRepository entrees;
    private final Json json;

    public ExportService(CatalogueService catalogue, CatalogueRepository catalogueRepo, EntryRepository entrees, Json json) {
        this.catalogue = catalogue;
        this.catalogueRepo = catalogueRepo;
        this.entrees = entrees;
        this.json = json;
    }

    /** Colonnes exportées : structure de la table puis parent, validité et statut. */
    private static List<String[]> colonnes(TableDef t) {
        List<String[]> c = new ArrayList<>();
        for (ColumnDef col : t.columns()) c.add(new String[]{col.key(), col.labelFr(), col.role()});
        if (t.parentTableCode() != null) c.add(new String[]{"parentCode", "Code parent", "PARENT"});
        c.add(new String[]{"validFrom", "Début de validité", "VALID_FROM"});
        c.add(new String[]{"validTo", "Fin de validité", "VALID_TO"});
        c.add(new String[]{"status", "Statut", "STATUS"});
        return c;
    }

    private static String valeur(Entry e, String[] col) {
        Object v = switch (col[2]) {
            case "CODE" -> e.code();
            case "LABEL_FR" -> e.labelFr();
            case "LABEL_EN" -> e.labelEn();
            case "PARENT" -> e.parentCode();
            case "VALID_FROM" -> e.validFrom();
            case "VALID_TO" -> e.validTo();
            case "STATUS" -> e.status();
            default -> e.attributes() == null ? null : e.attributes().get(col[0]);
        };
        return v == null ? "" : v.toString();
    }

    @Transactional(readOnly = true)
    public void csv(String table, String statut, OutputStream out) throws IOException {
        TableDef t = catalogue.table(table);
        List<String[]> cols = colonnes(t);
        Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        w.write('﻿');
        w.write(String.join(";", cols.stream().map(c -> cellule(c[0])).toList()));
        w.write("\r\n");
        entrees.parcourir(catalogueRepo.idDe(t.code()), statut, e -> {
            try {
                w.write(String.join(";", cols.stream().map(c -> cellule(valeur(e, c))).toList()));
                w.write("\r\n");
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
        w.flush();
    }

    @Transactional(readOnly = true)
    public void json(String table, String statut, OutputStream out) throws IOException {
        TableDef t = catalogue.table(table);
        List<Entry> liste = new ArrayList<>();
        entrees.parcourir(catalogueRepo.idDe(t.code()), statut, liste::add);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", "nexora-refdata-entries/1");
        doc.put("table", Map.of("code", t.code(), "nameFr", t.nameFr(), "standards", t.standards() == null ? "" : t.standards()));
        doc.put("exportedAt", OffsetDateTime.now().toString());
        doc.put("entries", liste.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", e.code());
            m.put("labelFr", e.labelFr());
            m.put("labelEn", e.labelEn());
            if (e.parentCode() != null) m.put("parentCode", e.parentCode());
            m.put("validFrom", e.validFrom() == null ? null : e.validFrom().toString());
            m.put("validTo", e.validTo() == null ? null : e.validTo().toString());
            m.put("status", e.status());
            m.put("attributes", e.attributes());
            return m;
        }).toList());
        out.write(json.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(doc));
    }

    /** Classeur Excel : données (en flux, adapté à UN/LOCODE), structure et fiche ISO 19115. */
    @Transactional(readOnly = true)
    public void excel(String table, String statut, OutputStream out) throws IOException {
        TableDef t = catalogue.table(table);
        List<String[]> cols = colonnes(t);
        try (SXSSFWorkbook wb = new SXSSFWorkbook(500)) {
            CellStyle entete = wb.createCellStyle();
            Font gras = wb.createFont();
            gras.setBold(true);
            gras.setColor(IndexedColors.WHITE.getIndex());
            entete.setFont(gras);
            entete.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            entete.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // En-tête « Libellé [clé] » : lisible et ré-importable (la clé entre crochets est reconnue à l'import)
            SXSSFSheet donnees = wb.createSheet("Données");
            Row titresDonnees = donnees.createRow(0);
            for (int i = 0; i < cols.size(); i++) {
                Cell k = titresDonnees.createCell(i);
                k.setCellValue(cols.get(i)[1] + " [" + cols.get(i)[0] + "]");
                k.setCellStyle(entete);
            }
            donnees.createFreezePane(1, 1);
            int[] n = {1};
            entrees.parcourir(catalogueRepo.idDe(t.code()), statut, e -> {
                Row r = donnees.createRow(n[0]++);
                for (int i = 0; i < cols.size(); i++) r.createCell(i).setCellValue(valeur(e, cols.get(i)));
            });

            Sheet structure = wb.createSheet("Structure");
            String[] titres = {"Clé", "Libellé", "Rôle", "Type", "Longueur", "Obligatoire", "Balise XML", "UNTDED", "Référence", "Définition"};
            Row h = structure.createRow(0);
            for (int i = 0; i < titres.length; i++) { Cell c = h.createCell(i); c.setCellValue(titres[i]); c.setCellStyle(entete); }
            int ligne = 1;
            for (ColumnDef c : t.columns()) {
                Row r = structure.createRow(ligne++);
                Object[] v = {c.key(), c.labelFr(), c.role(), c.dataType(), c.maxLength(), c.required() ? "Oui" : "Non", c.xmlTag(),
                        c.untded(), c.refTableCode(), c.definition()};
                for (int i = 0; i < v.length; i++) r.createCell(i).setCellValue(v[i] == null ? "" : v[i].toString());
            }

            Sheet fiche = wb.createSheet("Métadonnées ISO 19115");
            Row h2 = fiche.createRow(0);
            for (int i = 0; i < 3; i++) { Cell c = h2.createCell(i); c.setCellValue(new String[]{"Rubrique", "Champ", "Valeur"}[i]); c.setCellStyle(entete); }
            int l = 1;
            for (String[] p : List.of(new String[]{"IDENTIFICATION", "Code", t.code()}, new String[]{"IDENTIFICATION", "Nom", t.nameFr()},
                    new String[]{"IDENTIFICATION", "Catégorie", t.categoryCode()}, new String[]{"IDENTIFICATION", "Source", t.source()},
                    new String[]{"IDENTIFICATION", "Phases Buy-Ship-Pay", String.join(", ", t.bspPhases())})) {
                Row r = fiche.createRow(l++);
                for (int i = 0; i < 3; i++) r.createCell(i).setCellValue(p[i] == null ? "" : p[i]);
            }
            for (CatalogueModel.ChampMetadonnee c : CatalogueModel.FICHE_ISO_19115) {
                Object v = switch (c.key()) {
                    case "description" -> t.description();
                    case "standards" -> t.standards();
                    case "producer" -> t.producer();
                    case "updateAuthority" -> t.updateAuthority();
                    case "obtentionMode" -> t.obtentionMode();
                    case "updateMode" -> t.updateMode();
                    default -> t.metadata().get(c.key());
                };
                Row r = fiche.createRow(l++);
                r.createCell(0).setCellValue(c.group());
                r.createCell(1).setCellValue(c.labelFr());
                r.createCell(2).setCellValue(v == null ? "" : v.toString());
            }
            fiche.setColumnWidth(1, 14000);
            fiche.setColumnWidth(2, 30000);
            wb.write(out);
        }
    }

    private static String cellule(String v) {
        return v.matches("(?s).*[\";\\r\\n].*") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
