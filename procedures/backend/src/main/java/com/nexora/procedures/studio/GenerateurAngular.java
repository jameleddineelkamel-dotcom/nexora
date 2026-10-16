package com.nexora.procedures.studio;

import com.nexora.procedures.studio.Modele.*;
import com.nexora.procedures.web.ProceduresException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Générateur du formulaire de demande en code Angular (procédures dont le réglage « rendu » vaut GENERE). Pour une
 * version, il écrit un composant autonome destiné au dossier {@code eservices/frontend/src/app/formulaires-generes} :
 * structure (onglets, étapes ou sections, sous-sections en blocs ou accordéons), contrôles écrits champ par champ,
 * libellés et valeurs de liste en constantes, conditions (types de demande, « visible si ») en code. Le composant étend
 * {@code FormulaireGenereBase} d'e-Services, qui porte la saisie, les erreurs et l'amendement.
 * <p>Les textes saisis dans le studio ne sont jamais écrits dans le gabarit HTML : ils passent par des constantes
 * TypeScript échappées. Les codes de champs et de sections, écrits en dur, doivent être des identifiants.</p>
 */
public final class GenerateurAngular {

    /** Version du format de code produit (à faire évoluer avec FormulaireGenereBase). */
    public static final int FORMAT = 1;
    private static final Pattern IDENTIFIANT = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");
    private static final Pattern TABLE = Pattern.compile("^REF_[A-Z0-9_]+$");
    private static final Pattern CONDITION_TYPE = Pattern.compile("^\\s*typeDemande\\s+in\\s*\\(([^)]*)\\)\\s*$", Pattern.CASE_INSENSITIVE);

    public record Fichier(String chemin, String contenu) {}

    public record Resultat(String procedure, int versionNo, String composant, String selecteur, List<Fichier> fichiers) {}

    private GenerateurAngular() {}

    public static String rendu(Map<String, Object> settings) {
        return settings != null && "GENERE".equals(settings.get("rendu")) ? "GENERE" : "DYNAMIQUE";
    }

    public static Resultat generer(VersionComplete v) {
        String code = v.procedure().code();
        int no = v.version().versionNo();
        controler(v);
        String fichier = code.toLowerCase(Locale.ROOT).replace('_', '-') + "-v" + no;
        String classe = "Formulaire" + pascal(code) + "V" + no;
        String selecteur = "nx-fg-" + fichier;
        Map<String, Object> ihm = v.version().settings() != null && v.version().settings().get("ihm") instanceof Map<?, ?> m ? cast(m) : Map.of();
        Gabarit g = new Gabarit(ihm);

        List<Section> triees = v.sections().stream().sorted(Comparator.comparingInt(Section::sortOrder)).toList();
        Set<String> codes = new HashSet<>();
        triees.forEach(s -> codes.add(s.code()));
        Map<String, Section> parCode = new HashMap<>();
        triees.forEach(s -> parCode.put(s.code(), s));
        List<Section> hauts = triees.stream().filter(s -> {
            Section p = s.parentCode() == null ? null : parCode.get(s.parentCode());
            return p == null || p.parentCode() != null;
        }).toList();
        Map<String, List<Field>> champs = new LinkedHashMap<>();
        triees.forEach(s -> champs.put(s.code(), v.fields().stream().filter(f -> f.sectionCode().equals(s.code()))
                .sorted(Comparator.comparingInt(Field::sortOrder)).toList()));

        String entete = "Code généré par le studio NEXORA (générateur Angular " + FORMAT + ") — procédure " + code + ", version " + no
                + (v.version().packageHash() == null ? "" : ", paquet " + v.version().packageHash().substring(0, Math.min(12, v.version().packageHash().length())))
                + ". Ne pas modifier à la main : regénérer depuis le studio.";
        String ts = typescript(v, g, entete, fichier, classe, selecteur, hauts, triees, champs);
        String html = gabarit(v, g, entete, hauts, triees, champs);
        return new Resultat(code, no, classe, selecteur, List.of(new Fichier(fichier + ".ts", ts), new Fichier(fichier + ".html", html)));
    }

