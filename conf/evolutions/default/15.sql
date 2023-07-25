# --- !Ups

ALTER TABLE election ADD tally_state VARCHAR(254) DEFAULT 'no_tally';
ALTER TABLE election ALTER COLUMN tally_state SET NOT NULL;

# --- !Downs

ALTER TABLE election DROP tally_state;
