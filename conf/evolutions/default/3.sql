# --- !Ups

ALTER TABLE election ADD results_config text DEFAULT NULL;
ALTER TABLE election ADD virtual boolean DEFAULT false;

# --- !Downs

ALTER TABLE election DROP virtual;
ALTER TABLE election DROP results_config;