    // ------------------------------------------------------------------ contrôles préalables
    private static void controler(VersionComplete v) {
        List<String> e = new ArrayList<>();
        for (Section s : v.sections()) if (!IDENTIFIANT.matcher(s.code()).matches()) e.add("Code de section non conforme pour la génération Angular : « " + s.code() + " ».");
        for (Field f : v.fields()) {
            if (!IDENTIFIANT.matcher(f.code()).matches()) e.add("Code de champ non conforme pour la génération Angular : « " + f.code() + " ».");
            if ("CODE".equals(f.dataType()) && f.refdataTable() != null && !f.refdataTable().isBlank() && !"@LIEUX_TRAITEMENT".equals(f.refdataTable())
                    && !TABLE.matcher(f.refdataTable()).matches())
                e.add("Liste non conforme pour la génération Angular (champ " + f.code() + ") : « " + f.refdataTable() + " » (attendu REF_xxx ou @LIEUX_TRAITEMENT).");
        }
        if (!e.isEmpty()) throw ProceduresException.invalide(e);
    }

    // ------------------------------------------------------------------ composant TypeScript
    private static String typescript(VersionComplete v, Gabarit g, String entete, String fichier, String classe, String selecteur,
                                     List<Section> hauts, List<Section> triees, Map<String, List<Field>> champs) {
        // Composants importés : seulement ceux que le gabarit utilise (pas d'avertissement NG8113 à la compilation)
        List<Field> utilises = champs.values().stream().flatMap(List::stream).toList();
        boolean icone = g.sousSections.equals("ACCORDEONS") && triees.stream().anyMatch(s -> s.parentCode() != null)
                || triees.stream().anyMatch(Section::repeating);
        boolean liste = utilises.stream().anyMatch(f -> "CODE".equals(f.dataType()) && (f.codeValues() == null || f.codeValues().isEmpty())
                && f.refdataTable() != null && f.refdataTable().startsWith("REF_"));
        boolean carte = utilises.stream().anyMatch(f -> "GEOLOCATION".equals(f.dataType()));
        List<String> imports = new ArrayList<>(List.of("NgTemplateOutlet"));
        if (icone) imports.add("Icon");
        if (liste) imports.add("CodePicker");
        if (carte) imports.add("Localisation");
        imports.add("...I18N");
        StringBuilder t = new StringBuilder();
        t.append("// ").append(entete).append('\n');
        t.append("import { Component } from '@angular/core';\n");
        t.append("import { NgTemplateOutlet } from '@angular/common';\n");
        t.append("import { I18N } from '../core/i18n';\n");
        if (icone) t.append("import { Icon } from '../shared/icon';\n");
        if (liste || carte) t.append("import { ").append(liste && carte ? "CodePicker, Localisation" : liste ? "CodePicker" : "Localisation").append(" } from '../shared/widgets';\n");
        t.append("import { Bloc, FormulaireGenereBase, Libelle, Ligne } from './base';\n\n");
        t.append("/** Formulaire de la procédure ").append(commentaire(v.procedure().labelFr())).append(" (").append(v.procedure().code())
                .append("), version ").append(v.version().versionNo()).append(". */\n");
        t.append("@Component({\n");
        t.append("  selector: '").append(selecteur).append("',\n");
        t.append("  imports: [").append(String.join(", ", imports)).append("],\n");
        t.append("  templateUrl: './").append(fichier).append(".html',\n");
        t.append("  styleUrl: './formulaire-genere.css',\n");
        t.append("  host: { 'data-densite': '").append(g.densite).append("', 'data-libelles': '").append(g.libelles).append("' },\n");
        t.append("})\n");
        t.append("export class ").append(classe).append(" extends FormulaireGenereBase {\n");
        t.append("  protected readonly disposition = '").append(g.disposition).append("' as const;\n");
        t.append("  protected readonly accordeons = '").append(g.accordeons).append("' as const;\n\n");

        t.append("  /** Sections de premier niveau et sections qu'elles regroupent. */\n");
        t.append("  protected readonly structure: readonly Bloc[] = [\n");
        for (Section h : hauts) {
            List<String> groupe = new ArrayList<>(List.of(h.code()));
            triees.stream().filter(s -> h.code().equals(s.parentCode())).forEach(s -> groupe.add(s.code()));
            t.append("    { code: ").append(js(h.code())).append(", libelle: ").append(libelle(h.labelFr(), h.labelEn()))
                    .append(", sections: [").append(String.join(", ", groupe.stream().map(GenerateurAngular::js).toList())).append("] },\n");
        }
        t.append("  ];\n\n");

        t.append("  protected readonly champsParSection: Readonly<Record<string, readonly string[]>> = {\n");
        champs.forEach((s, l) -> t.append("    ").append(js(s)).append(": [").append(String.join(", ", l.stream().map(f -> js(f.code())).toList())).append("],\n"));
        t.append("  };\n\n");

        t.append("  /** Libellés des sections. */\n");
        t.append("  protected readonly S: Readonly<Record<string, Libelle>> = {\n");
        triees.forEach(s -> t.append("    ").append(js(s.code())).append(": ").append(libelle(s.labelFr(), s.labelEn())).append(",\n"));
        t.append("  };\n\n");

        List<Field> tous = champs.values().stream().flatMap(List::stream).toList();
        t.append("  /** Libellés des champs. */\n");
        t.append("  protected readonly L: Readonly<Record<string, Libelle>> = {\n");
        tous.forEach(f -> t.append("    ").append(js(f.code())).append(": ").append(libelle(f.labelFr(), f.labelEn())).append(",\n"));
        t.append("  };\n\n");

        t.append("  /** Textes d'aide. */\n");
        t.append("  protected readonly A: Readonly<Record<string, Libelle>> = {\n");
        tous.stream().filter(f -> f.helpFr() != null && !f.helpFr().isBlank())
                .forEach(f -> t.append("    ").append(js(f.code())).append(": ").append(libelle(f.helpFr(), f.helpEn())).append(",\n"));
        t.append("  };\n\n");

        t.append("  /** Textes indicatifs (placeholder). */\n");
        t.append("  protected readonly P: Readonly<Record<string, Libelle>> = {\n");
        tous.forEach(f -> {
            Map<String, Object> r = g.reglage(f.code());
            String fr = texte(r.get("placeholderFr")), en = texte(r.get("placeholderEn"));
            if (fr != null || en != null) t.append("    ").append(js(f.code())).append(": ").append(libelle(fr == null ? en : fr, en)).append(",\n");
        });
        t.append("  };\n\n");

        t.append("  /** Valeurs des listes locales : [code, libellé]. */\n");
        t.append("  protected readonly V: Readonly<Record<string, readonly (readonly [string, Libelle])[]>> = {\n");
        tous.stream().filter(f -> f.codeValues() != null && !f.codeValues().isEmpty()).forEach(f -> {
            t.append("    ").append(js(f.code())).append(": [\n");
            f.codeValues().forEach(c -> t.append("      [").append(js(c.code())).append(", ").append(libelle(c.labelFr(), c.labelEn())).append("],\n"));
            t.append("    ],\n");
        });
        t.append("  };\n\n");

        t.append("  /** Conditions des champs : types de demande concernés, affichage conditionnel « visible si ». */\n");
        t.append("  protected condition(code: string, ligne: Ligne | null): boolean {\n");
        t.append("    switch (code) {\n");
        boolean une = false;
        for (Field f : tous) {
            List<String> conditions = new ArrayList<>();
            Matcher m = f.conditionText() == null ? null : CONDITION_TYPE.matcher(f.conditionText());
            if (m != null && m.matches()) {
                List<String> types = Arrays.stream(m.group(1).split(",")).map(x -> x.strip().replace("'", "")).filter(x -> !x.isEmpty()).toList();
                if (!types.isEmpty()) conditions.add("this.pourTypes([" + String.join(", ", types.stream().map(GenerateurAngular::js).toList()) + "])");
            }
            if (g.reglage(f.code()).get("visibleSi") instanceof Map<?, ?> si && si.get("champ") instanceof String champ && !champ.isBlank()) {
                List<String> valeurs = si.get("valeurs") instanceof List<?> l ? l.stream().map(String::valueOf).map(String::strip).filter(x -> !x.isEmpty()).toList() : List.of();
                conditions.add(valeurs.isEmpty() ? "this.renseigne(" + js(champ) + ", ligne)"
                        : "this.valeurDans(" + js(champ) + ", [" + String.join(", ", valeurs.stream().map(GenerateurAngular::js).toList()) + "], ligne)");
            }
            if (conditions.isEmpty()) continue;
            une = true;
            t.append("      case ").append(js(f.code())).append(": return ").append(String.join(" && ", conditions)).append(";\n");
        }
        t.append("      default: return true;\n");
        t.append("    }\n");
        t.append("  }\n");
        if (!une) t.insert(t.lastIndexOf("  protected condition("), "  // Aucun champ conditionnel dans cette version\n");
        t.append("}\n");
        return t.toString();
    }

