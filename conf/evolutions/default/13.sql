# --- !Ups

ALTER TABLE election ADD trustee_keys_state text DEFAULT NULL;

# --- !Downs

ALTER TABLE election DROP trustee_keys_state;
