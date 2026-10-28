-- Journal d'un compte rendu sans auteur : le rappel automatique des patients
-- (RattrapageAvisPatientJob) agit sans utilisateur. Avec user_id NOT NULL, sa
-- ligne de journal faisait annuler toute la transaction après le départ de
-- l'appel FluidChat : ni l'appel ni « patient prévenu » n'étaient enregistrés,
-- et le patient était rappelé tous les quarts d'heure. Idempotente.

ALTER TABLE log_reports ALTER COLUMN user_id DROP NOT NULL;

COMMENT ON COLUMN log_reports.user_id IS
    'Auteur de l''action ; NULL pour une action automatique (rappel planifié des patients).';