    // ------------------------------------------------------------------ gabarit HTML
    private static String gabarit(VersionComplete v, Gabarit g, String entete, List<Section> hauts, List<Section> triees, Map<String, List<Field>> champs) {
        StringBuilder h = new StringBuilder();
        h.append("<!-- ").append(entete.replace("--", "—")).append(" -->\n");
        boolean etapes = g.disposition.equals("ETAPES");
        if (g.disposition.equals("CONTINU")) {
            if (g.navigation) {
                h.append("@if (total() > 2) {\n");
                h.append("  <nav class=\"nav-sec\" [attr.aria-label]=\"'Sections du formulaire' | t\">\n");
                h.append("    @for (b of blocs(); track b.code) {\n");
                h.append("      <button type=\"button\" class=\"pastille\" [class.err]=\"erreursSections(b.sections) > 0\" (click)=\"aller(b.code)\">{{ l(b.libelle) }}\n");
                h.append("        @if (erreursSections(b.sections)) { <span class=\"badge-n\">{{ erreursSections(b.sections) }}</span> }</button>\n");
                h.append("    }\n");
                h.append("    @for (o of ongletsSup(); track o.code) {\n");
                h.append("      <button type=\"button\" class=\"pastille\" [class.err]=\"!!o.badge\" (click)=\"aller(o.code)\">{{ o.labelFr | lib: o.labelEn }}\n");
                h.append("        @if (o.badge) { <span class=\"badge-n\">{{ o.badge }}</span> }</button>\n");
                h.append("    }\n");
                h.append("  </nav>\n");
                h.append("}\n");
            }
        } else {
            h.append("@if (total() > 1) {\n");
            h.append("  <nav class=\"onglets-sec").append(etapes ? " etapes" : "").append("\" role=\"tablist\" [attr.aria-label]=\"'Sections du formulaire' | t\">\n");
            h.append("    @for (b of blocs(); track b.code; let i = $index) {\n");
            h.append("      <button type=\"button\" role=\"tab\" class=\"onglet-sec\" [class.on]=\"i === actifBorne()\"")
                    .append(etapes ? " [class.fait]=\"i < actifBorne()\"" : "").append(" [class.err]=\"erreursSections(b.sections) > 0\"\n");
            h.append("        [attr.aria-selected]=\"i === actifBorne()\" (click)=\"actif.set(i)\">")
                    .append(etapes ? "<span class=\"num\">{{ i + 1 }}</span> " : "").append("{{ l(b.libelle) }}\n");
            h.append("        @if (erreursSections(b.sections)) { <span class=\"badge-n\">{{ erreursSections(b.sections) }}</span> }</button>\n");
            h.append("    }\n");
            h.append("    @for (o of ongletsSup(); track o.code; let k = $index) {\n");
            h.append("      <button type=\"button\" role=\"tab\" class=\"onglet-sec\" [class.on]=\"blocs().length + k === actifBorne()\"")
                    .append(etapes ? " [class.fait]=\"blocs().length + k < actifBorne()\"" : "").append(" [class.err]=\"!!o.badge\"\n");
            h.append("        [attr.aria-selected]=\"blocs().length + k === actifBorne()\" (click)=\"actif.set(blocs().length + k)\">")
                    .append(etapes ? "<span class=\"num\">{{ blocs().length + k + 1 }}</span> " : "").append("{{ o.labelFr | lib: o.labelEn }}\n");
            h.append("        @if (o.badge) { <span class=\"badge-n\">{{ o.badge }}</span> }</button>\n");
            h.append("    }\n");
            h.append("  </nav>\n");
            h.append("}\n");
        }

        for (Section haut : hauts) {
            String c = haut.code();
            h.append("\n<!-- ").append(c).append(" -->\n");
            h.append("@if (affiche('").append(c).append("')) {\n");
            h.append("  <section class=\"sec\" [id]=\"ancre('").append(c).append("')\">\n");
            if (g.disposition.equals("CONTINU"))
                h.append("    @if (total() > 1) { <h3 class=\"sec-titre\">{{ l(S['").append(c).append("']) }}</h3> }\n");
            h.append("    @if (sectionAffichee('").append(c).append("')) {\n");
            corps(h, haut, champs.get(c), g, "      ");
            h.append("    }\n");
            int rang = 0;
            for (Section e : triees.stream().filter(s -> c.equals(s.parentCode())).toList()) {
                String ec = e.code();
                h.append("    @if (sectionAffichee('").append(ec).append("')) {\n");
                if (g.sousSections.equals("ACCORDEONS")) {
                    h.append("      <div class=\"accordeon\" [class.ouvert]=\"ouvert('").append(ec).append("', ").append(rang).append(")\" [id]=\"ancre('").append(ec).append("')\">\n");
                    h.append("        <button type=\"button\" class=\"acc-tete\" [attr.aria-expanded]=\"ouvert('").append(ec).append("', ").append(rang)
                            .append(")\" (click)=\"basculerAccordeon('").append(ec).append("', ").append(rang).append(")\">\n");
                    h.append("          <span>{{ l(S['").append(ec).append("']) }}</span>\n");
                    if (e.repeating()) h.append("          <span class=\"muted small\">· {{ lignes('").append(ec).append("').length }} {{ 'ligne(s)' | t }}</span>\n");
                    h.append("          @if (erreursSections(['").append(ec).append("'])) { <span class=\"badge-n\">{{ erreursSections(['").append(ec).append("']) }}</span> }\n");
                    h.append("          <nx-icon name=\"chevron\" [size]=\"16\" class=\"acc-chevron\" />\n");
                    h.append("        </button>\n");
                    h.append("        @if (ouvert('").append(ec).append("', ").append(rang).append(")) {\n");
                    h.append("          <div class=\"acc-corps\">\n");
                    corps(h, e, champs.get(ec), g, "            ");
                    h.append("          </div>\n");
                    h.append("        }\n");
                    h.append("      </div>\n");
                } else {
                    h.append("      <div class=\"sous-sec\" [id]=\"ancre('").append(ec).append("')\">\n");
                    h.append("        <h4 class=\"sous-titre\">{{ l(S['").append(ec).append("']) }}</h4>\n");
                    corps(h, e, champs.get(ec), g, "        ");
                    h.append("      </div>\n");
                }
                h.append("    }\n");
                rang++;
            }
            h.append("  </section>\n");
            h.append("}\n");
        }

        h.append("\n<!-- Onglets propres à la page (pièces jointes, livrables), toujours en dernier -->\n");
        h.append("@for (o of ongletsSupAffiches(); track o.code) {\n");
        h.append("  <section class=\"sec\" [id]=\"ancre(o.code)\">\n");
        if (g.disposition.equals("CONTINU")) h.append("    @if (total() > 1) { <h3 class=\"sec-titre\">{{ o.labelFr | lib: o.labelEn }}</h3> }\n");
        h.append("    <ng-container *ngTemplateOutlet=\"o.modele\" />\n");
        h.append("  </section>\n");
        h.append("}\n");
        if (etapes) {
            h.append("@if (total() > 1) {\n");
            h.append("  <div class=\"pas\">\n");
            h.append("    <button type=\"button\" class=\"btn\" [disabled]=\"actifBorne() === 0\" (click)=\"actif.set(actifBorne() - 1)\">{{ 'Précédent' | t }}</button>\n");
            h.append("    <span class=\"muted small\">{{ 'Étape {0} sur {1}' | t: actifBorne() + 1 : total() }}</span>\n");
            h.append("    <button type=\"button\" class=\"btn\" [disabled]=\"actifBorne() >= total() - 1\" (click)=\"actif.set(actifBorne() + 1)\">{{ 'Suivant' | t }}</button>\n");
            h.append("  </div>\n");
            h.append("}\n");
        }
        return h.toString();
    }

