-- Ajouter une colonne carRestriction à la table EVENT
-- Cette colonne stocke les noms de voitures autorisées pour l'événement
-- Format: "car1002,sf90" (noms séparés par des virgules)
-- Correspond aux valeurs "name" de la table CAR

ALTER TABLE EVENT ADD COLUMN carRestriction VARCHAR(500) NULL;

-- Commentaire pour clarifier l'usage
COMMENT ON COLUMN EVENT.carRestriction IS 'Liste des noms de voitures autorisées pour cet événement, séparés par des virgules. Correspond aux valeurs name de la table CAR. NULL = aucune restriction.';