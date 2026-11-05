-- Appels, bips et SMS que FluidChat n'a pas reçus (erreur réseau, 502, 503, 504),
-- renvoyés toutes les 10 minutes pendant la plage d'appel, trois jours au plus,
-- avec la même clé : si FluidChat avait reçu la demande, il rend l'existante au
-- lieu d'appeler le patient une seconde fois. Une ligne est supprimée dès que
-- l'envoi aboutit ; abandonnee_le la garde pour trace sinon.

CREATE TABLE IF NOT EXISTS demandes_fluidchat (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id       UUID         NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    cible_id        UUID         NOT NULL,
    cle             VARCHAR(64)  NOT NULL,
    tentatives      INTEGER      NOT NULL DEFAULT 0,
    derniere_erreur VARCHAR(255),
    abandonnee_le   TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID,
    deleted_at      TIMESTAMP
);

-- Une seule demande en attente par envoi voulu : c'est elle qui porte la clé.
CREATE UNIQUE INDEX IF NOT EXISTS uq_demandes_fluidchat_en_attente
    ON demandes_fluidchat (type, cible_id) WHERE abandonnee_le IS NULL;
