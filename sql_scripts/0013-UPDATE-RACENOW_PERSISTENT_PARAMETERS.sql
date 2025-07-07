-- Script SQL pour mettre à jour les paramètres RaceNow existants
-- Exécuter ce script si vous avez déjà installé le système RaceNow persistant

-- Mettre à jour l'intervalle de surveillance pour être plus réactif (10s -> 5s)
UPDATE PARAMETER SET VALUE = '5' WHERE NAME = 'SBRWR_RACENOW_MONITOR_INTERVAL';

-- Vérifier que tous les paramètres sont présents
INSERT IGNORE INTO PARAMETER (NAME, VALUE) VALUES ('SBRWR_RACENOW_MONITOR_INTERVAL', '5');
INSERT IGNORE INTO PARAMETER (NAME, VALUE) VALUES ('SBRWR_RACENOW_MAX_WAIT_MINUTES', '30');
INSERT IGNORE INTO PARAMETER (NAME, VALUE) VALUES ('SBRWR_RACENOW_PERSISTENT_ENABLED', 'true');
INSERT IGNORE INTO PARAMETER (NAME, VALUE) VALUES ('SBRWR_RACENOW_MAX_INVITES_PER_CYCLE', '3');
INSERT IGNORE INTO PARAMETER (NAME, VALUE) VALUES ('SBRWR_RACENOW_MIN_INVITE_DELAY', '30');

-- Afficher les paramètres actuels
SELECT 'Paramètres RaceNow configurés:' as Info;
SELECT NAME, VALUE FROM PARAMETER WHERE NAME LIKE 'SBRWR_RACENOW%' ORDER BY NAME;
