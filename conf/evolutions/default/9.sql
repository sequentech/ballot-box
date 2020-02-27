# --- !Ups

ALTER TABLE election ADD ballot_boxes_results_config text DEFAULT NULL;

# --- !Downs

ALTER TABLE election DROP ballot_boxes_results_config;
