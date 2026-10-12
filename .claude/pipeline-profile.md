# Profil pipeline — LabSys API (labsys_api)

Lu par `kawa-feature-pipeline` avant l'étape 0. Ne contient que ce qui est propre à ce projet ; tout le reste
est dans le pipeline commun. Pas de `CLAUDE.md` dans le dépôt : ce profil est déduit du code
(`origin/develop` au 2026-10-11, dépôt `serviceskawa/backend_api_labocap`).

## Contexte et risques majeurs
API REST du laboratoire d'anatomopathologie (et de biologie, module optionnel) : Spring Boot 4.1.1 (Spring 7,
Security 7, Hibernate 7, Jackson 3), Java 21, PostgreSQL, Flyway, JWT (jjwt), MapStruct, Lombok, PDF
openhtmltopdf + Thymeleaf, Firebase (push), S3 (`pom.xml`). Paquets par domaine sous
`src/main/java/com/labo/anapath/` (`patient`, `testorder`, `report`, `finance`, `hr`, `mobile`, `biology`…),
transverse dans `common/`. Reprise d'une application Laravel (`docs/reprise-laravel.md`,
`docs/ecarts-schema-laravel.md` ; ancien code : `../labo-anapath`).

Le plus coûteux :
- **Données de santé** : dossiers patients, demandes d'examen, comptes rendus, images d'examen, factures. Les
  lectures sont tracées (`common/audit/JournalAccesInterceptor`, « lot 6 »), les fichiers chiffrés au repos
  (`common/storage/FileCipher`, `STORAGE_ENCRYPTION_ENABLED`), l'accès aux fichiers suit la permission de
  l'entité propriétaire et l'agence (`common/storage/AccesAuxFichiers`).
- **Cloisonnement par agence** : en-tête `X-Branch-Id` revalidé contre `branch_user`
  (`common/branch/BranchContextFilter`) ; l'isolation repose sur `principal.getBranchId()` et `BranchContext`,
  **pas** sur un filtre Hibernate (OSIV désactivé). Une requête qui l'oublie fuit entre agences.
- **Migrations Flyway non testées** : la suite et le job `demarrage` tournent Flyway éteint, schéma créé par
  Hibernate (`pom.xml` surefire, `scripts/demarrage-ci.sh`). En prod `ddl-auto: validate` sur une base reprise
  du dump Laravel (baseline V50). Précédents : numéros en double (V100, 03/10, `fix/numeros-de-migration`),
  migrations de permissions « vertes » qui n'inséraient rien (`V78__creer_la_permission_des_affectations.sql`),
  API qui ne démarre plus après passage à Boot 4 (`ModulesDeBootPresentsTest`).
- **Argent** : caisse, factures, remboursements, paie (`finance`, `hr`).

## Déclencheurs propres
Nouvelle route ou nouveau domaine sous `/api/v1/`, nouvelle entité / migration, nouvelle permission, toute
route de lecture d'une donnée patient, route publique (`permitAll`), flux mobile (`/api/v1/mobile`), envoi
SMS / courriel / push, nouvelle dépendance ou nouveau starter Spring Boot.

## Mots-clés de leçons
Domaine (`report`, `test-order`, `patient`, `invoice`, `cashbox`, `biology`, `mobile`), mécanique
(`migration`, `flyway`, `permission`, `branch`, `affectation`, `boot4`).

## Inventaire (étape 0.5)
- Contrôleurs : `@RequestMapping("/api/v1/…")` dans chaque paquet de domaine
  (`grep -rh '@RequestMapping(' src/main/java`) ; routes publiques et règles globales dans
  `common/security/SecurityConfig.java`.
- Transverse avant d'écrire : `common/storage` (fichiers, S3/disque, chiffrement), `common/notification`
  (SMS FluidPay, `SmsTemplates`, `PhoneNumbers`), `common/email` + `templates/email/`, `common/pdf` +
  `templates/pdf/`, `common/scheduling` (tâches planifiées, sauvegardes), `common/audit`,
  `common/supervision`, `common/module` (module biologie), `common/exception` (`GlobalExceptionHandler`).
- Permissions existantes : `hasAuthority('<slug>')` dans les contrôleurs, slugs listés dans
  `src/test/resources/seed-test.sql` et créés par les migrations `db/migration/`.
