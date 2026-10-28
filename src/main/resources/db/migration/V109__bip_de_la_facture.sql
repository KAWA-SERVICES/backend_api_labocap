-- Bip FluidChat à la facture : le patient voit un appel manqué du numéro qui
-- l'appellera au résultat, et peut l'enregistrer pour ne pas rejeter l'appel
-- automatique. Un bip par facture : la date arrête le second événement
-- (encaissement puis normalisation). Idempotente.

ALTER TABLE invoices ADD COLUMN IF NOT EXISTS bip_sent_at TIMESTAMP;

COMMENT ON COLUMN invoices.bip_sent_at IS
    'Date du bip FluidChat envoyé au patient pour cette facture ; NULL tant qu''aucun bip n''est parti.';
