# --- !Ups

ALTER TABLE election ADD weighted_voting_field TEXT DEFAULT NULL;

# --- !Downs

ALTER TABLE election DROP weighted_voting_field;
