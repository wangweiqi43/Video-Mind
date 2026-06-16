ALTER TABLE chat_session
  ADD COLUMN video_id BIGINT NULL AFTER user_id,
  ADD KEY idx_video_updated_time (video_id, updated_time);

UPDATE chat_session session
JOIN (
  SELECT session_id,
         CAST(JSON_UNQUOTE(JSON_EXTRACT(references_json, '$[0].videoId')) AS UNSIGNED) AS video_id
  FROM chat_message
  WHERE role = 'ASSISTANT'
    AND references_json IS NOT NULL
    AND JSON_EXTRACT(references_json, '$[0].videoId') IS NOT NULL
) inferred ON inferred.session_id = session.id
SET session.video_id = inferred.video_id
WHERE session.video_id IS NULL;

UPDATE chat_session session
JOIN (
  SELECT session_id, MAX(created_time) AS last_message_time
  FROM chat_message
  GROUP BY session_id
) latest ON latest.session_id = session.id
SET session.updated_time = latest.last_message_time;

-- Resolve any remaining legacy NULL rows manually before applying this constraint.
ALTER TABLE chat_session MODIFY video_id BIGINT NOT NULL;
