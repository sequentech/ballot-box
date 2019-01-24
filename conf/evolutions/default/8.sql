# --- !Ups

ALTER TABLE election DROP real;

# --- !Downs

ALTER TABLE election ADD real boolean DEFAULT false;
UPDATE election set real=true where 1=1;