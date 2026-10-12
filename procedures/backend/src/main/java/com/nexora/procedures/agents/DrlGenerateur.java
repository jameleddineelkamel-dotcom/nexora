package com.nexora.procedures.agents;

import com.nexora.procedures.studio.Modele.*;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Traduit les données de conception en règles DRL (Drools) : le tableau des frais devient l'agenda-group « frais »,
 * les pièces conditionnelles des règles de l'agenda-group « validation ». Les sections générées sont délimitées par
 * des marqueurs {@code // <generated:xxx>} et remplacées sans toucher aux règles écrites à la main.
 */
public final class DrlGenerateur {

    /** Condition simple acceptée : « champ == 'valeur' » ou « champ != 'valeur' » (combinables par « && »). */
    private static final Pattern TERME = Pattern.compile("^\\s*([a-z][A-Za-z0-9]*)\\s*(==|!=)\\s*'([^']*)'\\s*$");

    private DrlGenerateur() {}

    public static String entete(String procedure) {
        return """
                package nexora.%s;

                // Règles de la procédure %s — moteur Drools.
                // Points d'évaluation (agenda-group) : « validation » au dépôt, « frais » pour l'avis de paiement.
                import com.nexora.regles.Demande;
                import com.nexora.regles.Antecedent;
                import com.nexora.regles.Resultat;

                global Resultat resultat;

                """.formatted(procedure.toLowerCase(), procedure);
    }

    public static String frais(List<FeeItem> frais) {
        StringBuilder s = new StringBuilder();
        for (FeeItem f : frais) {
            String types = f.requestTypes() == null || f.requestTypes().isEmpty() ? "" : "typeDemande in (" + liste(f.requestTypes()) + ")";
            String natures = f.natures() == null || f.natures().isEmpty() ? "" : "nature in (" + liste(f.natures()) + ")";
            String contraintes = List.of(types, natures).stream().filter(c -> !c.isEmpty()).collect(Collectors.joining(", "));
            s.append("rule \"Frais ").append(f.code()).append("\"\n    agenda-group \"frais\"\nwhen\n    Demande(").append(contraintes)
                    .append(")\nthen\n    resultat.frais(\"").append(f.code()).append("\", \"").append(f.beneficiary()).append("\", \"")
                    .append(echapper(f.labelFr())).append("\", ").append(f.amount().stripTrailingZeros().toPlainString()).append(");\nend\n\n");
        }
        return s.toString().stripTrailing() + "\n";
    }

    /** Règles des pièces conditionnelles ; une condition non traduisible est signalée en commentaire. */
    public static String pieces(List<RequiredDocument> pieces) {
        StringBuilder s = new StringBuilder();
        for (RequiredDocument d : pieces) {
            if (d.conditionText() == null || d.conditionText().isBlank()) continue;
            String contrainte = traduire(d.conditionText());
            String types = d.requestTypes() == null || d.requestTypes().isEmpty() ? "" : "typeDemande in (" + liste(d.requestTypes()) + "), ";
            if (contrainte == null) {
                s.append("// Pièce ").append(d.code()).append(" : condition non traduisible automatiquement (« ")
                        .append(d.conditionText().replace("\n", " ")).append(" ») — règle à écrire à la main.\n\n");
                continue;
            }
            s.append("rule \"Pièce ").append(d.code()).append(" conditionnelle\"\n    agenda-group \"validation\"\nwhen\n    Demande(")
                    .append(types).append(contrainte).append(", aPiece(\"").append(d.code()).append("\") == false)\nthen\n    resultat.erreur(\"")
                    .append(d.code()).append("\", \"Pièce obligatoire : ").append(echapper(d.labelFr())).append(" (condition : ")
                    .append(echapper(d.conditionText())).append(").\");\nend\n\n");
        }
        return s.toString().stripTrailing() + "\n";
    }

    /** « foreignerCitizen == 'E' && partyTypeCode != 'PP' » → contraintes DRL sur le fait Demande. */
    static String traduire(String condition) {
        StringBuilder r = new StringBuilder();
        for (String terme : condition.split("&&")) {
            Matcher m = TERME.matcher(terme);
            if (!m.matches()) return null;
            if (!r.isEmpty()) r.append(", ");
            r.append("texte(\"").append(m.group(1)).append("\") ").append(m.group(2)).append(" \"").append(echapper(m.group(3))).append("\"");
        }
        return r.isEmpty() ? null : r.toString();
    }

    /** Remplace (ou ajoute) une section générée dans un DRL existant. */
    public static String remplacerSection(String drl, String section, String contenu) {
        String debut = "// <generated:" + section + ">", fin = "// </generated:" + section + ">";
        int i = drl.indexOf(debut), j = drl.indexOf(fin);
        String bloc = debut + " — section régénérée par le Rules Agent\n" + contenu + fin;
        if (i >= 0 && j > i) {
            return drl.substring(0, i) + bloc + drl.substring(j + fin.length());
        }
        return drl.stripTrailing() + "\n\n" + bloc + "\n";
    }

    private static String liste(List<String> l) {
        return l.stream().map(x -> "\"" + x + "\"").collect(Collectors.joining(", "));
    }

    private static String echapper(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
