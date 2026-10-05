package com.nexora.refdata.entry;

import com.nexora.refdata.catalogue.CatalogueModel.ColumnDef;
import com.nexora.refdata.catalogue.CatalogueModel.TableDef;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EntryValidatorTest {

    private static ColumnDef col(String key, String role, String type, Integer len, boolean req, String ref) {
        return new ColumnDef(key, key, null, role, type, len, null, req, null, null, null, null, null, null, ref, 0);
    }

    /** Table type REF_UNLOCODE : code, libellés, référence pays, attributs typés. */
    private static TableDef table(String parent) {
        return new TableDef("REF_TEST", "9.99", "Test", null, null, "INT", "INTERNATIONALE", null, null, null, null, null,
                List.of("SHIP"), parent, Map.of(), null, null, "ACTIVE",
                List.of(col("LocationCode", "CODE", "STRING", 5, true, null), col("LocationName", "LABEL_FR", "STRING", 50, true, null),
                        col("LocationNameEn", "LABEL_EN", "STRING", 50, false, null),
                        col("CountryIdAlpha2", "ATTRIBUTE", "CODE_REF", 2, true, "REF_COUNTRY"),
                        col("Capacity", "ATTRIBUTE", "DECIMAL", null, false, null), col("Berths", "ATTRIBUTE", "INTEGER", null, false, null),
                        col("Opened", "ATTRIBUTE", "DATE", null, false, null), col("IsPort", "ATTRIBUTE", "BOOLEAN", null, false, null)),
                0L, null, null, 0, 0, 0, "COMPLEXE", null);
    }

    private static final EntryValidator.Resolveur PAYS = (t, c) -> "REF_COUNTRY".equals(t) && Set.of("CM", "FR").contains(c)
            || "REF_TEST".equals(t) && "CMDLA".equals(c);

    private static Entry entree(String code, String fr, String parent, Map<String, Object> a) {
        return new Entry(code, fr, null, parent, a, null, null, null, null, null, null, null);
    }

    @Test
    void normaliseLesValeursTypees() {
        var r = EntryValidator.valider(table(null), entree(" CMDLA ", "Douala", null,
                Map.of("CountryIdAlpha2", "CM", "Capacity", "1 250,5", "Berths", "12", "Opened", "01/07/1881", "IsPort", "oui")), PAYS);
        assertThat(r.erreurs()).isEmpty();
        assertThat(r.entree().code()).isEqualTo("CMDLA");
        assertThat(r.entree().status()).isEqualTo("ACTIVE");
        assertThat(r.entree().attributes()).containsEntry("Capacity", new BigDecimal("1250.5")).containsEntry("Berths", 12L)
                .containsEntry("Opened", "1881-07-01").containsEntry("IsPort", true);
    }

    @Test
    void controleObligatoiresLongueursEtReferences() {
        var r = EntryValidator.valider(table(null), entree("TOOLONG", null, null, Map.of("CountryIdAlpha2", "XX", "Berths", "douze")), PAYS);
        assertThat(r.erreurs())
                .anyMatch(m -> m.contains("7 caractères pour 5"))
                .anyMatch(m -> m.contains("LocationName") && m.contains("obligatoire"))
                .anyMatch(m -> m.contains("« XX » inconnue dans REF_COUNTRY"))
                .anyMatch(m -> m.contains("n'est pas un nombre entier"));
    }

    @Test
    void refuseLesAttributsInconnusEtLesPeriodesIncoherentes() {
        Entry e = new Entry("CMKBI", "Kribi", null, null, Map.of("CountryIdAlpha2", "CM", "Inconnu", "x"),
                LocalDate.of(2026, 1, 1), LocalDate.of(2025, 1, 1), "ACTIVE", null, null, null, null);
        assertThat(EntryValidator.valider(table(null), e, PAYS).erreurs())
                .anyMatch(m -> m.contains("Attribut inconnu « Inconnu »"))
                .anyMatch(m -> m.contains("fin de validité précède"));
    }

    @Test
    void hierarchie() {
        assertThat(EntryValidator.valider(table(null), entree("CMKBI", "Kribi", "CMDLA", Map.of("CountryIdAlpha2", "CM")), PAYS).erreurs())
                .anyMatch(m -> m.contains("n'est pas hiérarchique"));
        assertThat(EntryValidator.valider(table("REF_TEST"), entree("CMKBI", "Kribi", "CMDLA", Map.of("CountryIdAlpha2", "CM")), PAYS).erreurs())
                .isEmpty();
        assertThat(EntryValidator.valider(table("REF_TEST"), entree("CMKBI", "Kribi", "CMXXX", Map.of("CountryIdAlpha2", "CM")), PAYS).erreurs())
                .anyMatch(m -> m.contains("Code parent « CMXXX » inconnu"));
    }

    @Test
    void comparaisonDesDonneesIndependanteDeLOrdreEtDuType() {
        Entry a = new Entry("X", "x", null, null, Map.of("b", 2, "a", "1"), null, null, "ACTIVE", 1L, null, null, null);
        Entry b = new Entry("X", "x", null, null, new java.util.LinkedHashMap<>(Map.of("a", "1", "b", new BigDecimal("2.0"))),
                null, null, "ACTIVE", 2L, null, null, null);
        assertThat(a.memesDonnees(b)).isTrue();
    }
}