    /** Corps d'une section : grille de champs, ou tableau de lignes pour un groupe répétitif. */
    private static void corps(StringBuilder h, Section s, List<Field> champs, Gabarit g, String r) {
        List<Field> l = champs == null ? List.of() : champs;
        if (!s.repeating()) {
            h.append(r).append(g.colonnes == null ? "<div class=\"grille\">\n" : "<div class=\"grille cols\" style=\"--cols: " + g.colonnes + "\">\n");
            for (Field f : l) {
                String c = f.code();
                int largeur = g.largeur(c);
                boolean pleine = g.reglage(c).get("largeur") == null ? List.of("LONGTEXT", "GEOLOCATION").contains(f.dataType())
                        : largeur >= (g.colonnes == null ? 4 : g.colonnes);
                h.append(r).append("  @if (visible('").append(c).append("')) {\n");
                h.append(r).append("    <div class=\"field").append(pleine ? " full" : largeur > 1 ? " large" : "").append('"')
                        .append(!pleine && largeur > 1 ? " style=\"--span: " + largeur + "\"" : "").append(">\n");
                h.append(r).append("      <label for=\"c-").append(c).append("\">{{ l(L['").append(c).append("']) }} @if (obligatoire('").append(c).append("', '")
                        .append(f.mandatory()).append("')) { <span class=\"req\">*</span> }</label>\n");
                h.append(r).append("      ").append(controle(f, g, "$any(valeur('" + c + "'))", "null", "-1")).append('\n');
                h.append(r).append("      @if (modifie('").append(c).append("')) { <span class=\"hint avant\">{{ 'Avant amendement' | t }} : <b>{{ affichage('")
                        .append(c).append("', precedent()?.['").append(c).append("']) || '—' }}</b></span> }");
                if (f.helpFr() != null && !f.helpFr().isBlank()) h.append(" @else { <span class=\"hint\">{{ l(A['").append(c).append("']) }}</span> }");
                h.append('\n');
                h.append(r).append("      @if (erreur('").append(c).append("'); as e) { <span class=\"hint err-txt\">{{ e }}</span> }\n");
                h.append(r).append("    </div>\n");
                h.append(r).append("  }\n");
            }
            h.append(r).append("</div>\n");
            return;
        }
        String sc = s.code();
        h.append(r).append("<div class=\"scroll-x\">\n");
        h.append(r).append("  <table class=\"datagrid repete\">\n");
        h.append(r).append("    <thead><tr><th>#</th>");
        for (Field f : l)
            h.append("<th>{{ l(L['").append(f.code()).append("']) }}@if (obligatoire('").append(f.code()).append("', '").append(f.mandatory()).append("')) { * }</th>");
        h.append("@if (!lectureSeule()) { <th></th> }</tr></thead>\n");
        h.append(r).append("    <tbody>\n");
        h.append(r).append("      @for (ligne of lignes('").append(sc).append("'); track $index; let i = $index) {\n");
        h.append(r).append("        <tr>\n");
        h.append(r).append("          <td class=\"muted\">{{ i + 1 }}</td>\n");
        for (Field f : l)
            h.append(r).append("          <td>@if (visible('").append(f.code()).append("', ligne)) { ")
                    .append(controle(f, g, "$any(ligne['" + f.code() + "'])", "'" + sc + "'", "i")).append(" }</td>\n");
        h.append(r).append("          @if (!lectureSeule()) { <td><button type=\"button\" class=\"btn icon ghost danger\" (click)=\"retirerLigne('").append(sc)
                .append("', i)\" [attr.aria-label]=\"'Retirer la ligne' | t\"><nx-icon name=\"trash\" /></button></td> }\n");
        h.append(r).append("        </tr>\n");
        h.append(r).append("      } @empty {\n");
        h.append(r).append("        <tr><td colspan=\"").append(l.size() + 2).append("\" class=\"empty small\">{{ 'Aucune ligne.' | t }}");
        if (s.minRows() > 0) h.append(" {{ 'Au moins {0} ligne(s) attendue(s).' | t: ").append(s.minRows()).append(" }}");
        h.append("</td></tr>\n");
        h.append(r).append("      }\n");
        h.append(r).append("    </tbody>\n");
        h.append(r).append("  </table>\n");
        h.append(r).append("</div>\n");
        h.append(r).append("@if (erreur('").append(sc).append("'); as e) { <div class=\"alert err\">{{ e }}</div> }\n");
        h.append(r).append("@if (!lectureSeule()) { <button type=\"button\" class=\"btn sm\" (click)=\"ajouterLigne('").append(sc)
                .append("')\"><nx-icon name=\"plus\" [size]=\"15\" /> {{ 'Ajouter une ligne' | t }}</button> }\n");
    }

