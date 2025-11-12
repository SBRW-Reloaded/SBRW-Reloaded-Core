-- Script SQL pour ajouter la colonne LOCKED_CAR_CLASS_HASH à la table LOBBY
-- Cette colonne permet de verrouiller un lobby à une classe de voiture spécifique

-- Ajouter la colonne à la table LOBBY
ALTER TABLE LOBBY ADD COLUMN LOCKED_CAR_CLASS_HASH INT NULL;

-- Créer un index pour améliorer les performances de recherche
CREATE INDEX IDX_LOBBY_LOCKED_CAR_CLASS ON LOBBY(LOCKED_CAR_CLASS_HASH);

-- Ajouter le paramètre pour activer/désactiver le verrouillage de classe (activé par défaut)
INSERT IGNORE INTO PARAMETER (NAME, VALUE) VALUES ('SBRWR_LOCK_LOBBY_CAR_CLASS', 'true');

-- Vérifier les paramètres
SELECT 'Configuration du verrouillage de classe de lobby:' as Info;
SELECT NAME, VALUE FROM PARAMETER WHERE NAME = 'SBRWR_LOCK_LOBBY_CAR_CLASS';

-- Afficher la structure de la table LOBBY
SELECT 'Structure de la table LOBBY mise à jour:' as Info;
SHOW COLUMNS FROM LOBBY LIKE 'LOCKED_CAR_CLASS_HASH';
