-- Crédit prépayé SMS et appels (tenu par FluidChat, docs/CREDIT.md de fluidchat-saas).
-- La tuile du tableau de bord montre le solde et lance des recharges Mobile Money :
-- c'est engager de l'argent pour tout le laboratoire, d'où un droit à part, donné
-- aux seuls super-admin et admin. Aucune table : le solde vit chez FluidChat.

INSERT INTO permissions (id, name, slug, created_at, updated_at)
SELECT gen_random_uuid(), 'Gérer le crédit SMS et appels', 'manage-messaging-credit', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE slug = 'manage-messaging-credit');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.slug IN ('super-admin', 'admin')
  AND p.slug = 'manage-messaging-credit'
  AND NOT EXISTS (
      SELECT 1 FROM role_permissions deja
      WHERE deja.role_id = r.id AND deja.permission_id = p.id
  );

-- Garde-fou (voir V78) : une migration « verte » qui n'insère rien ne passe pas.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM permissions WHERE slug = 'manage-messaging-credit') THEN
        RAISE EXCEPTION 'La permission manage-messaging-credit est absente.';
    END IF;
    IF EXISTS (SELECT 1 FROM roles WHERE slug = 'super-admin') AND NOT EXISTS (
        SELECT 1 FROM role_permissions rp
        JOIN roles r ON r.id = rp.role_id
        JOIN permissions p ON p.id = rp.permission_id
        WHERE r.slug = 'super-admin' AND p.slug = 'manage-messaging-credit'
    ) THEN
        RAISE EXCEPTION 'Le rôle super-admin n''a pas reçu manage-messaging-credit.';
    END IF;
END $$;
