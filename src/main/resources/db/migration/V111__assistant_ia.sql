-- Assistant IA (lot 1) : réglages, conversations, messages, trace des outils,
-- et les deux permissions qui l'ouvrent.
--
-- LES CONVERSATIONS PORTENT DES DONNÉES DE SANTÉ EN CLAIR
-- `assistant_messages.contenu` est ce que l'écran relit ; `envoye` est la forme
-- masquée partie chez le fournisseur, figée (le fournisseur exige que
-- l'historique déjà envoyé ne change pas). Les deux sont purgés avec leur
-- conversation après 90 jours (`PurgeDesConversations`). La trace des appels
-- d'outils, elle, reste : elle dit ce que l'assistant a lu ou fait, au nom de qui.
--
-- UNE SEULE LIGNE DE RÉGLAGES
-- Un laboratoire = un déploiement : la clé et le quota valent pour toutes ses
-- agences. La ligne n'est pas créée ici — l'assistant est éteint tant qu'un
-- administrateur ne l'a pas réglé, et l'application crée la ligne au premier
-- enregistrement. La clé est chiffrée par l'application (SecretChiffreConverter).

CREATE TABLE assistant_reglages (
    id                    SMALLINT PRIMARY KEY CHECK (id = 1),
    actif                 BOOLEAN NOT NULL DEFAULT FALSE,
    fournisseur           VARCHAR(20) NOT NULL DEFAULT 'ANTHROPIC'
                          CHECK (fournisseur IN ('ANTHROPIC', 'OPENAI')),
    modele                VARCHAR(100) NOT NULL,
    cle_api               TEXT NULL,
    caviardage            BOOLEAN NOT NULL DEFAULT TRUE,
    quota_mensuel_jetons  BIGINT NULL CHECK (quota_mensuel_jetons IS NULL OR quota_mensuel_jetons >= 0),
    updated_at            TIMESTAMP NULL,
    updated_by            UUID NULL
);

CREATE TABLE assistant_conversations (
    id                   UUID PRIMARY KEY,
    branch_id            UUID NOT NULL REFERENCES branches(id),
    user_id              UUID NOT NULL REFERENCES users(id),
    titre                VARCHAR(200) NULL,
    fournisseur          VARCHAR(20) NOT NULL,
    appel_en_attente_id  VARCHAR(100) NULL,
    created_at           TIMESTAMP NOT NULL,
    updated_at           TIMESTAMP NOT NULL,
    created_by           UUID NULL,
    updated_by           UUID NULL,
    deleted_at           TIMESTAMP NULL
);
CREATE INDEX idx_assistant_conversations_auteur
    ON assistant_conversations (user_id, branch_id, updated_at DESC);
CREATE INDEX idx_assistant_conversations_purge
    ON assistant_conversations (updated_at);

CREATE TABLE assistant_messages (
    id               UUID PRIMARY KEY,
    conversation_id  UUID NOT NULL REFERENCES assistant_conversations(id) ON DELETE CASCADE,
    seq              INT NOT NULL,
    role             VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant', 'tool')),
    contenu          JSONB NOT NULL,
    -- TEXT, pas JSONB : JSONB réordonne les clés, et ce qui est reparti doit
    -- rester identique octet pour octet.
    envoye           TEXT NOT NULL,
    jetons_entree    INT NOT NULL DEFAULT 0,
    jetons_sortie    INT NOT NULL DEFAULT 0,
    -- Le 👍 / 👎 de l'utilisateur sur une réponse ; le commentaire est masqué
    -- comme ce qui part chez le fournisseur (il nourrit le rapport).
    avis             SMALLINT NULL CHECK (avis IN (-1, 1)),
    avis_commentaire VARCHAR(500) NULL,
    created_at       TIMESTAMP NOT NULL,
    CONSTRAINT uq_assistant_messages_seq UNIQUE (conversation_id, seq)
);
-- Le quota se calcule en sommant les jetons du mois.
CREATE INDEX idx_assistant_messages_date ON assistant_messages (created_at);

CREATE TABLE assistant_appels_outils (
    id               UUID PRIMARY KEY,
    -- Pas de clé étrangère : la trace survit à la purge de la conversation.
    conversation_id  UUID NULL,
    user_id          UUID NOT NULL,
    branch_id        UUID NOT NULL,
    outil            VARCHAR(100) NOT NULL,
    categorie        VARCHAR(20) NOT NULL,
    arguments        JSONB NULL,
    statut           VARCHAR(20) NOT NULL,
    erreur           TEXT NULL,
    duree_ms         INT NOT NULL DEFAULT 0,
    created_at       TIMESTAMP NOT NULL
);
CREATE INDEX idx_assistant_appels_agence_date ON assistant_appels_outils (branch_id, created_at);

-- ---------------------------------------------------------------------------
-- Boucle d'amélioration
--   assistant_besoins  : ce que l'assistant n'a pas su faire, signalé par lui
--                        (outil signaler_besoin), reformulé et masqué.
--   assistant_rapports : le rapport hebdomadaire (statistiques + synthèse).
-- Purgés après 90 jours, comme les conversations.
-- ---------------------------------------------------------------------------
CREATE TABLE assistant_besoins (
    id          UUID PRIMARY KEY,
    branch_id   UUID NOT NULL,
    user_id     UUID NOT NULL,
    demande     VARCHAR(500) NOT NULL,
    raison      VARCHAR(30) NOT NULL
                CHECK (raison IN ('outil_manquant', 'donnee_absente', 'droit_manquant', 'autre')),
    created_at  TIMESTAMP NOT NULL
);
CREATE INDEX idx_assistant_besoins_agence_date ON assistant_besoins (branch_id, created_at);

CREATE TABLE assistant_rapports (
    id            UUID PRIMARY KEY,
    branch_id     UUID NOT NULL,
    debut         DATE NOT NULL,
    fin           DATE NOT NULL,
    statistiques  JSONB NOT NULL,
    synthese      TEXT NULL,
    jetons        INT NOT NULL DEFAULT 0,
    created_by    UUID NULL,
    created_at    TIMESTAMP NOT NULL
);
CREATE INDEX idx_assistant_rapports_agence_date ON assistant_rapports (branch_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Permissions
--   view-assistant   : ouvrir l'assistant et relire ses propres conversations.
--                      Docteur, admin, super-admin. L'accorder plus largement ne
--                      donne accès à rien de plus — chaque outil revérifie le
--                      droit de la route équivalente — mais chaque échange coûte.
--   manage-assistant : fournisseur, modèle, clé, masquage, quota. Admin, super-admin.
-- ---------------------------------------------------------------------------
INSERT INTO permissions (id, name, slug, created_at, updated_at)
SELECT gen_random_uuid(), v.name, v.slug, NOW(), NOW()
FROM (VALUES
        ('Utiliser l''assistant IA',  'view-assistant'),
        ('Configurer l''assistant IA', 'manage-assistant')
     ) AS v(name, slug)
WHERE NOT EXISTS (SELECT 1 FROM permissions p WHERE p.slug = v.slug);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON (
        (p.slug = 'view-assistant'   AND r.slug IN ('super-admin', 'admin', 'docteur'))
     OR (p.slug = 'manage-assistant' AND r.slug IN ('super-admin', 'admin')))
WHERE NOT EXISTS (
    SELECT 1 FROM role_permissions deja
    WHERE deja.role_id = r.id AND deja.permission_id = p.id
);

-- Garde-fou (leçon V78) : un droit absent ou accordé à personne est une
-- migration qui a échoué en silence.
DO $$
DECLARE
    s TEXT;
BEGIN
    FOREACH s IN ARRAY ARRAY['view-assistant', 'manage-assistant'] LOOP
        IF NOT EXISTS (SELECT 1 FROM permissions WHERE slug = s) THEN
            RAISE EXCEPTION 'La permission % est absente.', s;
        END IF;
        IF EXISTS (SELECT 1 FROM roles WHERE slug = 'super-admin') AND NOT EXISTS (
            SELECT 1
            FROM role_permissions rp
            JOIN roles r ON r.id = rp.role_id
            JOIN permissions p ON p.id = rp.permission_id
            WHERE r.slug = 'super-admin' AND p.slug = s
        ) THEN
            RAISE EXCEPTION 'Le rôle super-admin n''a pas reçu %.', s;
        END IF;
    END LOOP;
END $$;
