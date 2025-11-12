-- Script SQL pour ajouter le paramètre de création automatique de lobby RaceNow
-- Ce paramètre définit le délai en secondes avant qu'un lobby soit créé automatiquement
-- si aucun lobby n'est trouvé pour un joueur en attente RaceNow

-- Ajouter le paramètre pour le délai avant création automatique (30 secondes par défaut)
INSERT IGNORE INTO PARAMETER (NAME, VALUE) VALUES ('SBRWR_RACENOW_AUTO_CREATE_DELAY', '30');

-- Vérifier les paramètres RaceNow
SELECT 'Paramètres RaceNow configurés:' as Info;
SELECT NAME, VALUE FROM PARAMETER WHERE NAME LIKE 'SBRWR_RACENOW%' ORDER BY NAME;
