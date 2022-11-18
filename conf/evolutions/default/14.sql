# --- !Ups

ALTER TABLE election ADD segmented_mixing boolean DEFAULT false;
UPDATE election set segmented_mixing=false where 1=1;

# --- !Downs

ALTER TABLE election DROP segmented_mixing;