    /** Contrôle de saisie d'un champ selon son type ; {@code v} : expression de la valeur, {@code section} / {@code i} : ligne d'un groupe. */
    private static String controle(Field f, Gabarit g, String v, String section, String i) {
        String c = f.code();
        boolean ligne = !"null".equals(section);
        String poser = "poser('" + c + "', $any($event.target).value, " + section + ", " + i + ")";
        String desactive = "[disabled]=\"verrou('" + c + "', " + f.amendable() + ")\"";
        String etat = ligne ? "" : " [class.err]=\"!!erreur('" + c + "')\" [class.modifie]=\"modifie('" + c + "')\"";
        boolean indicatif = g.reglage(c).get("placeholderFr") != null || g.reglage(c).get("placeholderEn") != null;
        String placeholder = indicatif ? " [attr.placeholder]=\"l(P['" + c + "'])\"" : "";
        String id = ligne ? "" : " id=\"c-" + c + "\"";
        switch (f.dataType()) {
            case "CODE" -> {
                if (f.codeValues() != null && !f.codeValues().isEmpty())
                    return "<select class=\"input\"" + id + " " + desactive + etat + " (change)=\"" + poser + "\"><option value=\"\">—</option>"
                            + "@for (o of V['" + c + "']; track o[0]) { <option [value]=\"o[0]\" [selected]=\"" + v + " === o[0]\">{{ l(o[1]) }}</option> }</select>";
                if ("@LIEUX_TRAITEMENT".equals(f.refdataTable()))
                    return "<select class=\"input\"" + id + " " + desactive + etat + " (change)=\"" + poser + "\"><option value=\"\">—</option>"
                            + "@for (o of form().locations; track o.code) { <option [value]=\"o.code\" [selected]=\"" + v + " === o.code\">{{ o.labelFr | lib: o.labelEn }} — {{ o.unitLabel }}</option> }</select>";
                if (f.refdataTable() != null && !f.refdataTable().isBlank())
                    return "<nx-code-picker table=\"" + f.refdataTable() + "\" [value]=\"" + v + " ?? null\" (valueChange)=\"poser('" + c + "', $event ?? '', " + section + ", " + i
                            + ")\" " + desactive + " [erreur]=\"!!erreur('" + c + "')\" [label]=\"l(L['" + c + "'])\" />";
                return "<input class=\"input\"" + id + " [value]=\"" + v + " ?? ''\" " + desactive + placeholder + " (input)=\"" + poser + "\" />";
            }
            case "GEOLOCATION" -> {
                return "<nx-localisation [value]=\"" + v + " ?? null\" (valueChange)=\"poser('" + c + "', $event ?? '', " + section + ", " + i + ")\" " + desactive + " />";
            }
            case "LONGTEXT" -> {
                return "<textarea class=\"input\"" + id + " [value]=\"" + v + " ?? ''\" " + desactive + placeholder + " (input)=\"" + poser + "\"></textarea>";
            }
            case "BOOLEAN" -> {
                return "<select class=\"input\"" + id + " " + desactive + " (change)=\"" + poser + "\"><option value=\"\">—</option>"
                        + "<option value=\"true\" [selected]=\"" + v + " === 'true' || " + v + " === true\">{{ 'Oui' | t }}</option>"
                        + "<option value=\"false\" [selected]=\"" + v + " === 'false' || " + v + " === false\">{{ 'Non' | t }}</option></select>";
            }
            default -> {
                String type = switch (f.dataType()) { case "DATE" -> "date"; case "EMAIL" -> "email"; case "PHONE" -> "tel"; default -> "text"; };
                StringBuilder s = new StringBuilder("<input class=\"input").append("TEXT".equals(f.dataType()) ? "" : " mono").append('"').append(id)
                        .append(" type=\"").append(type).append('"');
                if (f.maxLength() != null) s.append(" maxlength=\"").append(f.maxLength()).append('"');
                if (List.of("DECIMAL", "NUMBER", "YEAR").contains(f.dataType())) s.append(" inputmode=\"decimal\"");
                s.append(" [value]=\"").append(v).append(" ?? ''\" ").append(desactive).append(etat).append(placeholder).append(" (input)=\"").append(poser).append("\" />");
                return s.toString();
            }
        }
    }

