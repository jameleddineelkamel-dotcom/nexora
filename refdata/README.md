# NEXORA · refdata — Référentiel Commun du commerce extérieur

Premier microservice de la plateforme NEXORA : gestion centralisée des **tables de référence** (listes de codes)
internationales, régionales et nationales, selon le rapport **« Référentiel Commun » GUCE V1.5**, les
recommandations **UN/CEFACT** et le **modèle de référence de la chaîne logistique internationale** (Buy-Ship-Pay).

## Ce qui est livré

| Élément | Contenu |
|---|---|
| Catalogue | **119 tables `REF_*`** classées en 9 catégories : standards internationaux, normes régionales (CEMAC), tables nationales, domaines d'activité (dédouanement, transport, contrôle technique, cacao-café, véhicules) |
| Métadonnées | Fiche **ISO 19115** de chaque table (identification, cycle de vie, organismes associés) issue du rapport, extensible par des métadonnées libres ; indicateur de complétude |
| Structures | Colonnes typées issues des attributs du rapport : balise XML, cardinalité, type..longueur, référence **UNTDED**, clés étrangères |
| Données | **119 871 codes** : pays ISO 3166, devises ISO 4217, Incoterms® 2020, modes et moyens de transport (Rec. 19, 28), **UN/LOCODE (116 086 lieux)**, emballages (Rec. 21), unités de mesure (Rec. 20), PAYTERMS (Rec. 17), frais de transport FCC (Rec. 23), statuts (Rec. 24), documents (UNCL 1001), NACAM |
| Buy-Ship-Pay | Chaque table est rattachée aux phases Buy / Ship / Pay du modèle UN/CEFACT |

### Couverture des exigences (spécification « Gestion des Données de Référence »)

| Exigence | Réalisation |
|---|---|
| REF-01/02 Listes internationales, régionales, nationales | Catégories et attribut **Source** (Internationale, Régionale, Nationale) ; contenu **Simple** ou **Complexe** (avec attributs) |
| REF-03 Éditer, consulter, importer, exporter (UI et backend) | Interface Angular + API REST ; export CSV / JSON / Excel ré-importable |
| REF-04 Gestion des métadonnées | Fiche ISO 19115 éditable + création de nouvelles métadonnées |
| REF-05 Chargement des listes de codes | Import CSV / JSON / Excel, **simulation** préalable, fusion ou remplacement, journal des chargements |
| REF-06 Adaptateurs DataWarehouse / Big Data | **Vue SQL typée** `referentiel.ref_xxx` générée pour chaque table |
| REF-07 Interopérabilité | Services `lookup` (code valide à une date, libellé FR/EN), recherche, définitions |
| Pas de suppression | Un code est **invalidé** (statut + fin de validité) ; la base refuse toute suppression (trigger) |
| Périodes de validité | `validFrom` / `validTo` par code, consultation « valide au » (voyage dans le temps) |
| Historique | Trigger PostgreSQL : auteur, canal, **motif**, lot d'import, détail avant / après champ par champ |
| Nouvelles tables | **Création dynamique** (sans redéploiement), y compris **à partir d'un fichier** avec détection des types et des références |

## Architecture

```
frontend/ (Angular 22)  ──►  backend/ (Spring Boot 4.1, Java 21)  ──►  PostgreSQL 17 : base nexora_refdata
                               /api/v1/...                               ref_category, ref_table, ref_column (catalogue)
                                                                         ref_entry (codes, attributs JSONB)
                                                                         ref_history (trigger), ref_import
                                                                         schéma referentiel : vues ref_xxx générées
```

Moteur générique : la structure de chaque table est décrite dans le catalogue (`ref_column`), les codes sont
stockés dans `ref_entry` avec leurs attributs typés (JSONB) et exposés en SQL sous `referentiel.ref_xxx` avec des
colonnes nommées d'après les balises XML. C'est ce qui permet d'ajouter une table à chaud.

## Installation

1. Copier `.env.example` en `.env.local` et choisir `REFDATA_DB_PASSWORD`.
2. Double-cliquer sur `database\creer-base.cmd` (mot de passe de `postgres` demandé).
3. Double-cliquer sur **`construire.cmd`** puis **`lancer.cmd`** → http://localhost:8082

Au premier démarrage, Flyway crée le schéma et le **chargement initial** importe catalogue et données (~17 s).

### Développement

```
cd backend
.\mvnw.cmd spring-boot:run          # API sur 8082
cd frontend
npm.cmd start                       # interface sur http://localhost:4201 (proxy /api → 8082)
```

## API (extraits)

| Méthode | URL | Rôle |
|---|---|---|
| GET | `/api/v1/dashboard` | Indicateurs |
| GET | `/api/v1/categories` · `/api/v1/tables?category=&source=&phase=` | Catalogue |
| GET/POST/PUT | `/api/v1/tables[/{code}]` | Définition (structure + fiche ISO 19115), création dynamique |
| PATCH | `/api/v1/tables/{code}/status` | Archivage / réactivation |
| GET | `/api/v1/tables/{code}/entries?q=&status=&validAt=&parent=&page=` | Codes (recherche sans accents) |
| POST/PUT | `/api/v1/tables/{code}/entries` · `/entry?code=` | Création / modification d'un code |
| POST | `/api/v1/tables/{code}/entry/invalidate?code=` · `/reactivate` | Invalidation / réactivation |
| GET | `/api/v1/lookup/{code}?lang=fr\|en&validAt=` · `/lookup/{code}/resolve?code=` | Services de requête |
| POST | `/api/v1/tables/{code}/import?mode=MERGE\|REPLACE&dryRun=` | Chargement de fichier |
| GET | `/api/v1/tables/{code}/export?format=csv\|json\|xlsx` | Export |
| POST | `/api/v1/tables/infer` | Proposition de structure à partir d'un fichier |
| GET | `/api/v1/history?table=&code=&operation=&author=&channel=&batch=` | Historique |
| GET | `/api/v1/search?q=` | Recherche globale |
| GET/POST | `/api/v1/catalogue/export` · `/catalogue/import` | Définitions en masse |

En-têtes : `X-Nexora-User`, `X-Nexora-Channel`, `X-Nexora-Reason` (URL-encodés) alimentent l'historique.

## Jeu de données initial

`tools/seed/construire-seed.mjs` régénère `backend/src/main/resources/seed/` à partir des documents sources
(rapport Word, classeurs UN/CEFACT et NACAM) :

```
cd tools\seed
npm.cmd install
node construire-seed.mjs "D:\...\REFDATA"
```

Particularités traitées : codes Rec. 28 uniques par mode (code composé `mode-code`), libellés équivalents Rec. 23
regroupés en synonymes, cardinalités du rapport assouplies quand la source ne renseigne pas la donnée,
monnaies uniques dérivées de la table ISO 4217 par entité.
