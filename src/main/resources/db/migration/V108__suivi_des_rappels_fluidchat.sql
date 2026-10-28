-- Rappel automatique des patients par FluidChat : appel_by_reports devient
-- l'historique des rappels.
--
-- Une ligne par appel (jusqu'ici, la ligne d'un compte rendu était réécrite à
-- chaque appel). La tâche de suivi relit chez FluidChat les appels en cours et
-- remplit status / result / listened_pct / attempts ; « issue » est la lecture
-- métier qu'en font le suivi, le tableau de bord et le bandeau.
--
-- Les lignes OurVoice existantes gardent issue = NULL : ni comptées, ni suivies.
-- Idempotente : le schéma de prod vient du dump Laravel (baseline V50).

ALTER TABLE appel_by_reports ADD COLUMN IF NOT EXISTS status       VARCHAR(20);
ALTER TABLE appel_by_reports ADD COLUMN IF NOT EXISTS result       VARCHAR(20);
ALTER TABLE appel_by_reports ADD COLUMN IF NOT EXISTS listened_pct INTEGER;
ALTER TABLE appel_by_reports ADD COLUMN IF NOT EXISTS attempts     INTEGER;
ALTER TABLE appel_by_reports ADD COLUMN IF NOT EXISTS issue        VARCHAR(20);
ALTER TABLE appel_by_reports ADD COLUMN IF NOT EXISTS finished_at  TIMESTAMP;

-- Patient sans numéro : la ligne trace le rappel impossible, sans appel FluidChat.
ALTER TABLE appel_by_reports ALTER COLUMN appel_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_appel_by_reports_branche_date
    ON appel_by_reports (branch_id, created_at);
CREATE INDEX IF NOT EXISTS idx_appel_by_reports_en_cours
    ON appel_by_reports (created_at) WHERE issue = 'EN_COURS';

COMMENT ON COLUMN appel_by_reports.issue IS 'Issue du rappel automatique (IssueRappel) : EN_COURS, JOINT, PARTIEL, SANS_REPONSE, INJOIGNABLE, SANS_NUMERO, ANNULE. NULL = appel OurVoice d''avant FluidChat.';
COMMENT ON COLUMN appel_by_reports.appel_id IS 'Identifiant de l''appel chez FluidChat (OurVoice avant V108). NULL si aucun appel n''est parti (patient sans numéro).';