- Schéma : `src/main/resources/db/migration/` (prod), `db/local/` (base vierge, profil `local`).
- Historique : `docs/reprise-laravel.md` (défauts trouvés après bascule) ; comportement d'origine dans
  `../labo-anapath` (Laravel) quand la demande dit « comme avant ».
- Consommateur : `../labsys_front/src/lib/api/*.ts` (un fichier par domaine).

## Contrat d'API
Pas de contrat maintenu à la main qui fasse foi : `openapi.yaml` à la racine n'a pas bougé depuis le premier
commit (2026-05-18) et est périmé. Le contrat, c'est le code sur `develop` (contrôleurs + DTO) ; SpringDoc le
génère en dev (`/v3/api-docs`, désactivé en prod par `application-prod.yml`).
Consommé par `labsys_front` (`src/lib/api/client.ts` : base `/api/v1`, cookies `withCredentials`, en-tête
`X-Branch-Id`, déballage de l'enveloppe `ApiResponse` `{success, data}` ; types écrits à la main dans
`src/types/api.ts` et `src/lib/api/*.ts`). Les routes `/api/v1/mobile/**` servent une application mobile dont
le dépôt n'est pas dans l'espace de travail. Un champ renommé ou retiré casse le front sans test qui le
signale : le traiter comme un changement de contrat.

## Wireframe (étape 1)
Pas d'écran ici : les écrans sont dans `labsys_front` (son propre pipeline). Surfaces rendues par l'API :
PDF (`templates/pdf/`, polices et signatures dans `pdf-assets/`) et courriels (`templates/email/`) — un
changement de leur contenu se montre avant d'être codé.

## Architecte (étape 2)
Pas d'agent architecte dans le dépôt (`.claude/` absent). Étape sautée quand le changement reste dans un
paquet de domaine existant et suit son patron (Controller → Service/ServiceImpl → Repository, DTO + Mapper
MapStruct). Agent `Plan` pour un changement structurant : nouveau domaine, nouveau module activable
(`app.modules.*`, `common/module`), nouveau canal de notification, changement du cloisonnement par agence,
de l'authentification ou du stockage.

## Checklist (étape 3)
Rôles (slugs des migrations) : `super-admin`, `admin`, `docteur`, `laborantin`, `secretariat` ; agences
(`branches`). Les droits passent par des permissions (`view-…`, `edit-…`, `manage-…`), pas par le rôle.
Garde-fous : `@PreAuthorize("hasAuthority('…')")` sur chaque route, cloisonnement agence, journal des accès
pour toute lecture patient, validation Bean Validation des DTO, enveloppe `ApiResponse`.
Obligations : migration Flyway pour tout changement de schéma ; variable d'environnement nouvelle reportée
dans `.env.example`, `.env.prod.example` et, si elle vient de GitHub, `deploy.yml`.

## Implémentation (étape 4)
- JUnit 5 + Spring Boot Test. Deux styles : `*Test.java` unitaires (Mockito) et `*IT.java` /
  `*IntegrationTest.java` d'intégration (`@SpringBootTest` RANDOM_PORT + `TestRestTemplate`, PostgreSQL par
  Testcontainers `postgres:15`, un conteneur par classe). Exemple : `auth/AuthIntegrationTest.java`. Aides :
  `src/test/java/com/labo/anapath/testsupport/` (`Jetons`, `EnTeteDAgence`, `CourrielsDeTest`).
- Schéma de test créé par Hibernate + `schema-test.sql` / `seed-test.sql` (variables dans `pom.xml`
  surefire) : une permission nouvelle s'ajoute aussi à `seed-test.sql`.
- Code, commentaires et noms de tests souvent en français ; Javadoc qui explique le « pourquoi ».

