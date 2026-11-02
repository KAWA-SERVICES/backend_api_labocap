-- Dictée vocale des comptes rendus : une ligne par segment transcrit.
--
-- La transcription est facturée à la seconde d'audio par le fournisseur
-- (ElevenLabs). Ce compteur sert le quota mensuel par agence — vérifié avant
-- chaque envoi — et la facturation. L'audio et le texte ne sont PAS conservés :
-- seule la durée l'est.

CREATE TABLE consommation_dictee (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id   UUID NOT NULL REFERENCES branches(id),
    user_id     UUID NULL REFERENCES users(id),
    -- Durée d'audio transcrite, telle que le fournisseur la renvoie.
    secondes    NUMERIC(10, 2) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Le quota somme le mois en cours d'une agence.
CREATE INDEX idx_consommation_dictee_agence ON consommation_dictee(branch_id, created_at);
