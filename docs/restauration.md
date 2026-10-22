# Sauvegardes et restauration

Ce document s'adresse à un ingénieur qui ne connaît pas le projet et doit
remettre la plateforme en service sur un serveur vierge. Objectif : moins de
quatre heures, en suivant les étapes dans l'ordre. Un modèle de procès-verbal
de test de restauration figure en fin de document.

---

## 1. Ce qui est sauvegardé, où, comment

Chaque soir, à l'heure du laboratoire (`Africa/Porto-Novo`), l'API fait trois
choses (`SauvegardeService`) :

| Heure | Quoi | Où |
|-------|------|----|
| 18h30 | Export de la base (`pg_dump`), **chiffré à la volée** avec `age` contre la clé publique `BACKUP_AGE_RECIPIENT`. Aucun SQL en clair ne touche le disque. | Fichier `backup-AAAA-MM-JJ-HH-MM-SS.sql.age` dans le volume `backups` du serveur (30 derniers conservés), **puis copié** dans le seau S3 de sauvegardes sous `base/`. |
| 18h30 (suite) | Synchronisation du volume `storage` (images d'examen, documents, photos…) : seuls les fichiers nouveaux ou modifiés partent (comparaison taille + date avec l'inventaire du seau). Les fichiers sont déjà chiffrés au repos quand `STORAGE_ENCRYPTION_ENABLED=true` ; ils ne sont pas rechiffrés. | Même seau, sous `storage/`. |
| 20h00 | Contrôle indépendant : un objet `base/backup-<date du jour>*` existe-t-il dans le seau ? | — |

Tout échec (export en erreur ou vide, copie refusée, contrôle négatif, clé
publique absente en production) part par courriel aux adresses du réglage
`admin_mails` (écran Paramètres) et à `BACKUP_ALERT_EMAIL`.

Le seau de sauvegardes est **distinct** du seau de fichiers (`S3_BUCKET`) et
accédé avec des clés **en écriture seule** : un serveur compromis peut déposer,
jamais effacer.

---

## 2. Prérequis

Avant toute restauration, réunir :

1. **La clé privée `age`** correspondant à `BACKUP_AGE_RECIPIENT`. Elle n'est
   **pas** sur le serveur, et c'est voulu. Elle est dans le coffre du
   laboratoire et chez le prestataire (fichier `cle-sauvegardes.txt`, une
   ligne `AGE-SECRET-KEY-1…`). Sans elle, aucune sauvegarde n'est lisible.
2. **Un accès au seau de sauvegardes** avec le droit de lecture
   (`s3:GetObject`, `s3:ListBucket`, `s3:ListBucketVersions`). Ce n'est pas
   la clé du serveur, qui ne sait qu'écrire : c'est un second utilisateur IAM,
   « restauration », dont les clés sont aussi dans le coffre.
3. **Le fichier `.env` de production** (ou sa copie dans le coffre) : il
   contient `STORAGE_ENCRYPTION_KEY`, sans laquelle les fichiers restaurés
   sont illisibles, `JWT_SECRET`, les identifiants SMTP, SMS, facturation.
4. Les outils, sur le poste qui pilote la restauration :
   `age` (<https://github.com/FiloSottile/age>, paquet `age` sur Debian,
   Alpine, Homebrew), l'AWS CLI (`aws`) ou `mc` pour MinIO, `docker` et
   `docker compose` sur le serveur cible.

---

## 3. Préparer le seau (une fois, à la mise en place)

Ces réglages se font sur le seau, **pas dans le code**. Les exemples sont pour
AWS ; MinIO expose les mêmes notions (`mc version enable`, `mc ilm add`,
`mc admin policy`).

### 3.1 Créer le seau et activer le versionnage