| Le changement touche… | À vérifier |
|---|---|
| Une entité / une table | Migration `db/migration/V<n>__<sujet>.sql`, numéro **unique** (le CI refuse les doublons — vérifier aussi contre les PR ouvertes). Écrite contre le schéma de prod (repris de Laravel), jamais testée par la suite : la relire comme du code de prod. `scripts/regen-local-baseline.sh` pour le profil `local`. |
| Une permission | Créée par migration **et** vérifiée (un `INSERT … SELECT` sur un slug absent n'insère rien, cf. V78) ; ajoutée à `seed-test.sql` ; même slug côté `labsys_front/src/lib/constants/permissions`. |
| Une requête de lecture/écriture | Filtre par agence (`principal.getBranchId()` / `BranchContext`) ; un test d'intégration qui prouve qu'une autre agence ne voit pas la donnée (sans `X-Branch-Id`, le serveur répond 428 ; `EnTeteDAgence` le pose dans les tests). |
| Une route qui lit une donnée patient | Chemin ajouté à `JournalAccesInterceptor.CHEMINS` (la route mobile est consignée d'office par `mobile/JournalMobileFilter`). |
| Un fichier rattaché | Type et permission dans `AccesAuxFichiers.PERMISSION_PAR_TYPE` ; stockage via `FileStorageService` (chiffrement au repos). |
| Une route publique | `permitAll` explicite dans `SecurityConfig` ; limitation (`RateLimitFilter`, bucket4j). |
| Un secret stocké en base | `@Convert(converter = SecretChiffreConverter.class)` (cf. `user/User.java`), clé `SECRETS_KEY`. |
| Le module biologie | Chemins dans `ModulesWebConfig.BIOLOGY_PATHS` (coupés si `MODULE_BIOLOGY=false`). |
| Une dépendance / un starter Spring Boot | `ModulesDeBootPresentsTest` (liste des auto-configurations requises) ; portée Maven correcte (précédent httpclient5 en `test`) ; le job `demarrage` le confirme. |
| Une variable d'environnement | `application*.yml`, `.env.example`, `.env.prod.example`, `deploy.yml` (`ajouter_si_absent` / `remplacer`). |
| Une réponse ou un DTO exposé | Champ renommé ou retiré = changement de contrat (§ Contrat d'API) ; story côté `labsys_front`. |

Fin d'étape : la suite Maven passe. Pas de nouvelle bibliothèque de test sans discussion.

## Revue (étape 5)
`security-review` + `security-and-hardening` : toute donnée patient (dossier, demande, compte rendu, image,
facture), authentification (JWT, 2FA TOTP, verrouillage de compte V104, réinitialisation), permissions et
cloisonnement par agence, routes publiques (`/public/invoices`, branding, enrôlement mobile), fichiers, argent
(caisse, factures, paie), SMS / push / courriel, secrets.
Vérification locale (JDK 21, Maven et un démon Docker pour Testcontainers ; pas de wrapper `mvnw`) :
```bash
ls src/main/resources/db/migration | sed -n 's/^V\([0-9.]*\)__.*/\1/p' | sort | uniq -d   # doit être vide
mvn -B --no-transfer-progress verify                     # suite complète, ~20 min (ci.yml)
mvn -B -Dtest='PatientIntegrationTest' test             # une classe
docker build -t labo-anapath-api:ci . && scripts/demarrage-ci.sh labo-anapath-api:ci   # image de prod démarre
```
CI (`.github/workflows/ci.yml`, sur PR vers `develop` et `main`) : numéros de migration uniques,
`mvn verify`, démarrage de l'image de prod, `dependency-check` OWASP (CVSS ≥ 7 bloque, si `NVD_API_KEY`).
`protect-branches.yml` : préfixes de branche `feat|fix|chore|hotfix|docs|refactor|dependabot/`.

## Suivi
— (pas de phases `stories/` pour l'instant ; les chantiers de sécurité sont numérotés « lot N » dans les
commentaires du code)

## Ship (étape 7)
Flux commun ; `develop` existe sur origin. Dependabot cible `develop` (`.github/dependabot.yml`). Branches
nommées en français (`feat/versions-comptes-rendus`), commits `type(portée): sujet` en français.
Le merge sur `main` déploie la production (`deploy.yml` : tests, puis SSH, `git reset --hard origin/main`,
`docker compose up -d --build --wait api`, retour à l'image `previous` si non sain) ; seule `develop` (ou un
`hotfix/`) peut viser `main`. Aucun serveur de dev n'est décrit dans le dépôt : livrée = mergée sur
`develop`, checks verts.

## Hors pipeline
Déploiement production et redéploiement manuel (`workflow_dispatch`) ; reprise de données Laravel ;
restauration (`docs/restauration.md`) ; supervision serveur (`docs/supervision.md`,
`docker-compose.supervision.yml`, `infra/`).
