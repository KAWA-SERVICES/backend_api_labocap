# ─── Stage 1 : build ─────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /build

# Téléchargement des dépendances en cache séparé
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Compilation
COPY src/ src/
RUN mvn package -Dmaven.test.skip=true -B

# ─── Stage 2 : runtime ───────────────────────────────────────────────────────
# Tag précis, mis à jour par Dependabot (lot 4) : « 21-jre-alpine » changeait sous nos pieds.
FROM eclipse-temurin:24.0.2_12-jre-alpine

# Client PostgreSQL — la tâche de sauvegarde quotidienne appelle `pg_dump`.
# Sans lui, elle échouait chaque soir depuis la mise en conteneur : la commande
# par défaut passait par `docker exec`, or ni le client Docker ni sa socket
# n'existent ici. Aucune sauvegarde n'a jamais été produite.
#
# Version 16, celle du serveur : `pg_dump` refuse de sauvegarder une base dont
# la version majeure dépasse la sienne. À faire suivre si le serveur monte.
#
# `age` chiffre chaque export à la volée (pg_dump | age -r <clé publique>) :
# aucun SQL en clair ne touche le disque, et la clé privée n'est pas ici.
RUN apk add --no-cache age postgresql16-client

# Utilisateur non-root pour la sécurité
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=build /build/target/app.jar app.jar

RUN chown appuser:appgroup app.jar

# Racine de stockage des fichiers téléversés (images d'examen, documents, photos
# d'employés, justificatifs…). Elle DOIT exister dans l'image et appartenir à
# appuser AVANT que le volume ne soit monté dessus : quand Docker crée un volume
# nommé sur un chemin absent de l'image, il crée le point de montage en
# root:root 0755. Le conteneur tournant en non-root, toute écriture échouait
# alors avec « Permission denied » et chaque upload partait en erreur, alors
# qu'en local (processus lancé sous le compte du développeur) tout marchait.
# Docker recopie les droits du dossier de l'image dans le volume neuf.
ENV STORAGE_PATH=/var/lib/labo/storage
# Répertoire des sauvegardes, créé ici pour la même raison que le stockage —
# un volume monté sur un chemin absent de l'image appartient à root, et le
# conteneur tourne en non-root. Il vivait auparavant dans /app, hors de tout
# volume : chaque redéploiement l'emportait.
ENV APP_BACKUP_DIR=/var/lib/labo/backups
# Le journal applicatif aussi (LOG_FILE sous /var/lib/labo/logs, volume `logs`).
RUN mkdir -p "$STORAGE_PATH" "$APP_BACKUP_DIR" /var/lib/labo/logs && chown -R appuser:appgroup /var/lib/labo

USER appuser

# Port d'écoute dans le conteneur. Doit rester identique à PORT ci-dessous :
# l'application lit server.port=${PORT}, et actuator n'a pas de port distinct,
# donc le healthcheck du compose interroge ce même port.
EXPOSE 7001

# Variables d'environnement attendues (à fournir au runtime)
ENV DB_URL="" \
    DB_USERNAME="" \
    DB_PASSWORD="" \
    JWT_SECRET="" \
    PORT="7001" \
    SPRING_PROFILES_ACTIVE="prod"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