```bash
aws s3api create-bucket --bucket labo-sauvegardes --region eu-west-3 \
  --create-bucket-configuration LocationConstraint=eu-west-3
aws s3api put-public-access-block --bucket labo-sauvegardes \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-versioning --bucket labo-sauvegardes \
  --versioning-configuration Status=Enabled
```

Le versionnage est ce qui protège contre un écrasement : la synchronisation
`storage/` renvoie un fichier modifié sous la même clé, et l'ancienne version
reste lisible.

### 3.2 Cycle de vie : 30 versions quotidiennes, puis 12 mensuelles

Les exports de base portent leur date dans leur nom ; ce sont donc des objets
distincts, et le cycle de vie joue sur leur **expiration**. Règle retenue :

- `base/` — un export par jour pendant 30 jours ; au-delà, on ne garde qu'un
  export par mois pendant 12 mois. S3 ne sait pas « garder un par mois » : la
  règle expire tout après 30 jours, et un **second préfixe `mensuel/`** reçoit,
  le 1er de chaque mois, une copie faite à la main ou par une règle de
  réplication (ci-dessous) qui expire à 365 jours.
- `storage/` — versions non courantes conservées 30 jours (le temps de voir
  qu'un fichier a été écrasé par erreur).

```json
{
  "Rules": [
    { "ID": "base-30-jours", "Status": "Enabled",
      "Filter": { "Prefix": "base/" },
      "Expiration": { "Days": 30 },
      "NoncurrentVersionExpiration": { "NoncurrentDays": 7 } },
    { "ID": "mensuel-12-mois", "Status": "Enabled",
      "Filter": { "Prefix": "mensuel/" },
      "Expiration": { "Days": 365 } },
    { "ID": "storage-versions-30-jours", "Status": "Enabled",
      "Filter": { "Prefix": "storage/" },
      "NoncurrentVersionExpiration": { "NoncurrentDays": 30 } },
    { "ID": "nettoyage-multipart", "Status": "Enabled",
      "Filter": { "Prefix": "" },
      "AbortIncompleteMultipartUpload": { "DaysAfterInitiation": 2 } }
  ]
}
```

```bash
aws s3api put-bucket-lifecycle-configuration --bucket labo-sauvegardes \
  --lifecycle-configuration file://cycle-de-vie.json
```

Copie mensuelle (à faire le 1er du mois depuis le poste du prestataire, avec
la clé de restauration — le serveur ne sait pas copier, il ne sait qu'écrire) :

```bash
DERNIER=$(aws s3 ls s3://labo-sauvegardes/base/ | sort | tail -1 | awk '{print $4}')
aws s3 cp "s3://labo-sauvegardes/base/$DERNIER" "s3://labo-sauvegardes/mensuel/$DERNIER"
```

### 3.3 Politique IAM : écriture seule pour le serveur

L'utilisateur dont les clés vont dans `BACKUP_S3_ACCESS_KEY` /
`BACKUP_S3_SECRET_KEY` peut déposer et lister, **jamais effacer ni lire** :

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "Deposer", "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:AbortMultipartUpload"],
      "Resource": "arn:aws:s3:::labo-sauvegardes/*" },
    { "Sid": "Inventorier", "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": "arn:aws:s3:::labo-sauvegardes" }
  ]
}
```

`s3:ListBucket` est nécessaire : c'est l'inventaire du seau qui dit à la
synchronisation quels fichiers sont déjà partis, et au contrôle de 20h si
l'export du jour existe. Pas de `s3:DeleteObject`, pas de
`s3:DeleteObjectVersion`, pas de `s3:PutLifecycleConfiguration` : un attaquant
qui tient le serveur ne tient pas les sauvegardes.

Un second utilisateur, « restauration », reçoit en plus `s3:GetObject`,
`s3:GetObjectVersion`, `s3:ListBucketVersions`. Ses clés ne vont **jamais**
dans le `.env` du serveur.

### 3.4 Produire la paire de clés `age`

Sur le poste du prestataire, jamais sur le serveur :

```bash
age-keygen -o cle-sauvegardes.txt
# Public key: age1xxxxxxxx…   ← cette ligne va dans BACKUP_AGE_RECIPIENT
```

`cle-sauvegardes.txt` contient la clé privée : coffre du laboratoire +
copie chez le prestataire. Il n'est **jamais** copié sur le serveur, ni
commité, ni envoyé par courriel.

### 3.5 Variables du serveur

Dans le `.env` du serveur (voir `.env.prod.example`) :

```
BACKUP_AGE_RECIPIENT=age1…
BACKUP_S3_BUCKET=labo-sauvegardes
BACKUP_S3_REGION=eu-west-3
BACKUP_S3_ACCESS_KEY=…        # utilisateur écriture seule
BACKUP_S3_SECRET_KEY=…
BACKUP_S3_ENDPOINT=           # vide pour AWS, URL pour MinIO
BACKUP_ALERT_EMAIL=prestataire@exemple.bj
```

Puis `docker compose up -d api`. Le journal doit montrer
`Copie externe des sauvegardes active : seau=labo-sauvegardes`. Le lendemain
matin, vérifier qu'un objet `base/backup-<date>.sql.age` est apparu ; sinon,
un courriel d'alerte est arrivé à 20h et dit pourquoi.

---

## 4. Récupérer l'export sur S3

Avec les clés de l'utilisateur « restauration » :

```bash
export AWS_ACCESS_KEY_ID=…  AWS_SECRET_ACCESS_KEY=…  AWS_DEFAULT_REGION=eu-west-3
# Pour MinIO : ajouter --endpoint-url https://minio.exemple.bj à chaque commande

