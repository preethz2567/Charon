ALTER TABLE jobs ADD COLUMN parent_job_id BIGINT REFERENCES jobs(id);
