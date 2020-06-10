# --- !Ups

ALTER TABLE election ADD tally_allowed boolean DEFAULT false;
UPDATE election set tally_allowed=true where 1=1;

# --- !Downs

ALTER TABLE election DROP tally_allowed;