-- Migration: SBRW-Reloaded-Core-old → SBRW-Reloaded-Core
-- Adds new tables, columns, and parameters for:
--   AutoTune, Lobby Ready, Race Again, social preferences

-- =============================================
-- 1. NEW TABLE: AUTO_TUNE_CACHE
-- =============================================
CREATE TABLE IF NOT EXISTS `AUTO_TUNE_CACHE` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `physics_hash` int(11) NOT NULL,
  `class_name` varchar(10) NOT NULL,
  `priority` varchar(20) NOT NULL,
  `level` int(11) NOT NULL DEFAULT 100,
  `part_hashes` varchar(1000) NOT NULL,
  `achieved_rating` int(11) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_autotune_cache_composite` (`physics_hash`, `class_name`, `priority`, `level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 2. NEW COLUMNS on USER table (Race Again preferences)
-- =============================================
ALTER TABLE `USER`
  ADD COLUMN IF NOT EXISTS `raceAgainEnabled` tinyint(1) NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS `raceAgainMode` int(11) NOT NULL DEFAULT 0;

-- =============================================
-- 3. NEW COLUMN on CAR_CLASSES (Performance Lock)
-- =============================================
ALTER TABLE `CAR_CLASSES`
  ADD COLUMN IF NOT EXISTS `perf_locked` tinyint(1) NOT NULL DEFAULT 0;

-- =============================================
-- 4. NEW PARAMETER entries
-- =============================================
INSERT IGNORE INTO `PARAMETER` (`name`, `value`) VALUES
  -- AutoTune
  ('SBRWR_ENABLE_AUTOTUNE', 'false'),
  ('SBRWR_AUTOTUNE_COMMAND_MAX_ITERATIONS', '1000000'),
  ('SBRWR_AUTOTUNE_RELOAD_MAX_ITERATIONS', '10000000'),
  -- Lobby Ready
  ('SBRWR_ENABLE_LOBBY_READY', 'false'),
  ('SBRWR_READY_ENABLE_VOTEMESSAGES', 'false'),
  ('SBRWR_READY_THRESHOLD', '0'),
  -- Race Again / RaceNow persistent queue
  ('SBRWR_RACENOW_PERSISTENT_ENABLED', 'false'),
  ('SBRWR_RACE_AGAIN_MIN_TIME', '20000'),
  ('RACE_AGAIN_EMPTY_LOBBY_GRACE_PERIOD', '30000'),
  ('SBRWR_RACENOW_AUTO_CREATE_DELAY', '0'),
  ('SBRWR_RACENOW_MAX_WAIT_MINUTES', '5'),
  ('SBRWR_RACENOW_MONITOR_INTERVAL', '10000'),
  -- Lobby
  ('SBRWR_LOCK_LOBBY_CAR_CLASS', 'false');
