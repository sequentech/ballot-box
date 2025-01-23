# --- !Ups

CREATE TABLE "scheduled_events" ("id" BIGINT NOT NULL PRIMARY KEY, "election_id" BIGINT NOT NULL, "event_name" VARCHAR(254) NOT NULL, "payload" TEXT, "scheduled_date" TIMESTAMP NOT NULL, "executed_date" TIMESTAMP, "created" TIMESTAMP NOT NULL );

# --- !Downs

drop table "scheduled_events";