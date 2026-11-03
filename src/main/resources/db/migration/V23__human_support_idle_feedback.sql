ALTER TABLE support_human_session
    ADD COLUMN last_message_at DATETIME(3) NULL,
    ADD COLUMN close_reason VARCHAR(16) NULL,
    ADD COLUMN rating TINYINT NULL,
    ADD COLUMN rating_comment VARCHAR(500) NULL,
    ADD COLUMN rated_at DATETIME(3) NULL;

UPDATE support_human_session s
LEFT JOIN (SELECT session_id, MAX(created_at) AS last_message FROM support_human_message GROUP BY session_id) m
    ON m.session_id = s.id
SET s.last_message_at = COALESCE(m.last_message, s.created_at),
    s.close_reason = CASE WHEN s.status = 'CLOSED' THEN 'MANUAL' ELSE NULL END;

ALTER TABLE support_human_session
    MODIFY COLUMN last_message_at DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    ADD KEY ix_human_session_idle (status, last_message_at, id),
    ADD CONSTRAINT ck_human_session_close_reason CHECK (
        (status <> 'CLOSED' AND close_reason IS NULL) OR
        (status = 'CLOSED' AND (close_reason IS NULL OR close_reason IN ('MANUAL','BUYER','STAFF','INACTIVITY')))),
    ADD CONSTRAINT ck_human_session_feedback CHECK (
        (rating IS NULL AND rating_comment IS NULL AND rated_at IS NULL) OR
        (rating IS NOT NULL AND rating BETWEEN 1 AND 5 AND rating_comment IS NOT NULL AND rated_at IS NOT NULL AND status = 'CLOSED'));
