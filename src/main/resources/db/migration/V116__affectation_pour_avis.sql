-- Affectation « pour avis » : un second médecin reçoit le dossier sans que le
-- premier le perde. La ligne d'avis reste courante à côté de celle du
-- titulaire ; les lignes existantes sont toutes des affectations ordinaires.
ALTER TABLE test_order_assignment_details
    ADD COLUMN IF NOT EXISTS pour_avis BOOLEAN NOT NULL DEFAULT FALSE;
