# NEXORA — AI-powered Trade Digital Platform

Plateforme numérique du commerce extérieur, organisée en microservices indépendants (une base de données par
service), alignée sur les standards UN/CEFACT, OMD, ISO et sur le **modèle de référence de la chaîne logistique
internationale** (Buy – Ship – Pay).

| Microservice | Rôle | État | Port |
|---|---|---|---|
| [`refdata`](refdata/) | **Référentiel Commun** : tables de référence REF_*, métadonnées ISO 19115, historique, services de requête | ✅ disponible | 8082 |
| `party` | Party Management : opérateurs, importateurs/exportateurs, déclarants, banques | à venir | 8083 |
| `procedures` | Procédures du commerce extérieur (import, export, transit, contrôles techniques) | à venir | 8084 |
| `eservices` | e-Services : formulaires, demandes, documents électroniques | à venir | 8085 |
| `data` | Services DATA : entrepôt de données, statistiques, échanges | à venir | 8086 |

## Conventions communes

- **Stack** : Java 21 · Spring Boot 4 · PostgreSQL 17 · Angular 22 ; chaque microservice produit un jar exécutable
  qui embarque son interface.
- **Une base par microservice** (`nexora_<service>`), secrets dans `.env.local` (jamais versionné).
- **API REST versionnée** : `/api/v1/...`, erreurs au format RFC 9457 (`application/problem+json`, propriété `erreurs`).
- **Traçabilité** : chaque appel d'écriture transmet `X-Nexora-User`, `X-Nexora-Channel` (UI, API, IMPORT, SYNC) et
  `X-Nexora-Reason` ; ces métadonnées alimentent l'historique de chaque service.
- **Données de référence** : les autres services ne dupliquent pas les listes de codes ; ils interrogent `refdata`
  (`GET /api/v1/lookup/{REF_TABLE}`) ou ses vues SQL `referentiel.ref_*`.
