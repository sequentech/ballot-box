# --- !Ups

ALTER TABLE election ADD published_results text DEFAULT NULL;

# --- !Downs

ALTER TABLE election DROP published_results;
