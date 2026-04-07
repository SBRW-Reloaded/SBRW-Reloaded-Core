-- Add perf_locked column to CAR_CLASSES table
-- When set to 1, autotune performance modifications are blocked for this car
ALTER TABLE CAR_CLASSES ADD COLUMN perf_locked TINYINT(1) NOT NULL DEFAULT 0;
