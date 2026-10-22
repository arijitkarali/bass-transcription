CREATE TABLE IF NOT EXISTS jobs (
 id uuid PRIMARY KEY, status varchar(32) NOT NULL, input_path varchar(255) NOT NULL,
 result_path varchar(255), error_message varchar(2000), size_bytes bigint,
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS outbox (id uuid PRIMARY KEY REFERENCES jobs(id), input_path varchar(255) NOT NULL);
