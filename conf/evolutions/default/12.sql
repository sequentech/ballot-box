# --- !Ups

ALTER TABLE election ADD public_candidates boolean DEFAULT true;
UPDATE election set public_candidates=true where 1=1;

# --- !Downs

ALTER TABLE election DROP public_candidates;