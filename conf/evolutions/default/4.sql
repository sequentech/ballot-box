# --- !Ups

ALTER TABLE election ADD logo_url text DEFAULT NULL;

# --- !Downs

ALTER TABLE election DROP logo_url;