-- Fixe explicitement l'intervalle entre deux tentatives d'appel de rappel à 20
-- minutes, au lieu de ne compter que sur le défaut codé dans ReglagesDeRappel.
--
-- Contexte : suite au bug de prod CAAP du 28/10/2026 (jusqu'à 39 appels/jour
-- pour un même patient, corrigé en V110 — rollback de transaction, pas un souci
-- de réglage), on rend ce réglage visible et modifiable depuis l'écran
-- Paramètres plutôt que de le laisser implicite.
--
-- On ne crée la clé que pour les succursales qui ne l'ont pas déjà réglée :
-- une valeur déjà choisie par un laboratoire n'est jamais écrasée.

INSERT INTO setting_apps (id, branch_id, key, value, label, created_at, updated_at)
SELECT gen_random_uuid(), b.id, 'rappel_intervalle_minutes', '20',
       'Rappel des patients — minutes entre deux tentatives d''appel quand le patient n''a pas répondu',
       NOW(), NOW()
FROM branches b
WHERE NOT EXISTS (
    SELECT 1 FROM setting_apps s
    WHERE s.key = 'rappel_intervalle_minutes'
      AND s.branch_id = b.id
      AND s.deleted_at IS NULL
);
