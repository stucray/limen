CREATE TABLE users (
    id bigserial PRIMARY KEY,
    username varchar(100) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);
