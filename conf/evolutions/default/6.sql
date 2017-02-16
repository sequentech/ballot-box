# --- !Ups

CREATE INDEX "voter_id_created_desc_idx" ON vote (voter_id asc, created desc);

# --- !Downs

DROP INDEX "voter_id_created_desc_idx"; 