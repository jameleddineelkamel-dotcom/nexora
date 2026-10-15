package com.nexora.procedures.studio;

import com.nexora.procedures.studio.Modele.Field;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Réglages d'affichage par champ du constructeur visuel, rangés dans « ihm.champs » à l'enregistrement de la partie « form ». */
class AffichageChampsTest {

    private static Field champ(String code) {
        return new Field(code, "S", code, null, "TEXT", null, null, "NON", null, null, null, null, null, null, null, null,
                false, null, true, null, null, 10);
    }

    @Test
    void conserveLeGabaritEtRetireLesChampsSupprimes() {
        Map<String, Object> settings = Map.of("currency", "XAF", "ihm", Map.of("disposition", "ONGLETS", "champs", Map.of("ancien", Map.of("largeur", 2))));
        Map<String, Object> champs = Map.of("a", Map.of("largeur", 4), "disparu", Map.of("largeur", 1), "vide", Map.of());
        Map<String, Object> r = VersionService.avecAffichage(settings, champs, List.of(champ("a"), champ("vide")));
        assertEquals("XAF", r.get("currency"));
        Map<?, ?> ihm = (Map<?, ?>) r.get("ihm");
        assertEquals("ONGLETS", ihm.get("disposition"));
        assertEquals(Map.of("a", Map.of("largeur", 4)), ihm.get("champs"));
    }

    @Test
    void sansReglageLaCleChampsDisparait() {
        Map<String, Object> r = VersionService.avecAffichage(Map.of("ihm", Map.of("champs", Map.of("a", Map.of("largeur", 2)))), Map.of(), List.of(champ("a")));
        assertFalse(r.containsKey("ihm"));
        assertTrue(VersionService.avecAffichage(null, Map.of(), List.of()).isEmpty());
    }
}
