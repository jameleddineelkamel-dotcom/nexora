# Générateur du modèle SCRDM D23B (migrations V6 et V7)

Reconstruit le Supply Chain Reference Data Model UN/CEFACT D23B complet à partir de sa forme machine
(`github.com/uncefact/spec-JSONschema`, `library/BuyShipPay/D23B`), le compare au contenu chargé par
`V2__rdm_scrdm_d23b.sql` et écrit :

- `V6__scrdm_complet_bsp.sql` : ABIE renvoyant à la bibliothèque BSP (vides en V2) et leurs BIE, cardinalités corrigées,
  types de données, en-têtes des listes de codes, restrictions contextuelles, couche Buy-Ship-Pay (domaines, processus,
  entités, règles, rôles, messages, instruments de référence et leurs liens) ;
- `V7__rdm_listes_de_codes_d23b.sql` : valeurs des listes de codes.

| Fichier | Rôle |
|---|---|
| `telecharger-source.cjs` | télécharge les schémas et les listes de codes dans `./source` (ignoré par git) |
| `scrdm-modele.cjs` | résout les ABIE du SCRDM (restriction `allOf` ou renvoi `$ref` vers le BSP), cardinalités, types, restrictions |
| `lire-v2.cjs` | relit les ABIE et BIE de V2 pour ne produire que les écarts |
| `bsp-donnees.cjs` | contenu métier tiré des supports de formation e-GUCE 3G (BSP, Recommandations, EDIFACT-ebXML-NIMP) |
| `generer-v6-v7.cjs` | vérifie chaque chemin BIE cité (restrictions comprises), les listes, les codes UNTDID 3035, puis écrit V6 et V7 |

```bat
node telecharger-source.cjs
node generer-v6-v7.cjs
```

Une erreur « Chemin BIE invalide » signale une donnée de `bsp-donnees.cjs` qui ne correspond pas au SCRDM. Les migrations
déjà appliquées par Flyway ne doivent pas être régénérées : pour une nouvelle publication (D24A…), créer une nouvelle
migration à partir de ce générateur.
