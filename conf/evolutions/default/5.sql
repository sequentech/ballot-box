# --- !Ups

UPDATE election SET configuration=json_object_set_path(configuration::json,'{presentation,share_text}',cast(concat('[{"network":"Twitter","button_text":"","social_message":"', configuration::json->'presentation'->>'share_text', '"}]') AS json));

# --- !Downs

UPDATE election SET configuration=json_object_set_path(configuration::json,'{presentation,share_text}',cast(configuration::json#>'{presentation,share_text,0,social_message}' AS json));