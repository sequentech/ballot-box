# --- !Ups

CREATE OR REPLACE FUNCTION "json_object_set_key"(
  "json"          json,
  "key_to_set"    TEXT,
  "value_to_set"  anyelement
)
  RETURNS json
  LANGUAGE sql
  IMMUTABLE
  STRICT
AS $function$
SELECT concat('{', string_agg(to_json("key") || ':' || "value", ','), '}')::json
  FROM (SELECT *
          FROM json_each("json")
         WHERE "key" <> "key_to_set"
         UNION ALL
        SELECT "key_to_set", to_json("value_to_set")) AS "fields"
$function$;;

CREATE OR REPLACE FUNCTION "json_object_set_path"(
  "json"          json,
  "key_path"      TEXT[],
  "value_to_set"  anyelement
)
  RETURNS json
  LANGUAGE sql
  IMMUTABLE
  STRICT
AS $function$
SELECT CASE COALESCE(array_length("key_path", 1), 0)
         WHEN 0 THEN to_json("value_to_set")
         WHEN 1 THEN "json_object_set_key"("json", "key_path"[l], "value_to_set")
         ELSE "json_object_set_key"(
           "json",
           "key_path"[l],
           "json_object_set_path"(
             COALESCE(NULLIF(("json" -> "key_path"[l])::text, 'null'), '{}')::json,
             "key_path"[l+1:u],
             "value_to_set"
           )
         )
       END
  FROM array_lower("key_path", 1) l,
       array_upper("key_path", 1) u
$function$;;

UPDATE election SET configuration=json_object_set_path(configuration::json,'{presentation,share_text}',cast(concat('[{"network":"Twitter","button_text":"","social_message":"', configuration::json->'presentation'->>'share_text', '"}]') AS json));

# --- !Downs

CREATE OR REPLACE FUNCTION "json_object_set_key"(
  "json"          json,
  "key_to_set"    TEXT,
  "value_to_set"  anyelement
)
  RETURNS json
  LANGUAGE sql
  IMMUTABLE
  STRICT
AS $function$
SELECT concat('{', string_agg(to_json("key") || ':' || "value", ','), '}')::json
  FROM (SELECT *
          FROM json_each("json")
         WHERE "key" <> "key_to_set"
         UNION ALL
        SELECT "key_to_set", to_json("value_to_set")) AS "fields"
$function$;;

CREATE OR REPLACE FUNCTION "json_object_set_path"(
  "json"          json,
  "key_path"      TEXT[],
  "value_to_set"  anyelement
)
  RETURNS json
  LANGUAGE sql
  IMMUTABLE
  STRICT
AS $function$
SELECT CASE COALESCE(array_length("key_path", 1), 0)
         WHEN 0 THEN to_json("value_to_set")
         WHEN 1 THEN "json_object_set_key"("json", "key_path"[l], "value_to_set")
         ELSE "json_object_set_key"(
           "json",
           "key_path"[l],
           "json_object_set_path"(
             COALESCE(NULLIF(("json" -> "key_path"[l])::text, 'null'), '{}')::json,
             "key_path"[l+1:u],
             "value_to_set"
           )
         )
       END
  FROM array_lower("key_path", 1) l,
       array_upper("key_path", 1) u
$function$;;

UPDATE election SET configuration=json_object_set_path(configuration::json,'{presentation,share_text}',cast(configuration::json#>'{presentation,share_text,0,social_message}' AS json));