# NEXORA · eservices — Runtime du guichet unique (eFORCE, eGOV, e-PAYMENT)

Exécution des procédures publiées par le studio : dépôt des demandes par les opérateurs et les CDA (eFORCE), paiement
en ligne (e-PAYMENT), cotation et traitement par les administrations (eGOV), signature et délivrance du livrable,
supervision. Le Runtime est **générique** : il ne contient aucune règle propre à une procédure, tout vient du paquet.

| | |
|---|---|
| Port | **8085** (interface de développement : 4205) |
| Base | PostgreSQL `nexora_eservices` — schéma `wfe` (moteur des échanges), vues `proc_<code>`, tables `ACT_*` d'Operaton |
| Workflow | **Operaton 2.1** (fork communautaire de Camunda 7, compatible Spring Boot 4) embarqué ; BPMN avec extensions `camunda:` |
| Règles | **Drools 10** ; DRL du paquet compilé une fois par déploiement |
| Livrables | PDF (OpenPDF) avec code QR (ZXing) et cachet électronique visible HMAC-SHA256 (`ESERVICES_SEAL_SECRET`) |
| Sécurité | jetons du service `auth` ; droits `eservices.deposer`, `.mandataire`, `.traiter`, `.signer`, `.encaisser`, `.superviser`, `.administrer` |

## Démarrage

1. Bases : `..\database\creer-bases-guichet-unique.cmd` ; secrets dans `.env.local` (`ESERVICES_DB_PASSWORD`, `ESERVICES_SEAL_SECRET`).
2. `construire.cmd` puis `lancer.cmd` → http://localhost:8085.
3. **Administration › Procédures** : déployer les procédures publiées (droit `eservices.administrer`, plus
   `procedures.lire` et `refdata.lire` pour lire le paquet et les listes de codes).

Services appelés avec le jeton de l'utilisateur : `procedures` (paquets), `registration` (pré-remplissage par NIU, pièces
d'enrôlement, mise à jour à la délivrance), `refdata` (listes de codes au déploiement).

## Modèle (schéma `wfe`)

D'après « NEXORA_WFE_SCRDM_Modele_Donnees_PG17 » et le dossier v4.2 (§10.12, §10.14) :

| Table | Rôle |
|---|---|
| `procedure_deployment` | paquet publié (JSON + SHA-256), définition Operaton, version active |
| `dossier` | dossier (CUR = `dossier_no`, attribué au dépôt), type, nature, dossier source, demandeur, statut, cotation, données du formulaire (JSONB), critères projetés (GIN) |
| `dossier_criterion_value` | critères normalisés (projection automatique dans `dossier.search_criteria`) |
| `dossier_status_history`, `cotation_history` | historiques en ajout seul |
| `flux`, `message`, `message_content` (partitionnée par mois), `payload_value`, `message_state` / `_transition` / `_history` | messages JSON, XML, CSV, TXT : enveloppe ebXML (MessageId, RefToMessageId, corrélation, idempotence, expiration), contenu séparé avec empreinte, valeurs indexées, états contrôlés |
| `attachment`, `message_attachment` | pièces jointes |
| `payment_notice`, `payment_line` | avis de paiement et répartition par bénéficiaire |
| `issued_document` | livrables (numéro dérivé du CUR, versions d'amendement, validité, QR, cachet, PDF) |
| `processing_location`, `admin_unit`, `unit_member` | paramétrage de la cotation |
| `cur_sequence` | séquence CUR par pays, année, code procédure |
| `code_list`, `code_list_value` | projection des listes du Référentiel Commun |
| `partner`, `audit_event` | partenaires d'échange ; journal chaîné |

## API principale (`/api/v1`)

eFORCE : `catalog`, `catalog/{code}/form`, `catalog/{code}/titles`, `lookup/{table}`, `dossiers` (création, liste
`scope=mine|assigned|all`, recherche `criterion=code=valeur`), `dossiers/{id}` (détail, `data`, `attachments`,
`estimate`, `submit`, `deliverable/preview`), `payment-notices/{no}` et `/pay`. eGOV : `tasks`, `tasks/{id}/complete`.
Échanges : `messages/{id}`, `messages/{id}/content`, `messages/inbound?code=` (banques, partenaires).
Public : `public/verify/{no}` (cible du code QR). Supervision : `supervision/dashboard`, `payments`, `statements`
(relevé CSV), `messages`, `tasks`, `journal`, `views`. Administration : `admin/published`, `admin/deployments`,
`admin/code-lists`, `admin/locations`, `admin/units`, `admin/members`, `admin/incidents`.

Ajouter une procédure : [guide](../docs/ajouter-une-procedure.md). Cas FIMEX : [docs/fimex.md](../docs/fimex.md).
