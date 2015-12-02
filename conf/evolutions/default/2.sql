# --- !Ups

ALTER TABLE election ADD real boolean DEFAULT false;
UPDATE election set real=true where 1=1;

# --- !Downs

ALTER TABLE election DROP real;

