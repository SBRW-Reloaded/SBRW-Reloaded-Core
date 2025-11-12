-- Add NEXTEVENTID column to store Race Again selected event
-- This ensures all players from the same event session race on the same randomly selected event

ALTER TABLE EVENT_SESSION
ADD COLUMN NEXTEVENTID INT NULL,
ADD CONSTRAINT FK_EVENT_SESSION_EVENT_NEXTEVENTID 
    FOREIGN KEY (NEXTEVENTID) REFERENCES EVENT(ID);

-- Create index for performance
CREATE INDEX IDX_EVENT_SESSION_NEXTEVENTID ON EVENT_SESSION(NEXTEVENTID);
