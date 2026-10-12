# NEXORA · procedures — Studio des procédures (Single Window AI Studio)

Conception des procédures du guichet unique et **agents IA de modélisation** : une procédure y est décrite une fois
(formulaire relié aux modèles de référence, pièces, frais, catalogue de messages et flux, workflow BPMN, règles Drools,
livrable), revue, approuvée par une autre personne, puis **publiée** sous forme de paquet que les e-Services exécutent.

| | |
|---|---|
| Port | **8084** (interface de développement : 4204) |
| Base | PostgreSQL `nexora_procedures` — schémas `studio` (modèle, agents, audit) et `rdm` (SCRDM UN/CEFACT D23B : 141 ABIE, 1 725 BIE) |
| Sécurité | jetons du service `auth` ; droits `procedures.lire`, `.modeliser`, `.approuver`, `.publier` |
| Moteurs | lecture/contrôle BPMN (modèle Operaton), Drools 10 (compilation et banc d'essai des règles) |

Guide pas à pas : [Ajouter une nouvelle procédure](../docs/ajouter-une-procedure.md). Cas de référence : [FIMEX](../docs/fimex.md).

## Démarrage

1. Bases : `..\database\creer-bases-guichet-unique.cmd` (une fois, mot de passe `postgres`).
2. `construire.cmd` puis `lancer.cmd` → http://localhost:8084 (connexion par le SSO, port 8090).

Au premier démarrage, **FIMEX** est amorcé en version 1 *brouillon* (SFD e-GUCE 3G v1.4, modèle DM_FIMEX v1.1).

## Modèle

Terminologie GUCE (`trade_process › process_phase › circuit`, `request_nature`), `procedure` → `procedure_version`
(statut porté par la version : `draft › in_review › approved › published › superseded / retired`), et par version :
`request_type` (code CUR), `procedure_actor`, `case_status`, `form_section` / `form_field` (UNTDED, OMD, DEN UN/CCL,
chemin BIE SCRDM, liste du Référentiel Commun, pré-remplissage), `required_document`, `fee_item`,
`message_definition` / `message_field` (contrat), `procedure_flow` / `flow_message` (usage), `process_model` (BPMN),
`rule_set` (DRL + règles de gestion), `deliverable`. Agents : `agent`, `agent_policy` (plafond par action),
`ai_run`, `ai_proposal` (revue accepter / corriger / rejeter). Journal `audit_event` chaîné (prev_hash / row_hash,
ajout seul). Une version publiée est immuable (déclencheur) ; l'approbateur diffère de l'auteur de la soumission
(contrainte).

## Agents

| Agent | Rôle |
|---|---|
| AI Orchestrator | enchaîne les agents, applique la politique d'autonomie, refuse et journalise les actions réservées à l'humain |
| Document Agent | lit la SFD (.docx / .txt) : pièces, acteurs, frais et répartition, délai, règles RG / RM — avec l'extrait source |
| Data Agent | rapproche chaque champ d'un BBIE du SCRDM D23B (chemin depuis le message maître, type de correspondance, score) et propose les listes `REF_*` |
| Single Window Agent — Process Design | génère le BPMN exécutable (avec diagramme) et les statuts manquants |
| Single Window Agent — Rules | génère les sections DRL « frais » et « pièces conditionnelles », signale les RG sans règle |
| Compliance Agent | contrôles bloquants avant soumission et publication |

Sans clé d'API (`ANTHROPIC_API_KEY` dans `.env.local`), les agents fonctionnent en mode heuristique déterministe.

## API principale (`/api/v1`)

`procedures` (liste, création : `model` = `GABARIT` \| `VIDE` \| code à copier), `procedures/{code}/versions`,
`versions/{id}` · `parts/{partie}` · `checks` · `submit` · `approve` · `reject` · `publish` · `retire` · `package` ·
`bpmn` · `drl` · `rules/test`, `versions/{id}/agents/{agent}/run`, `versions/{id}/proposals`, `proposals/{id}/review`,
`published` et `published/{code}` (paquet + en-tête `X-Nexora-Package-Hash`, consommés par les e-Services),
`terminology`, `rdm` (`search`, `abies/{code}`, `resolve`), `agents`, `runs`, `journal`.
