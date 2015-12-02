# --- !Ups

ALTER TABLE election ADD real boolean NULL DEFAULT null;

# --- !Downs

ALTER TABLE election DROP real;