    // ------------------------------------------------------------------ outils
    /** Gabarit d'IHM de la version, lu avec les mêmes valeurs par défaut que le rendu dynamique d'e-Services. */
    private static final class Gabarit {
        final String disposition, densite, libelles, sousSections, accordeons;
        final Integer colonnes;
        final boolean navigation;
        final Map<String, Object> champs;

        Gabarit(Map<String, Object> o) {
            disposition = un(o.get("disposition"), List.of("CONTINU", "ONGLETS", "ETAPES"), "CONTINU");
            densite = un(o.get("densite"), List.of("COMPACTE", "NORMALE", "AEREE"), "COMPACTE");
            libelles = un(o.get("libelles"), List.of("DESSUS", "COTE"), "DESSUS");
            sousSections = un(o.get("sousSections"), List.of("BLOCS", "ACCORDEONS"), "BLOCS");
            accordeons = un(o.get("accordeons"), List.of("AUCUN", "PREMIER", "TOUS"), "AUCUN");
            Integer n = o.get("colonnes") instanceof Number x ? (Integer) Math.round(x.floatValue()) : null;
            colonnes = n != null && n >= 1 && n <= 4 ? n : null;
            navigation = !Boolean.FALSE.equals(o.get("navigation"));
            champs = o.get("champs") instanceof Map<?, ?> m ? cast(m) : Map.of();
        }

