-- Script de correction pour initialiser les valeurs NULL des social settings existantes
-- Exécutez ce script sur votre base de données si les valeurs sont remises à 0

-- Première étape: Vérifier les valeurs actuelles
SELECT 
    COUNT(*) as total_users,
    COUNT(CASE WHEN appearOffline IS NULL THEN 1 END) as null_appearOffline,
    COUNT(CASE WHEN declineGroupInvite IS NULL THEN 1 END) as null_declineGroupInvite,
    COUNT(CASE WHEN declineIncommingFriendRequests IS NULL THEN 1 END) as null_declineIncommingFriendRequests,
    COUNT(CASE WHEN declinePrivateInvite IS NULL THEN 1 END) as null_declinePrivateInvite,
    COUNT(CASE WHEN hideOfflineFriends IS NULL THEN 1 END) as null_hideOfflineFriends,
    COUNT(CASE WHEN showNewsOnSignIn IS NULL THEN 1 END) as null_showNewsOnSignIn,
    COUNT(CASE WHEN showOnlyPlayersInSameChatChannel IS NULL THEN 1 END) as null_showOnlyPlayersInSameChatChannel
FROM USER;

-- Deuxième étape: Mettre à jour les valeurs NULL avec les defaults
UPDATE USER SET appearOffline = 0 WHERE appearOffline IS NULL;
UPDATE USER SET declineGroupInvite = 0 WHERE declineGroupInvite IS NULL;
UPDATE USER SET declineIncommingFriendRequests = 0 WHERE declineIncommingFriendRequests IS NULL;
UPDATE USER SET declinePrivateInvite = 0 WHERE declinePrivateInvite IS NULL;
UPDATE USER SET hideOfflineFriends = 0 WHERE hideOfflineFriends IS NULL;
UPDATE USER SET showNewsOnSignIn = 0 WHERE showNewsOnSignIn IS NULL;
UPDATE USER SET showOnlyPlayersInSameChatChannel = 0 WHERE showOnlyPlayersInSameChatChannel IS NULL;

-- Troisième étape: Vérifier après correction
SELECT 
    COUNT(*) as total_users,
    COUNT(CASE WHEN appearOffline IS NULL THEN 1 END) as null_appearOffline,
    COUNT(CASE WHEN declineGroupInvite IS NULL THEN 1 END) as null_declineGroupInvite,
    COUNT(CASE WHEN declineIncommingFriendRequests IS NULL THEN 1 END) as null_declineIncommingFriendRequests,
    COUNT(CASE WHEN declinePrivateInvite IS NULL THEN 1 END) as null_declinePrivateInvite,
    COUNT(CASE WHEN hideOfflineFriends IS NULL THEN 1 END) as null_hideOfflineFriends,
    COUNT(CASE WHEN showNewsOnSignIn IS NULL THEN 1 END) as null_showNewsOnSignIn,
    COUNT(CASE WHEN showOnlyPlayersInSameChatChannel IS NULL THEN 1 END) as null_showOnlyPlayersInSameChatChannel
FROM USER;

-- Quatrième étape: Forcer les colonnes à NOT NULL avec DEFAULT (optionnel, seulement si nécessaire)
-- ALTER TABLE USER MODIFY COLUMN appearOffline TINYINT(1) NOT NULL DEFAULT 0;
-- ALTER TABLE USER MODIFY COLUMN declineGroupInvite INT NOT NULL DEFAULT 0;
-- ALTER TABLE USER MODIFY COLUMN declineIncommingFriendRequests TINYINT(1) NOT NULL DEFAULT 0;
-- ALTER TABLE USER MODIFY COLUMN declinePrivateInvite INT NOT NULL DEFAULT 0;
-- ALTER TABLE USER MODIFY COLUMN hideOfflineFriends TINYINT(1) NOT NULL DEFAULT 0;
-- ALTER TABLE USER MODIFY COLUMN showNewsOnSignIn TINYINT(1) NOT NULL DEFAULT 0;
-- ALTER TABLE USER MODIFY COLUMN showOnlyPlayersInSameChatChannel TINYINT(1) NOT NULL DEFAULT 0;