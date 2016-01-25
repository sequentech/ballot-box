# --- !Ups

ALTER TABLE election ADD "extra_data" text;

UPDATE election set "extra_data"='' where 1=1;

# --- !Downs

ALTER TABLE election DROP "extra_data";

