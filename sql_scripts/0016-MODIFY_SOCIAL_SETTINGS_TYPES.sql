-- Script pour modifier les types de colonnes des paramètres sociaux dans la table USER
-- Ce script modifie les colonnes existantes pour utiliser les bons types de données
-- Compatible avec MySQL/MariaDB

-- Modifier les colonnes booléennes de BIT/BOOLEAN vers TINYINT(1)
ALTER TABLE `USER` MODIFY COLUMN `appearOffline` TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE `USER` MODIFY COLUMN `declineIncommingFriendRequests` TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE `USER` MODIFY COLUMN `hideOfflineFriends` TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE `USER` MODIFY COLUMN `showNewsOnSignIn` TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE `USER` MODIFY COLUMN `showOnlyPlayersInSameChatChannel` TINYINT(1) NOT NULL DEFAULT 0;

-- Vérifier que les colonnes INT sont bien définies (normalement déjà correctes)
ALTER TABLE `USER` MODIFY COLUMN `declineGroupInvite` INT NOT NULL DEFAULT 0;
ALTER TABLE `USER` MODIFY COLUMN `declinePrivateInvite` INT NOT NULL DEFAULT 0;