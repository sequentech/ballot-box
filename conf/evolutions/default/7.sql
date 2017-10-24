# --- !Ups

ALTER TABLE election ALTER COLUMN start_date DROP DEFAULT;
ALTER TABLE election ALTER COLUMN start_date DROP NOT NULL;
UPDATE election SET start_date=NULL where start_date='2000-01-01 00:00:00.001';

ALTER TABLE election ALTER COLUMN end_date DROP DEFAULT;
ALTER TABLE election ALTER COLUMN end_date DROP NOT NULL;
UPDATE election SET end_date=NULL where end_date='2000-01-01 00:00:00.001';


# --- !Downs

ALTER TABLE election ALTER COLUMN start_date SET DEFAULT '2000-01-01 00:00:00.001';
UPDATE election SET start_date='2000-01-01 00:00:00.001' where start_date IS NULL;
ALTER TABLE election ALTER COLUMN start_date SET NOT NULL;

ALTER TABLE election ALTER COLUMN end_date SET DEFAULT '2000-01-01 00:00:00.001';
UPDATE election SET end_date='2000-01-01 00:00:00.001' where end_date IS NULL;
ALTER TABLE election ALTER COLUMN end_date SET NOT NULL;
