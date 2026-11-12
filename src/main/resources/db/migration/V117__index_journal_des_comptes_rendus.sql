-- Le tableau « Suivi des demandes » compte, pour chaque ligne, les SMS de
-- résultat consignés au journal du compte rendu. La base reprise de Laravel n'a
-- pas l'index que V1 créait : sans lui, chaque ligne parcourt tout le journal.
CREATE INDEX IF NOT EXISTS idx_log_reports_report_id ON log_reports (report_id);
