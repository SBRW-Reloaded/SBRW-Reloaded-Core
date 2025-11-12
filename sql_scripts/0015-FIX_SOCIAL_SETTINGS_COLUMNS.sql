-- Script de correction pour les colonnes déjà créées avec le mauvais type
-- Utilisez ce script si les colonnes ont déjà été créées avec le type BOOLEAN/BIT

-- Modifier les colonnes existantes pour utiliser TINYINT(1) au lieu de BIT
ALTER TABLE `USER` MODIFY COLUMN `appearOffline` TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE `USER` MODIFY COLUMN `declineIncommingFriendRequests` TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE `USER` MODIFY COLUMN `hideOfflineFriends` TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE `USER` MODIFY COLUMN `showNewsOnSignIn` TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE `USER` MODIFY COLUMN `showOnlyPlayersInSameChatChannel` TINYINT(1) NOT NULL DEFAULT 0;