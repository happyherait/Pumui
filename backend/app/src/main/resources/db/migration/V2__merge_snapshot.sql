CREATE TABLE pr_merge_snapshots (
  pr_id    NUMBER PRIMARY KEY REFERENCES purchase_requests(id),
  checks   CLOB NOT NULL,
  required CLOB NOT NULL,
  CONSTRAINT ck_snapshot_checks CHECK (checks IS JSON),
  CONSTRAINT ck_snapshot_required CHECK (required IS JSON)
);

CREATE OR REPLACE TRIGGER trg_snapshots_immutable
BEFORE UPDATE OR DELETE ON pr_merge_snapshots
BEGIN
  RAISE_APPLICATION_ERROR(-20004, 'pr_merge_snapshots is append-only');
END;
/
