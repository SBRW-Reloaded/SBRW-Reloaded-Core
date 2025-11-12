-- Add social settings columns to USER table
-- These columns store the user's social preferences and settings

ALTER TABLE `USER` ADD `appearOffline` TINYINT(1) NOT NULL DEFAULT 0 AFTER `selectedPersonaIndex`;

ALTER TABLE `USER` ADD `declineGroupInvite` INT NOT NULL DEFAULT 0 AFTER `appearOffline`;

ALTER TABLE `USER` ADD `declineIncommingFriendRequests` TINYINT(1) NOT NULL DEFAULT 0 AFTER `declineGroupInvite`;

ALTER TABLE `USER` ADD `declinePrivateInvite` INT NOT NULL DEFAULT 0 AFTER `declineIncommingFriendRequests`;

ALTER TABLE `USER` ADD `hideOfflineFriends` TINYINT(1) NOT NULL DEFAULT 0 AFTER `declinePrivateInvite`;

ALTER TABLE `USER` ADD `showNewsOnSignIn` TINYINT(1) NOT NULL DEFAULT 0 AFTER `hideOfflineFriends`;

ALTER TABLE `USER` ADD `showOnlyPlayersInSameChatChannel` TINYINT(1) NOT NULL DEFAULT 0 AFTER `showNewsOnSignIn`;