        Map<String, Object> reglage(String code) {
            return champs.get(code) instanceof Map<?, ?> m ? cast(m) : Map.of();
        }

        int largeur(String code) {
            Object l = reglage(code).get("largeur");
            int n = l instanceof Number x ? Math.round(x.floatValue()) : 1;
            return n >= 1 && n <= 4 ? n : 1;
        }

        private static String un(Object v, List<String> l, String d) {
            return v instanceof String s && l.contains(s) ? s : d;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String texte(Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }

    private static String libelle(String fr, String en) {
        return "[" + js(fr == null ? "" : fr) + ", " + (en == null || en.isBlank() ? "null" : js(en)) + "]";
    }

    /** Chaîne TypeScript entre guillemets, échappée. */
    static String js(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case ' ' -> b.append("\\u2028");
                case ' ' -> b.append("\\u2029");
                default -> { if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch); }
            }
        }
        return b.append('"').toString();
    }

    private static String commentaire(String s) {
        return s == null ? "" : s.replace("*/", "* /").replace('\n', ' ');
    }

    static String pascal(String code) {
        StringBuilder b = new StringBuilder();
        for (String p : code.split("[^A-Za-z0-9]+")) {
            if (p.isEmpty()) continue;
            b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase(Locale.ROOT));
        }
        return b.isEmpty() ? "Procedure" : b.toString();
    }
}
