ALTER TABLE jobs ADD COLUMN IF NOT EXISTS user_file_name varchar(255);
ALTER TABLE jobs ADD COLUMN download_name varchar(255);
-- Reservations intentionally survive deletion, so old names are never reused.
CREATE TABLE download_names (name varchar(255) PRIMARY KEY);
CREATE UNIQUE INDEX download_names_case_insensitive_idx ON download_names(lower(name));
CREATE INDEX jobs_created_at_idx ON jobs (created_at DESC, id DESC);
CREATE INDEX jobs_updated_at_idx ON jobs (updated_at DESC);
DO $$
DECLARE item record; base text; candidate text; suffix integer;
BEGIN
 FOR item IN SELECT id, user_file_name FROM jobs ORDER BY created_at, id LOOP
  base := btrim(regexp_replace(regexp_replace(coalesce(item.user_file_name, ''), '^.*[/\\]', ''), '[[:cntrl:]]', '_', 'g'));
  IF base = '' OR base = '.' OR base = '..' THEN base := item.id::text; END IF;
  WHILE octet_length(base) > 180 LOOP base := left(base, length(base)-1); END LOOP;
  suffix := 1;
  LOOP
   candidate := base || CASE WHEN suffix = 1 THEN '' ELSE ' (' || suffix || ')' END;
   INSERT INTO download_names(name) VALUES(candidate) ON CONFLICT DO NOTHING;
   IF FOUND THEN EXIT; END IF;
   suffix := suffix+1;
  END LOOP;
  UPDATE jobs SET download_name=candidate WHERE id=item.id;
 END LOOP;
END $$;
ALTER TABLE jobs ALTER COLUMN download_name SET NOT NULL;
CREATE UNIQUE INDEX jobs_download_name_idx ON jobs(download_name);
