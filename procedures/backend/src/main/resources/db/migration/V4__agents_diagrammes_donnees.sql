-- Agents : descriptions à jour (données tirées du texte de la SFD, import des diagrammes draw.io / BPMN, modèle auto-hébergé)
UPDATE studio.agent SET description = 'Décompose l''objectif, enchaîne les agents (avec un modèle auto-hébergé, choisit ceux qui sont utiles), applique la politique d''autonomie et trace chaque exécution. Un diagramme joint déclenche toujours son import.'
 WHERE code = 'ORCHESTRATEUR';
UPDATE studio.agent SET description = 'Extrait d''une SFD ou d''un texte réglementaire, avec l''extrait source : pièces, acteurs, frais, délais, règles de gestion, et les données du formulaire (sections, champs, types de demande) tirées des tableaux ou du texte des cas d''utilisation ; données saisies par l''organisme instructeur placées dans la section INSTRUCTION.'
 WHERE code = 'DOCUMENT';
UPDATE studio.agent SET description = 'Importe le diagramme BPMN joint (draw.io ou BPMN 2.0, source des figures de la SFD) en workflow exécutable : couloirs, décisions, livrables et statuts du diagramme ; à défaut, génère le workflow standard à partir des acteurs, statuts, frais et décisions.'
 WHERE code = 'PROCESSUS';
