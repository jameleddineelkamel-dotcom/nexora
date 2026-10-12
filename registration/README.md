# NEXORA · registration — Registre des opérateurs économiques

Enrôlement des opérateurs au guichet unique : NIU, raison sociale, personnalité juridique, nationalité, immatriculations
(patente, RCCM), adresse et contacts, activité, régime fiscal ; **pièces d'enrôlement** réutilisées par toutes les
procédures ; **comptes rattachés** (titulaire, ou mandataire CDA) ; historique des modifications.

| | |
|---|---|
| Port | **8083** (interface de développement : 4203) |
| Base | PostgreSQL `nexora_registration` — schéma `registre` |
| Sécurité | jetons du service `auth` ; droits `registration.lire`, `.gerer`, `.completer` ; un opérateur lit les fiches auxquelles son compte est rattaché |

## Rôle dans les procédures

- **Pré-remplissage** : les champs d'un formulaire publié dont le « pré-remplissage » vaut un attribut du registre
  (`legalName`, `city`, `rccm`…) sont remplis à partir du NIU ; les pièces d'enrôlement dont le code (P1…Pn)
  correspond aux pièces de la procédure sont jointes automatiquement.
- **Titulaire / mandataire** : un opérateur ne dépose que pour un NIU dont il est titulaire (RG4) ; un CDA peut agir
  pour des tiers (RG5).
- **Complétion** (`POST /api/v1/parties/{niu}/complete`, droit `registration.completer`) : à la délivrance d'un livrable,
  les attributs **vides** de la fiche sont complétés avec les données de la demande, jamais écrasés (RG2 UC-05 FIMEX).

## Démarrage

`construire.cmd` puis `lancer.cmd` → http://localhost:8083. Avec `REGISTRATION_DEMO=true` (défaut), un registre vide
reçoit trois opérateurs fictifs (NIU de démonstration), leurs pièces factices et le rattachement des comptes
`test.operateur` (titulaire de SCIE SA) et `test.cda` (mandataire).

## API (`/api/v1`)

`me/parties`, `parties` (recherche, enrôlement), `parties/{niu}` (lecture, modification), `parties/{niu}/complete`,
`parties/{niu}/documents` (+ `/{id}/content`), `parties/{niu}/users`, `parties/{niu}/history`, `attributes`, `dashboard`.