# Dernier export disponible
aws s3 ls s3://labo-sauvegardes/base/ | sort | tail -5
DERNIER=$(aws s3 ls s3://labo-sauvegardes/base/ | sort | tail -1 | awk '{print $4}')
aws s3 cp "s3://labo-sauvegardes/base/$DERNIER" ./restauration/

# Les fichiers (peut être long : quelques dizaines de Go)
aws s3 sync s3://labo-sauvegardes/storage/ ./restauration/storage/
```

Pour revenir à un état antérieur d'un fichier écrasé :
`aws s3api list-object-versions --bucket labo-sauvegardes --prefix storage/<chemin>`
puis `aws s3api get-object --version-id <id> …`.

---

## 5. Déchiffrer

```bash
cd restauration
age -d -i /chemin/vers/cle-sauvegardes.txt -o base.sql "$DERNIER"
head -c 300 base.sql        # doit commencer par "-- PostgreSQL database dump"
```

`base.sql` est le dossier clinique entier en clair : le laisser sur un poste
chiffré, l'effacer dès que la restauration est validée (`shred -u base.sql`).

Les fichiers de `storage/` ne se déchiffrent **pas** ici : ils sont chiffrés
par l'application avec `STORAGE_ENCRYPTION_KEY`, et c'est l'API restaurée qui
les lira. Ne pas les toucher.

---

## 6. Restaurer sur un serveur vierge

Ordre : **base, fichiers, variables**, puis démarrage. Durées indicatives
pour une base de quelques Go et quelques dizaines de Go de fichiers.

### 6.1 Préparer le serveur (≈ 30 min)

```bash
# Docker + compose installés, puis :
git clone https://github.com/KAWA-SERVICES/backend_api_labocap.git labo && cd labo
git checkout main
cp .env.prod.example .env
```

Remplir `.env` **depuis la copie du `.env` de production** conservée dans le
coffre. Les valeurs qui ne se devinent pas et dont dépend la lisibilité des
données :

| Variable | Si elle change… |
|----------|-----------------|
| `STORAGE_ENCRYPTION_KEY` | tous les fichiers restaurés sont illisibles. Doit être **exactement** celle d'avant. |
| `DB_PASSWORD` | doit correspondre à ce qu'on donne à PostgreSQL ci-dessous (nouvelle valeur acceptée). |
| `JWT_SECRET` | les sessions ouvertes expirent, c'est tout (nouvelle valeur acceptée). |
| `BACKUP_*` | à remettre, sinon le serveur restauré ne se sauvegarde plus. |

### 6.2 Base de données (≈ 20 min à 1 h selon la taille)

Le compose monte au premier démarrage un dump dans
`/docker-entrypoint-initdb.d/` — mais ce chemin est historique
(`./backups/backup-2026-07-06-….sql`). Deux voies :

**Voie simple** — remplacer ce fichier par le dump déchiffré, en gardant le
nom attendu par `docker-compose.yml` :

```bash
mkdir -p backups
cp /chemin/vers/base.sql backups/backup-2026-07-06-20-01-06.sql
docker compose up -d db
docker compose logs -f db      # attendre "database system is ready to accept connections" APRÈS l'import
```

PostgreSQL ne joue ce dump **que si le volume `db-data` est vide** — ce qui
est le cas sur un serveur vierge. Pour recommencer : `docker compose down -v`
(efface aussi `storage` et `backups` : à ne faire qu'avant 6.3).

**Voie explicite** — base démarrée vide, import à la main :

```bash
docker compose up -d db
docker compose exec -T db psql -U postgres -d labo_anapath -v ON_ERROR_STOP=1 < base.sql
```

Ne **pas** lancer l'API avant que l'import soit terminé : Flyway valide le
schéma au démarrage (`baseline-version: 50`) et refuserait une base à moitié
chargée.

### 6.3 Fichiers (≈ 30 min à 2 h selon le volume)

Le volume `storage` doit appartenir à l'utilisateur de l'image (`appuser`,
non-root). Copier via un conteneur jetable plutôt que directement dans
`/var/lib/docker/volumes`, pour garder les droits :

```bash
docker compose build api
UID_GID=$(docker compose run --rm --entrypoint id api -u):$(docker compose run --rm --entrypoint id api -g)
docker compose create api            # crée le volume "storage" sans démarrer l'API
docker run --rm -v labo_storage:/dst -v "$PWD/restauration/storage":/src:ro alpine \
  sh -c "cp -a /src/. /dst/ && chown -R $UID_GID /dst"
```

(`labo_storage` : préfixe = nom du dossier du projet ; vérifier avec
`docker volume ls`.)

### 6.4 Démarrer et sauvegarder tout de suite

```bash
docker compose up -d --build --wait
docker compose logs --tail=50 api     # "Copie externe des sauvegardes active", aucun ERROR
```

Puis forcer une sauvegarde pour vérifier la chaîne complète avant d'attendre
18h30 : redémarrer l'API avec, le temps d'un essai, un cron proche — ou plus
simplement, depuis le conteneur :

```bash
docker compose exec api sh -c 'PGPASSWORD=$APP_BACKUP_PASSWORD $APP_BACKUP_COMMAND | age -r $BACKUP_AGE_RECIPIENT > /var/lib/labo/backups/backup-manuel.sql.age && ls -l /var/lib/labo/backups'
```

Un fichier non vide signifie que `pg_dump`, `age` et la clé publique
fonctionnent. La copie S3 et les alertes se vérifieront au premier soir
(courriel à 20h si quelque chose manque).

---

## 7. Vérifications après restauration

### 7.1 Comptages

Les mêmes requêtes **avant** (sur la production, ou sur le dump : `grep -c`
ne suffit pas, utiliser une base jetable) et **après** :

```sql
SELECT 'patients'    AS table_, COUNT(*) FROM patients
UNION ALL SELECT 'demandes',       COUNT(*) FROM test_orders
UNION ALL SELECT 'comptes_rendus', COUNT(*) FROM reports
UNION ALL SELECT 'factures',       COUNT(*) FROM invoices
UNION ALL SELECT 'utilisateurs',   COUNT(*) FROM users;

-- La dernière activité connue : doit être au plus tard la date de l'export
SELECT MAX(created_at) AS derniere_demande FROM test_orders;
SELECT MAX(created_at) AS dernier_compte_rendu FROM reports;
SELECT MAX(created_at) AS derniere_facture FROM invoices;

-- Flyway : la dernière version appliquée doit être celle du code déployé
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;
```

```bash
docker compose exec db psql -U postgres -d labo_anapath -c "<requête>"
```

Les comptages doivent être **identiques** à ceux de l'export ; les dates
maximales doivent être antérieures ou égales à l'heure de l'export.

### 7.2 Fichiers

```bash
docker compose exec api sh -c 'find /var/lib/labo/storage -type f | wc -l; du -sh /var/lib/labo/storage'
```

À comparer au nombre d'objets sous `storage/` dans le seau
(`aws s3 ls --recursive s3://labo-sauvegardes/storage/ | wc -l`).

### 7.3 Depuis l'application

1. **Connexion** : ouvrir le front, se connecter avec un compte administrateur
   (le mot de passe est celui d'avant, les hachages sont dans la base). Le
   second facteur par courriel exige un SMTP joignable : vérifier `MAIL_*`.
2. **Un dossier avec image** : ouvrir une demande récente qui a des clichés
   de lame ; l'image doit s'afficher. Si elle est « illisible » ou absente :
   `STORAGE_ENCRYPTION_KEY` n'est pas la bonne, ou le volume n'a pas les
   bons droits (`docker compose logs api | grep -i "permission\|dechiffr"`).
3. **Un PDF** : télécharger le compte-rendu de cette demande, l'ouvrir, et
   vérifier l'en-tête du laboratoire et la signature.
4. **Une facture** : ouvrir une facture et vérifier son montant.
5. **Les réglages** : écran Paramètres, `admin_mails` et les identifiants
   des passerelles doivent être ceux d'avant.

---

## 8. Procès-verbal de test de restauration

À remplir à chaque exercice (au moins une fois par an, et après tout
changement de serveur, de clé ou de seau). Conserver avec les PV précédents.

```
PROCÈS-VERBAL DE TEST DE RESTAURATION — Labo AnaPath

Date du test : ____ / ____ / ________
Export restauré : base/backup-________________________.sql.age
Export daté du : ____ / ____ / ________ à ____ h ____
Serveur cible : ______________________________ (vierge : oui / non)
Opérateur : ______________________________
Témoin (laboratoire) : ______________________________

DURÉES MESURÉES
  Récupération S3 + déchiffrement : ______ min
  Import de la base               : ______ min
  Copie des fichiers              : ______ min
  Démarrage + vérifications       : ______ min
  TOTAL                           : ______ min   (objectif : < 240 min)

COMPTAGES                 Avant (production)   Après (restauré)   Identique ?
  patients                ________________     ______________     oui / non
  test_orders (demandes)  ________________     ______________     oui / non
  reports (comptes rendus)________________     ______________     oui / non
  invoices (factures)     ________________     ______________     oui / non
  users                   ________________     ______________     oui / non
  fichiers (storage)      ________________     ______________     oui / non

VÉRIFICATIONS FONCTIONNELLES
  Connexion d'un administrateur            : OK / KO
  Affichage d'un cliché de lame            : OK / KO
  Ouverture d'un PDF de compte-rendu       : OK / KO
  Ouverture d'une facture                  : OK / KO
  Sauvegarde manuelle depuis le serveur    : OK / KO
  Alerte reçue à 20h (si test sur 24 h)    : OK / KO / non testé

ÉCARTS CONSTATÉS ET ACTIONS
  ______________________________________________________________________
  ______________________________________________________________________

CONCLUSION : restauration validée / non validée

Signature opérateur : ______________   Signature laboratoire : ______________
```
