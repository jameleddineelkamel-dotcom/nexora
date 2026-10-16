# Stockage des fichiers : PostgreSQL ou MinIO

e-Services conserve deux sortes de fichiers :

| Fichiers | Table | Clé dans MinIO |
|---|---|---|
| Pièces jointes déposées par le demandeur (ou reprises du registre) | `wfe.attachment` | `pieces/{dossier}/{code pièce}-{empreinte SHA-256}` |
| Livrables signés (PDF avec cachet électronique) | `wfe.issued_document` | `livrables/{numéro du document}.pdf` |

Par défaut, le contenu est stocké dans PostgreSQL (colonne `content`). Si l'adresse MinIO est renseignée, les nouveaux
fichiers sont déposés dans le compartiment MinIO (API S3) et la ligne ne garde que la clé de l'objet (`storage_key`).
L'empreinte SHA-256, le nom, le type et la taille restent en base dans les deux cas. Le choix n'a aucun effet visible
dans l'interface : téléchargement des pièces, PDF des livrables et vérification par code QR fonctionnent pareil.

## 1. Installer MinIO (poste de développement Windows)

1. Télécharger `minio.exe` depuis le site officiel de MinIO et le placer par exemple dans `C:\minio`.
2. Choisir un identifiant et un mot de passe d'administration (8 caractères au moins), puis lancer :

   ```bat
   set MINIO_ROOT_USER=nexora
   set MINIO_ROOT_PASSWORD=<mot de passe>
   C:\minio\minio.exe server C:\minio\data --console-address ":9001"
   ```

   L'API S3 écoute sur `http://localhost:9000` et la console d'administration sur `http://localhost:9001`.
3. Pour la production, créer dans la console un utilisateur propre à e-Services (clé d'accès et clé secrète) limité au
   compartiment `nexora-eservices`, plutôt que d'utiliser l'administrateur.

## 2. Configurer e-Services

Dans `eservices/.env.local` (jamais versionné) :

```properties
MINIO_URL=http://localhost:9000
MINIO_ACCESS_KEY=<clé d'accès>
MINIO_SECRET_KEY=<clé secrète>
MINIO_BUCKET=nexora-eservices
```

Redémarrer e-Services. Le journal de démarrage indique le mode retenu, par exemple « Stockage des fichiers : MinIO
http://localhost:9000 (compartiment nexora-eservices) ». Le compartiment est créé au premier dépôt s'il n'existe pas.
Sans `MINIO_URL`, e-Services démarre comme avant et stocke les fichiers dans PostgreSQL.

## 3. Migrer les fichiers existants vers MinIO

Les fichiers déposés avant l'activation restent lisibles en base. Pour les déplacer vers MinIO, un administrateur
d'e-Services appelle les deux services suivants (protégés par le droit d'administration) :

| Appel | Effet |
|---|---|
| `GET /api/v1/admin/storage` | mode (`DATABASE` ou `MINIO`), compartiment, nombre de pièces et de livrables en base et dans MinIO |
| `POST /api/v1/admin/storage/migrate?lot=200` | dépose dans MinIO jusqu'à 200 pièces et 200 livrables encore en base, puis vide leur contenu en base ; à relancer jusqu'à ce que `database` vaille 0 |

Chaque lot est tracé dans le journal d'audit (`STOCKAGE_MIGRE`). Une migration interrompue peut être relancée sans
risque : seuls les fichiers encore en base sont repris.

## 4. Points d'attention

- Un fichier rangé dans MinIO ne se relit que si MinIO est configuré : ne retirez pas `MINIO_URL` après une migration.
- Sauvegardez le dossier de données de MinIO avec la base PostgreSQL : une ligne en base sans son objet est un
  fichier perdu.
- La structure (migration Flyway `V4__stockage_objet.sql` d'e-Services) garantit que chaque ligne a soit son contenu en
  base, soit sa clé MinIO.
- Les contenus des messages échangés (`wfe.message_content`) restent en base.
