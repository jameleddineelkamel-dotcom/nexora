# NEXORA · auth — Authentification unique (SSO) et habilitations

Microservice d'accès de la plateforme NEXORA : une seule connexion pour toutes les applications,
gestion des **utilisateurs**, **groupes**, **rôles** et **droits** (habilitations), journal de sécurité.

| | |
|---|---|
| Port | **8090** (interface Angular de développement : 4202) |
| Base | PostgreSQL `nexora_auth` (Flyway, `db/migration/V1__auth.sql`) |
| Jeton | JWT RS256 signé, durée 8 h, clé publique sur `/.well-known/jwks.json` |
| Session SSO | cookie `NEXORA_SSO` (HttpOnly, SameSite=Lax), 10 h |

## Démarrage

1. Créer la base : `database\creer-base.cmd` (demande le mot de passe `postgres`).
2. Copier `.env.example` en `.env.local` et renseigner `AUTH_DB_PASSWORD` et `AUTH_ADMIN_PASSWORD`
   (`.env.local` n'est jamais versionné).
3. `construire.cmd` puis `lancer.cmd` → http://localhost:8090/login

Au premier démarrage, si aucun utilisateur n'existe, le compte **`admin`** est créé (groupe *Administrateurs*)
avec le mot de passe `AUTH_ADMIN_PASSWORD`. Changez-le ensuite depuis *Mon compte*.

## Rôles et droits livrés

| Droit | Administrateur | Gestionnaire | Consultation |
|---|:-:|:-:|:-:|
| `refdata.lire` — consulter le référentiel | ✔ | ✔ | ✔ |
| `refdata.export` — exporter | ✔ | ✔ | ✔ |
| `refdata.donnees` — gérer les codes, charger des fichiers | ✔ | ✔ | |
| `refdata.metadonnees` — fiche ISO 19115, pièces jointes | ✔ | ✔ | |
| `refdata.structure` — créer/modifier les tables (onglet Structure) | ✔ | | |
| `refdata.api` — onglet API & SQL | ✔ | | |
| `auth.administration` — console d'administration des accès | ✔ | | |

Groupes : *Administrateurs*, *Gestionnaires du référentiel*, *Consultants*. Les rôles d'un groupe sont hérités
par ses membres ; un rôle peut aussi être attribué directement. La matrice rôles × droits se modifie dans
**Administration → Rôles et habilitations** (effet à la prochaine connexion).

## Intégrer une application (flux SSO)

1. L'application redirige vers `GET /sso/authorize?redirect_uri=<url>&state=<x>`.
2. Si la session SSO est ouverte : retour immédiat sur `<url>#access_token=…&expires_in=…&state=<x>` ;
   sinon : page de connexion, puis retour.
3. L'application appelle ses API avec `Authorization: Bearer <jeton>` ; le backend valide le jeton
   (Spring `oauth2-resource-server`, `jwk-set-uri` = `http://localhost:8090/.well-known/jwks.json`)
   et lit les droits dans la revendication `permissions`.
4. Déconnexion : `GET /sso/logout?redirect_uri=<url>`.

Seules les origines listées dans `nexora.auth.redirect-origins` sont acceptées comme `redirect_uri`.

## Sécurité

- Mots de passe BCrypt ; au moins 10 caractères avec lettres et chiffres ; mot de passe provisoire à changer à la 1re connexion.
- Verrouillage 15 min après 5 échecs ; déverrouillage par un administrateur.
- Un administrateur ne peut ni se désactiver ni retirer son propre droit d'administration ; les rôles système ne peuvent pas être supprimés.
- Journal de sécurité : connexions, échecs, déconnexions, changements de comptes, groupes, rôles et droits.
