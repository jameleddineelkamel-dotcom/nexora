package com.nexora.refdata.io;

import com.nexora.refdata.catalogue.CatalogueModel.ColumnDef;
import com.nexora.refdata.catalogue.CatalogueModel.TableDef;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImportFormatsTest {

    @Test
    void lectureCsvAvecGuillemetsPointVirguleEtBom() {
        String csv = "﻿code;libellé;description\r\nCREDOC/nM;\"Crédit; confirmé\";\"Ligne \"\"citée\"\"\"\r\n\r\nCASH;Comptant;\r\n";
        FichierTabulaire f = FichierTabulaire.csv(FichierTabulaire.texte(csv.getBytes(StandardCharsets.UTF_8)));
        assertThat(f.entetes()).containsExactly("code", "libellé", "description");
        assertThat(f.lignes()).hasSize(2);
        assertThat(f.lignes().getFirst()).containsEntry("code", "CREDOC/nM").containsEntry("libellé", "Crédit; confirmé")
                .containsEntry("description", "Ligne \"citée\"");
    }

    @Test
    void lectureWindows1252() {
        byte[] latin = "code,nom\nCI,Côte d'Ivoire\n".getBytes(java.nio.charset.Charset.forName("windows-1252"));
        FichierTabulaire f = FichierTabulaire.csv(FichierTabulaire.texte(latin));
        assertThat(f.lignes().getFirst()).containsEntry("nom", "Côte d'Ivoire");
    }

    @Test
    void correspondanceDesEntetesParCleLibelleBaliseOuCrochets() {
        ColumnDef code = new ColumnDef("CountryIdAlpha2", "Code Pays (Alpha-2)", null, "CODE", "STRING", 2, null, true, null, "CountryIdAlpha2", null, null, null, null, null, 1);
        ColumnDef fr = new ColumnDef("CountryNameFr", "Nom Pays Court (FR)", null, "LABEL_FR", "STRING", 35, null, true, null, null, null, null, null, null, null, 2);
        ColumnDef num = new ColumnDef("CountryIdNum", "Code Pays (Numeric)", null, "ATTRIBUTE", "STRING", 3, null, false, null, null, null, null, null, null, null, 3);
        TableDef t = new TableDef("REF_COUNTRY", null, "Pays", null, null, "INT", "INTERNATIONALE", null, null, null, null, null, List.of(),
                null, Map.of(), null, null, "ACTIVE", List.of(code, fr, num), 0L, null, null, 0, 0, 0, null, null);
        Map<String, String> m = ImportService.correspondance(t, List.of("Code Pays (Alpha-2)", "libellé", "Numérique [CountryIdNum]", "Statut", "Colonne X"));
        assertThat(m).containsEntry("Code Pays (Alpha-2)", "code").containsEntry("libellé", "labelFr")
                .containsEntry("Numérique [CountryIdNum]", "attr:CountryIdNum").containsEntry("Statut", "status")
                .doesNotContainKey("Colonne X");
    }
}
