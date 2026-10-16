package com.nexora.procedures.studio;

import com.nexora.procedures.studio.Modele.*;
import com.nexora.procedures.web.ProceduresException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Générateur du formulaire en code Angular, sur le modèle FIMEX : gabarit onglets + accordéons, réglages par champ
 * (largeur, texte indicatif, « visible si »), types de demande. Les fichiers produits sont aussi écrits dans
 * target/angular-genere pour une compilation d'essai dans e-Services.
 */
class GenerateurAngularTest {

    private static VersionComplete fimex;

    @BeforeAll
    static void charger() throws Exception {
        JsonMapper m = JsonMapper.builder().disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
        JsonNode r = m.readTree(GenerateurAngularTest.class.getResourceAsStream("/seed/fimex/version.json"));
        Procedure p = m.treeToValue(r.get("procedure"), Procedure.class);
        Map<String, Object> settings = new LinkedHashMap<>(m.convertValue(r.get("settings"), new TypeReference<Map<String, Object>>() {}));
        List<Section> sections = new ArrayList<>(m.convertValue(r.get("sections"), new TypeReference<List<Section>>() {}));
        List<Field> fields = m.convertValue(r.get("fields"), new TypeReference<List<Field>>() {});
        // Une sous-section (accordéon) : la deuxième section rattachée à la première
        Section s2 = sections.get(1);
        sections.set(1, new Section(s2.code(), s2.labelFr(), s2.labelEn(), s2.repeating(), s2.minRows(), s2.sortOrder(), sections.get(0).code()));
        String premier = fields.getFirst().code();
        settings.put("rendu", "GENERE");
        settings.put("ihm", Map.of("disposition", "ONGLETS", "colonnes", 3, "sousSections", "ACCORDEONS", "accordeons", "PREMIER",
                "champs", Map.of(premier, Map.of("largeur", 2, "placeholderFr", "Texte \"indicatif\" {{ x }}", "visibleSi", Map.of("champ", "foreignerCitizen", "valeurs", List.of("E"))))));
        Version v = new Version(7, "FIMEX", 1, "published", settings, "", null, "test", null, null, null, null, null, null, null, null, null, "abcdef1234567890");
        fimex = new VersionComplete(v, p, m.convertValue(r.get("requestTypes"), new TypeReference<List<RequestType>>() {}), List.of(), List.of(),
                sections, fields, List.of(), List.of(), List.of(), List.of(), null, null, List.of());
    }

    @Test
    void genereUnComposantCompletEtLEcritPourEssai() throws Exception {
        GenerateurAngular.Resultat r = GenerateurAngular.generer(fimex);
        assertEquals("FormulaireFimexV1", r.composant());
        assertEquals("nx-fg-fimex-v1", r.selecteur());
        assertEquals(List.of("fimex-v1.ts", "fimex-v1.html"), r.fichiers().stream().map(GenerateurAngular.Fichier::chemin).toList());
        String ts = r.fichiers().get(0).contenu(), html = r.fichiers().get(1).contenu();
        assertTrue(ts.contains("export class FormulaireFimexV1 extends FormulaireGenereBase"));
        assertTrue(ts.contains("protected readonly disposition = 'ONGLETS' as const;"));
        assertTrue(ts.contains("this.valeurDans(\"foreignerCitizen\", [\"E\"], ligne)"), "condition « visible si » écrite en code");
        // Textes saisis échappés dans les constantes, jamais écrits dans le gabarit
        assertTrue(ts.contains("\"Texte \\\"indicatif\\\" {{ x }}\""));
        assertFalse(html.contains("{{ x }}"));
        assertTrue(html.contains("class=\"accordeon\""));
        assertTrue(html.contains("style=\"--span: 2\""));
        assertTrue(html.contains("[attr.placeholder]=\"l(P['" + fimex.fields().getFirst().code() + "'])\""));
        for (Field f : fimex.fields()) assertTrue(html.contains("'" + f.code() + "'"), "champ présent dans le gabarit : " + f.code());
        Path d = Path.of("target", "angular-genere");
        Files.createDirectories(d);
        for (GenerateurAngular.Fichier f : r.fichiers()) Files.writeString(d.resolve(f.chemin()), f.contenu());
    }

    @Test
    void refuseUnCodeQuiNEstPasUnIdentifiant() {
        List<Field> champs = new ArrayList<>(fimex.fields());
        Field f = champs.getFirst();
        champs.set(0, new Field("nom'); alert(1); ('", f.sectionCode(), f.labelFr(), f.labelEn(), f.dataType(), f.maxLength(), f.pattern(), f.mandatory(),
                f.conditionText(), f.refdataTable(), f.codeValues(), f.untdedRef(), f.wcoRef(), f.cclDen(), f.cclUid(), f.biePath(), f.searchable(), f.prefill(),
                f.amendable(), f.helpFr(), f.helpEn(), f.sortOrder()));
        VersionComplete v = new VersionComplete(fimex.version(), fimex.procedure(), fimex.requestTypes(), List.of(), List.of(), fimex.sections(), champs,
                List.of(), List.of(), List.of(), List.of(), null, null, List.of());
        ProceduresException e = assertThrows(ProceduresException.class, () -> GenerateurAngular.generer(v));
        assertTrue(e.getErreurs().getFirst().startsWith("Code de champ non conforme"));
    }

    @Test
    void renduParDefautDynamique() {
        assertEquals("DYNAMIQUE", GenerateurAngular.rendu(null));
        assertEquals("DYNAMIQUE", GenerateurAngular.rendu(Map.of("rendu", "autre")));
        assertEquals("GENERE", GenerateurAngular.rendu(Map.of("rendu", "GENERE")));
        assertEquals("CertOrigineT95173c", GenerateurAngular.pascal("CERT_ORIGINE_T95173C"));
    }
}
