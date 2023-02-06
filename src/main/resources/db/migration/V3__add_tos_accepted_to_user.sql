ALTER TABLE user_t
    ADD COLUMN tos_accepted BOOLEAN NOT NULL;

UPDATE user_t SET tos_accepted = false;

ALTER TABLE user_t
    ALTER COLUMN tos_accepted
    SET NOT NULL;