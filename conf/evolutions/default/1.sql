# --- Initial

# --- !Ups

create table "election" ("id" BIGINT NOT NULL PRIMARY KEY,"configuration" text NOT NULL,"state" VARCHAR(254) NOT NULL,"start_date" TIMESTAMP NOT NULL,"end_date" TIMESTAMP NOT NULL,"pks" text,"results" text,"results_updated" TIMESTAMP);
create table "vote" ("id" BIGSERIAL NOT NULL PRIMARY KEY,"election_id" BIGINT NOT NULL,"voter_id" text NOT NULL,"vote" text NOT NULL,"hash" text NOT NULL,"created" TIMESTAMP NOT NULL);

# --- !Downs

drop table "vote";
drop table "election";

