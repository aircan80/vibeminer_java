-- Authored schema-only fixture exported from a catalog created by VibeMiner f533bb3.
-- Schema 19; no source bodies, application rows, game content or local paths.
-- VIBEMINER-STATEMENT
CREATE TABLE build_inventory_generations(id TEXT PRIMARY KEY,snapshot_id TEXT NOT NULL,admission_id TEXT NOT NULL,en_artifact TEXT NOT NULL,en_generation TEXT NOT NULL,scope TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('STAGING','READY')),row_count INTEGER NOT NULL DEFAULT 0,derived_bytes INTEGER NOT NULL DEFAULT 0,batches INTEGER NOT NULL DEFAULT 0,member_count INTEGER);
-- VIBEMINER-STATEMENT
CREATE TABLE build_inventory_members(generation TEXT NOT NULL REFERENCES build_inventory_generations(id) ON DELETE CASCADE,ordinal INTEGER NOT NULL,ckey TEXT NOT NULL,ekey TEXT NOT NULL,decoded_bytes INTEGER NOT NULL,encoded_bytes INTEGER NOT NULL,PRIMARY KEY(generation,ordinal),UNIQUE(generation,ckey));
-- VIBEMINER-STATEMENT
CREATE TABLE build_inventory_references(generation TEXT NOT NULL,role TEXT NOT NULL,ordinal INTEGER NOT NULL,block_ordinal INTEGER NOT NULL,key_ref TEXT NOT NULL,declared_bytes INTEGER,raw_fact TEXT NOT NULL CHECK(length(raw_fact)<=8192),resolution TEXT,ckey TEXT,ekey TEXT,decoded_bytes INTEGER,encoded_bytes INTEGER,PRIMARY KEY(generation,role,ordinal),FOREIGN KEY(generation,role) REFERENCES build_inventory_sources(generation,role) ON DELETE CASCADE);
-- VIBEMINER-STATEMENT
CREATE TABLE build_inventory_sources(generation TEXT NOT NULL REFERENCES build_inventory_generations(id) ON DELETE CASCADE,role TEXT NOT NULL,proof BLOB NOT NULL CHECK(length(proof)<=262144),complete INTEGER NOT NULL DEFAULT 0 CHECK(complete IN (0,1)),expected_rows INTEGER,PRIMARY KEY(generation,role));
-- VIBEMINER-STATEMENT
CREATE TABLE build_library_admission_counter(singleton INTEGER PRIMARY KEY CHECK(singleton=1),ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal>=0));
-- VIBEMINER-STATEMENT
CREATE TABLE build_library_baselines(
  product_id TEXT NOT NULL,region TEXT NOT NULL,
  event_id TEXT NOT NULL REFERENCES build_library_registrations(event_id),
  observation_sequence INTEGER NOT NULL CHECK(observation_sequence>0),recorded_at TEXT NOT NULL,
  record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64),PRIMARY KEY(product_id,region));
-- VIBEMINER-STATEMENT
CREATE TABLE build_library_checks(
  attempt_id TEXT PRIMARY KEY,product_id TEXT NOT NULL,region TEXT NOT NULL,
  ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal>0),
  requested_at TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('PENDING','SUCCESS','FAILED','CANCELLED')),
  event_id TEXT REFERENCES discovery_history_events(event_id),completed_at TEXT,
  record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64),
  parent_attempt_id TEXT REFERENCES build_library_product_checks(attempt_id),parent_sha256 TEXT,
  CHECK((parent_attempt_id IS NULL AND parent_sha256 IS NULL) OR(parent_attempt_id IS NOT NULL AND length(parent_sha256)=64)),UNIQUE(product_id,region,ordinal),
  CHECK((status='PENDING' AND event_id IS NULL AND completed_at IS NULL) OR
        (status='SUCCESS' AND event_id IS NOT NULL AND completed_at IS NOT NULL) OR
        (status IN ('FAILED','CANCELLED') AND event_id IS NULL AND completed_at IS NOT NULL)));
-- VIBEMINER-STATEMENT
CREATE TABLE build_library_event_groups(
  event_id TEXT NOT NULL REFERENCES build_library_registrations(event_id),
  group_id TEXT NOT NULL REFERENCES build_library_groups(group_id),
  first_row_ordinal INTEGER NOT NULL CHECK(first_row_ordinal BETWEEN 0 AND 9999),
  PRIMARY KEY(event_id,group_id));
-- VIBEMINER-STATEMENT
CREATE TABLE build_library_groups(
  group_id TEXT PRIMARY KEY,product_id TEXT NOT NULL,
  key_record BLOB NOT NULL CHECK(typeof(key_record)='blob' AND length(key_record) BETWEEN 1 AND 2097152),
  key_sha256 TEXT NOT NULL CHECK(length(key_sha256)=64),
  first_event_id TEXT NOT NULL REFERENCES build_library_registrations(event_id),
  first_row_ordinal INTEGER NOT NULL CHECK(first_row_ordinal BETWEEN 0 AND 9999),
  first_observation_sequence INTEGER NOT NULL CHECK(first_observation_sequence>0),
  first_seen_at TEXT NOT NULL,new_eligible INTEGER NOT NULL CHECK(new_eligible IN (0,1)),
  acknowledged_at TEXT,seen_sha256 TEXT NOT NULL CHECK(length(seen_sha256)=64));
-- VIBEMINER-STATEMENT
CREATE TABLE build_library_product_checks(
  attempt_id TEXT PRIMARY KEY,product_id TEXT NOT NULL,
  ordinal INTEGER NOT NULL UNIQUE CHECK(typeof(ordinal)='integer' AND ordinal>0),
  requested_at TEXT NOT NULL,record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64));
-- VIBEMINER-STATEMENT
CREATE TABLE build_library_region_states(
  product_id TEXT NOT NULL,region TEXT NOT NULL,
  finished_ordinal INTEGER NOT NULL CHECK(finished_ordinal>0),
  check_id TEXT NOT NULL REFERENCES build_library_checks(attempt_id),
  latest_successful_event_id TEXT REFERENCES build_library_registrations(event_id),
  check_state TEXT NOT NULL CHECK(check_state IN ('SUCCESS','FAILED','CANCELLED')),
  checked_at TEXT NOT NULL,record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64),
  PRIMARY KEY(product_id,region));
-- VIBEMINER-STATEMENT
CREATE TABLE build_library_registrations(
  registration_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL UNIQUE REFERENCES discovery_history_events(event_id),
  kind TEXT NOT NULL CHECK(kind IN ('HISTORICAL_IMPORT','SUCCESSFUL_CHECK')),
  check_id TEXT UNIQUE REFERENCES build_library_checks(attempt_id),recorded_at TEXT NOT NULL,
  group_count INTEGER NOT NULL CHECK(typeof(group_count)='integer' AND group_count BETWEEN 1 AND 64),
  record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64),
  CHECK((kind='HISTORICAL_IMPORT' AND check_id IS NULL) OR(kind='SUCCESSFUL_CHECK' AND check_id IS NOT NULL)));
-- VIBEMINER-STATEMENT
CREATE TABLE build_library_revision(singleton INTEGER PRIMARY KEY CHECK(singleton=1),revision INTEGER NOT NULL CHECK(typeof(revision)='integer' AND revision>=0));
-- VIBEMINER-STATEMENT
CREATE TABLE build_library_snapshot_groups(
  snapshot_id TEXT PRIMARY KEY REFERENCES snapshots(id),
  group_id TEXT NOT NULL REFERENCES build_library_groups(group_id),
  versions_digest TEXT NOT NULL REFERENCES source_blobs(digest),
  record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64));
-- VIBEMINER-STATEMENT
CREATE TABLE build_object_memberships(snapshot_id TEXT NOT NULL REFERENCES snapshots(id),role TEXT NOT NULL,namespace TEXT NOT NULL,source_key TEXT NOT NULL,verifier_semantics TEXT NOT NULL,source_url TEXT NOT NULL,verified_at TEXT NOT NULL,PRIMARY KEY(snapshot_id,role),FOREIGN KEY(namespace,source_key,verifier_semantics) REFERENCES source_aliases(namespace,source_key,verifier_semantics));
-- VIBEMINER-STATEMENT
CREATE TABLE build_open_admissions(
  context_id TEXT PRIMARY KEY CHECK(length(context_id)=64),
  snapshot_id TEXT NOT NULL REFERENCES snapshots(id),locale TEXT NOT NULL,
  scope_revision TEXT NOT NULL,classifier_revision TEXT NOT NULL,
  required_count INTEGER NOT NULL CHECK(required_count BETWEEN 5 AND 1024),
  closure_sha256 TEXT NOT NULL CHECK(length(closure_sha256)=64),committed_at TEXT NOT NULL,
  UNIQUE(snapshot_id,locale,scope_revision));
-- VIBEMINER-STATEMENT
CREATE TABLE build_open_receipts(
  context_id TEXT NOT NULL REFERENCES build_open_admissions(context_id),
  event_id TEXT NOT NULL REFERENCES build_library_registrations(event_id),
  row_ordinal INTEGER NOT NULL CHECK(row_ordinal BETWEEN 0 AND 9999),
  group_id TEXT NOT NULL REFERENCES build_library_groups(group_id),
  opened_at TEXT NOT NULL,PRIMARY KEY(context_id,event_id,row_ordinal));
-- VIBEMINER-STATEMENT
CREATE TABLE build_open_role_facts(
  context_id TEXT NOT NULL REFERENCES build_open_admissions(context_id),role TEXT NOT NULL,
  namespace TEXT NOT NULL,source_key TEXT NOT NULL,verifier_semantics TEXT NOT NULL,
  object_digest TEXT NOT NULL REFERENCES catalog_objects(digest),object_length INTEGER NOT NULL CHECK(object_length>0),
  canonical_token TEXT,proof_sha256 TEXT NOT NULL CHECK(length(proof_sha256)=64),
  PRIMARY KEY(context_id,role),
  FOREIGN KEY(namespace,source_key,verifier_semantics) REFERENCES source_aliases(namespace,source_key,verifier_semantics));
-- VIBEMINER-STATEMENT
CREATE TABLE build_retention_bindings(issuance INTEGER PRIMARY KEY AUTOINCREMENT,inventory_id TEXT NOT NULL UNIQUE,group_id TEXT NOT NULL,snapshot_id TEXT NOT NULL,admission_id TEXT NOT NULL,generation TEXT NOT NULL REFERENCES build_inventory_generations(id),scope_revision TEXT NOT NULL,scope_commitment TEXT NOT NULL,event_id TEXT NOT NULL,observation_sequence INTEGER NOT NULL CHECK(typeof(observation_sequence)='integer' AND observation_sequence>0),row_ordinal INTEGER NOT NULL CHECK(typeof(row_ordinal)='integer' AND row_ordinal>=0),locale TEXT NOT NULL,metadata_scope TEXT NOT NULL,policy TEXT NOT NULL,member_count INTEGER NOT NULL CHECK(typeof(member_count)='integer' AND member_count>=0));
-- VIBEMINER-STATEMENT
CREATE TABLE build_retention_intents(
  target_id TEXT PRIMARY KEY, group_id TEXT NOT NULL, snapshot_id TEXT NOT NULL,
  admission_id TEXT NOT NULL, inventory_id TEXT NOT NULL, inventory_generation INTEGER NOT NULL CHECK(typeof(inventory_generation)='integer' AND inventory_generation>0),
  scope_revision TEXT NOT NULL, scope_commitment TEXT NOT NULL,
  mode TEXT NOT NULL CHECK(mode IN ('ON_DEMAND','DOWNLOAD_ALL')),
  generation INTEGER NOT NULL CHECK(typeof(generation)='integer' AND generation>0), authorized_at TEXT NOT NULL,
  member_count INTEGER NOT NULL CHECK(typeof(member_count)='integer' AND member_count>=0), next_ordinal INTEGER NOT NULL CHECK(typeof(next_ordinal)='integer' AND next_ordinal>=0 AND next_ordinal<=member_count),
  maximum_object INTEGER NOT NULL CHECK(typeof(maximum_object)='integer' AND maximum_object>0), maximum_wire INTEGER NOT NULL CHECK(typeof(maximum_wire)='integer' AND maximum_wire>=0),
  maximum_additional INTEGER NOT NULL CHECK(typeof(maximum_additional)='integer' AND maximum_additional>=0), maximum_requests INTEGER NOT NULL CHECK(typeof(maximum_requests)='integer' AND maximum_requests>=0),
  maximum_attempts INTEGER NOT NULL CHECK(typeof(maximum_attempts)='integer' AND maximum_attempts BETWEEN 1 AND 10),
  reserved_wire INTEGER NOT NULL CHECK(typeof(reserved_wire)='integer' AND reserved_wire>=0 AND reserved_wire<=maximum_wire),
  reserved_additional INTEGER NOT NULL CHECK(typeof(reserved_additional)='integer' AND reserved_additional>=0 AND reserved_additional<=maximum_additional),
  reserved_requests INTEGER NOT NULL CHECK(typeof(reserved_requests)='integer' AND reserved_requests>=0 AND reserved_requests<=maximum_requests));
-- VIBEMINER-STATEMENT
CREATE TABLE build_retention_jobs(
  target_id TEXT NOT NULL REFERENCES build_retention_intents(target_id),
  member_id TEXT NOT NULL, ordinal INTEGER NOT NULL CHECK(typeof(ordinal)='integer' AND ordinal>=0),
  content_key TEXT NOT NULL, encoding_key TEXT NOT NULL, encoded_bytes INTEGER NOT NULL CHECK(typeof(encoded_bytes)='integer' AND encoded_bytes>0),
  cost_wire INTEGER NOT NULL CHECK(typeof(cost_wire)='integer' AND cost_wire>=0), cost_additional INTEGER NOT NULL CHECK(typeof(cost_additional)='integer' AND cost_additional>=0),
  cost_requests INTEGER NOT NULL CHECK(typeof(cost_requests)='integer' AND cost_requests>=0), membership_commitment TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
  attempts INTEGER NOT NULL CHECK(typeof(attempts)='integer' AND attempts BETWEEN 0 AND 10), worker_nonce TEXT, owner_epoch TEXT, lease_generation INTEGER CHECK(lease_generation IS NULL OR (typeof(lease_generation)='integer' AND lease_generation>0)),
  fulfillment_proof TEXT, failure_code TEXT, failure_message TEXT,
  PRIMARY KEY(target_id,member_id), UNIQUE(target_id,ordinal),
  CHECK((state='RUNNING')=(worker_nonce IS NOT NULL)),
  CHECK((worker_nonce IS NULL)=(owner_epoch IS NULL)), CHECK((worker_nonce IS NULL)=(lease_generation IS NULL)),
  CHECK((state='SUCCEEDED')=(fulfillment_proof IS NOT NULL)),
  CHECK(state!='FAILED' OR failure_code IS NOT NULL), CHECK((failure_code IS NULL)=(failure_message IS NULL)));
-- VIBEMINER-STATEMENT
CREATE TABLE build_retention_sources(
  target_id TEXT NOT NULL CHECK(length(target_id)=64),
  member_id TEXT NOT NULL CHECK(length(member_id)=64),
  record BLOB NOT NULL CHECK(typeof(record)='blob' AND length(record) BETWEEN 1 AND 131072),
  fulfillment_proof TEXT NOT NULL CHECK(length(fulfillment_proof)=64),
  PRIMARY KEY(target_id,member_id));
-- VIBEMINER-STATEMENT
CREATE TABLE canonical_content_slots(namespace TEXT NOT NULL,content_key TEXT NOT NULL,encoding_key TEXT NOT NULL,encoded_bytes INTEGER NOT NULL CHECK(typeof(encoded_bytes)='integer' AND encoded_bytes>0),token TEXT NOT NULL UNIQUE,status TEXT NOT NULL,owner_session TEXT NOT NULL,representation BLOB CHECK(representation IS NULL OR length(representation)<=65536), worker_nonce TEXT CHECK(worker_nonce IS NULL OR (typeof(worker_nonce)='text' AND length(worker_nonce)=36)),PRIMARY KEY(namespace,content_key),CHECK(status IN ('PENDING','PUBLISHING','RECOVERY','RETAINED')),CHECK(status<>'RETAINED' OR representation IS NOT NULL));
-- VIBEMINER-STATEMENT
CREATE TABLE canonical_legacy_blocks(product_id TEXT PRIMARY KEY,reason TEXT NOT NULL);
-- VIBEMINER-STATEMENT
CREATE TABLE canonical_legacy_conflicts(namespace TEXT NOT NULL,content_key TEXT NOT NULL,proof BLOB NOT NULL CHECK(length(proof)<=65536),PRIMARY KEY(namespace,content_key,proof));
-- VIBEMINER-STATEMENT
CREATE TABLE catalog_objects(digest TEXT PRIMARY KEY,length INTEGER NOT NULL CHECK(typeof(length)='integer' AND length>=0));
-- VIBEMINER-STATEMENT
CREATE TABLE discovery_history_builds(
  event_id TEXT NOT NULL REFERENCES discovery_history_events(event_id),
  position INTEGER NOT NULL CHECK(typeof(position)='integer' AND position BETWEEN 0 AND 63),
  snapshot_id TEXT NOT NULL REFERENCES snapshots(id),
  versions_row_ordinal INTEGER NOT NULL CHECK(typeof(versions_row_ordinal)='integer' AND versions_row_ordinal BETWEEN 0 AND 9999),
  channel TEXT NOT NULL,
  PRIMARY KEY(event_id,position),UNIQUE(event_id,versions_row_ordinal));
-- VIBEMINER-STATEMENT
CREATE TABLE discovery_history_documents(
  event_id TEXT NOT NULL REFERENCES discovery_history_events(event_id),
  role TEXT NOT NULL CHECK(role IN ('versions','cdns')),
  digest TEXT NOT NULL REFERENCES source_blobs(digest),
  source_url TEXT NOT NULL,captured_at TEXT NOT NULL,
  byte_length INTEGER NOT NULL CHECK(typeof(byte_length)='integer' AND byte_length BETWEEN 1 AND 1048576),
  PRIMARY KEY(event_id,role));
-- VIBEMINER-STATEMENT
CREATE TABLE discovery_history_events(
  event_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL UNIQUE,
  product_id TEXT NOT NULL,region TEXT NOT NULL,completed_at TEXT NOT NULL,
  parser_revision TEXT NOT NULL,
  build_count INTEGER NOT NULL CHECK(typeof(build_count)='integer' AND build_count BETWEEN 1 AND 64),
  record BLOB NOT NULL CHECK(typeof(record)='blob' AND length(record) BETWEEN 1 AND 4194304),
  record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64));
-- VIBEMINER-STATEMENT
CREATE TABLE en_lookup_artifacts(artifact TEXT NOT NULL,generation TEXT NOT NULL,owner TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('STAGING','READY')),source_proof BLOB NOT NULL CHECK(length(source_proof)<=262144),content_count INTEGER,encoding_count INTEGER,candidate_count INTEGER,PRIMARY KEY(artifact,generation),CHECK(status<>'READY' OR (typeof(content_count)='integer' AND typeof(encoding_count)='integer' AND typeof(candidate_count)='integer' AND content_count>=0 AND encoding_count>=0 AND candidate_count>=0)));
-- VIBEMINER-STATEMENT
CREATE TABLE en_lookup_candidates(artifact TEXT NOT NULL,generation TEXT NOT NULL,ckey TEXT NOT NULL,ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 0 AND 254),ekey TEXT NOT NULL CHECK(length(ekey)=32),PRIMARY KEY(artifact,generation,ckey,ordinal),FOREIGN KEY(artifact,generation,ckey) REFERENCES en_lookup_content(artifact,generation,ckey) ON DELETE CASCADE);
-- VIBEMINER-STATEMENT
CREATE TABLE en_lookup_content(artifact TEXT NOT NULL,generation TEXT NOT NULL,ckey TEXT NOT NULL CHECK(length(ckey)=32),decoded_bytes INTEGER NOT NULL CHECK(decoded_bytes BETWEEN 0 AND 1099511627775),candidate_count INTEGER NOT NULL CHECK(candidate_count BETWEEN 1 AND 255),PRIMARY KEY(artifact,generation,ckey),FOREIGN KEY(artifact,generation) REFERENCES en_lookup_artifacts(artifact,generation) ON DELETE CASCADE);
-- VIBEMINER-STATEMENT
CREATE TABLE en_lookup_encoding(artifact TEXT NOT NULL,generation TEXT NOT NULL,ekey TEXT NOT NULL CHECK(length(ekey)=32),encoded_bytes INTEGER NOT NULL CHECK(encoded_bytes BETWEEN 0 AND 1099511627775),spec_ordinal INTEGER NOT NULL CHECK(spec_ordinal>=0),PRIMARY KEY(artifact,generation,ekey),FOREIGN KEY(artifact,generation) REFERENCES en_lookup_artifacts(artifact,generation) ON DELETE CASCADE);
-- VIBEMINER-STATEMENT
CREATE TABLE encoded_file_receipts(namespace TEXT NOT NULL,encoding_key TEXT NOT NULL,verifier_semantics TEXT NOT NULL,record BLOB NOT NULL CHECK(length(record)<=65536),PRIMARY KEY(namespace,encoding_key,verifier_semantics));
-- VIBEMINER-STATEMENT
CREATE TABLE item_projection_artifacts(artifact_id TEXT PRIMARY KEY,definition BLOB NOT NULL CHECK(length(definition)<=262144),generation TEXT NOT NULL,owner_epoch TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('STAGING','READY')),section_ordinal INTEGER NOT NULL CHECK(typeof(section_ordinal)='integer' AND section_ordinal>=0),physical_rows INTEGER,copy_count INTEGER,excluded_sections INTEGER, copy_proof_version INTEGER CHECK(copy_proof_version IS NULL OR (typeof(copy_proof_version)='integer' AND copy_proof_version=1)),UNIQUE(artifact_id,generation),CHECK(status<>'READY' OR (typeof(physical_rows)='integer' AND physical_rows>=0 AND typeof(copy_count)='integer' AND copy_count>=0 AND typeof(excluded_sections)='integer' AND excluded_sections>=0)));
-- VIBEMINER-STATEMENT
CREATE TABLE item_projection_contexts(artifact_id TEXT NOT NULL REFERENCES item_projection_artifacts(artifact_id),snapshot_id TEXT NOT NULL,source_role TEXT NOT NULL,locale TEXT NOT NULL,evidence BLOB NOT NULL CHECK(length(evidence)<=2097152),PRIMARY KEY(artifact_id,snapshot_id,source_role,locale),FOREIGN KEY(snapshot_id,source_role) REFERENCES build_object_memberships(snapshot_id,role));
-- VIBEMINER-STATEMENT
CREATE TABLE item_projection_copies(artifact_id TEXT NOT NULL,generation TEXT NOT NULL,target_id INTEGER NOT NULL CHECK(typeof(target_id)='integer' AND target_id BETWEEN 1 AND 4294967295),source_id INTEGER NOT NULL CHECK(typeof(source_id)='integer' AND source_id BETWEEN 1 AND 4294967295), copy_record BLOB CHECK(copy_record IS NULL OR (typeof(copy_record)='blob' AND length(copy_record)=20 AND hex(substr(copy_record,1,4))='564D4301' AND hex(substr(copy_record,5,8))=printf('%016X',target_id) AND hex(substr(copy_record,13,8))=printf('%016X',source_id))), copy_sha256 TEXT CHECK((copy_record IS NULL AND copy_sha256 IS NULL) OR (copy_record IS NOT NULL AND typeof(copy_sha256)='text' AND length(copy_sha256)=64)),PRIMARY KEY(artifact_id,generation,target_id),FOREIGN KEY(artifact_id,generation) REFERENCES item_projection_artifacts(artifact_id,generation) ON DELETE CASCADE);
-- VIBEMINER-STATEMENT
CREATE TABLE item_projection_rows(artifact_id TEXT NOT NULL,generation TEXT NOT NULL,record_id INTEGER NOT NULL CHECK(typeof(record_id)='integer' AND record_id BETWEEN 0 AND 4294967295),physical_ordinal INTEGER NOT NULL CHECK(typeof(physical_ordinal)='integer' AND physical_ordinal>=0),row BLOB NOT NULL CHECK(length(row)<=1024),row_sha256 TEXT NOT NULL CHECK(length(row_sha256)=64),PRIMARY KEY(artifact_id,generation,record_id),UNIQUE(artifact_id,generation,physical_ordinal),FOREIGN KEY(artifact_id,generation) REFERENCES item_projection_artifacts(artifact_id,generation) ON DELETE CASCADE);
-- VIBEMINER-STATEMENT
CREATE TABLE metadata_build_associations(snapshot_id TEXT NOT NULL REFERENCES snapshots(id),kind TEXT NOT NULL CHECK(kind IN ('ROOT','INSTALL')),role TEXT NOT NULL,canonical_token TEXT NOT NULL REFERENCES canonical_content_slots(token),record BLOB NOT NULL CHECK(typeof(record)='blob' AND length(record)>0 AND length(record)<=262144),record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64),PRIMARY KEY(snapshot_id,kind),FOREIGN KEY(snapshot_id,role) REFERENCES build_object_memberships(snapshot_id,role),CHECK((kind='ROOT' AND role='ROOT_MANIFEST') OR (kind='INSTALL' AND role='INSTALL_MANIFEST')));
-- VIBEMINER-STATEMENT
CREATE TABLE metadata_repair_attempts(attempt_id TEXT PRIMARY KEY,canonical_token TEXT NOT NULL REFERENCES canonical_content_slots(token),owner_epoch TEXT NOT NULL,worker_nonce TEXT NOT NULL,snapshot_id TEXT NOT NULL REFERENCES snapshots(id),kind TEXT NOT NULL CHECK(kind IN ('ROOT','INSTALL')),started_at TEXT NOT NULL,record BLOB NOT NULL CHECK(typeof(record)='blob' AND length(record)>0 AND length(record)<=262144),record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64));
-- VIBEMINER-STATEMENT
CREATE TABLE metadata_repair_events(attempt_id TEXT NOT NULL REFERENCES metadata_repair_attempts(attempt_id),sequence INTEGER NOT NULL CHECK(typeof(sequence)='integer' AND sequence BETWEEN 1 AND 4),kind TEXT NOT NULL CHECK(kind IN ('PROSPECTIVE','STRUCTURE','COMPLETED','CONCLUSIVELY_UNPUBLISHED','UNCERTAIN')),record BLOB NOT NULL CHECK(typeof(record)='blob' AND length(record)>0 AND length(record)<=262144),record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64),PRIMARY KEY(attempt_id,sequence));
-- VIBEMINER-STATEMENT
CREATE TABLE retention_scope_requirements(scope_id TEXT NOT NULL REFERENCES retention_scopes(id),role TEXT NOT NULL,namespace TEXT,source_key TEXT,verifier_semantics TEXT,encoded_bytes INTEGER CHECK(encoded_bytes IS NULL OR (typeof(encoded_bytes)='integer' AND encoded_bytes>=0)),logical_decoded_bytes INTEGER CHECK(logical_decoded_bytes IS NULL OR (typeof(logical_decoded_bytes)='integer' AND logical_decoded_bytes>=0)),PRIMARY KEY(scope_id,role),CHECK((namespace IS NULL AND source_key IS NULL AND verifier_semantics IS NULL) OR (namespace IS NOT NULL AND source_key IS NOT NULL AND verifier_semantics IS NOT NULL)));
-- VIBEMINER-STATEMENT
CREATE TABLE retention_scopes(id TEXT PRIMARY KEY,snapshot_id TEXT NOT NULL REFERENCES snapshots(id),kind TEXT NOT NULL,pinned INTEGER NOT NULL CHECK(typeof(pinned)='integer' AND pinned IN (0,1)),created_at TEXT NOT NULL);
-- VIBEMINER-STATEMENT
CREATE TABLE selected_file_associations(snapshot_id TEXT NOT NULL REFERENCES snapshots(id),role TEXT NOT NULL,slot_namespace TEXT NOT NULL,slot_content_key TEXT NOT NULL,record BLOB NOT NULL CHECK(length(record)<=65536),PRIMARY KEY(snapshot_id,role),FOREIGN KEY(snapshot_id,role) REFERENCES build_object_memberships(snapshot_id,role),FOREIGN KEY(slot_namespace,slot_content_key) REFERENCES canonical_content_slots(namespace,content_key));
-- VIBEMINER-STATEMENT
CREATE TABLE snapshot_sources(snapshot_id TEXT NOT NULL REFERENCES snapshots(id),position INTEGER NOT NULL,digest TEXT NOT NULL REFERENCES source_blobs(digest),url TEXT NOT NULL,captured_at TEXT NOT NULL,PRIMARY KEY(snapshot_id,position));
-- VIBEMINER-STATEMENT
CREATE TABLE snapshots(id TEXT PRIMARY KEY,product_id TEXT NOT NULL,edition_label TEXT NOT NULL,channel TEXT NOT NULL,region TEXT NOT NULL,version_label TEXT NOT NULL,build_config TEXT NOT NULL,cdn_config TEXT NOT NULL,observed_at TEXT NOT NULL,fixture INTEGER NOT NULL,upstream_build_id TEXT NOT NULL,product_config TEXT NOT NULL,source_count INTEGER NOT NULL);
-- VIBEMINER-STATEMENT
CREATE TABLE source_aliases(namespace TEXT NOT NULL,source_key TEXT NOT NULL,verifier_semantics TEXT NOT NULL,object_digest TEXT NOT NULL REFERENCES catalog_objects(digest),source_url TEXT NOT NULL,verified_at TEXT NOT NULL,PRIMARY KEY(namespace,source_key,verifier_semantics));
-- VIBEMINER-STATEMENT
CREATE TABLE source_blobs(digest TEXT PRIMARY KEY,bytes BLOB NOT NULL);
-- VIBEMINER-STATEMENT
CREATE TABLE sparse_name_artifacts(
  artifact_id TEXT PRIMARY KEY,
  definition BLOB NOT NULL CHECK(typeof(definition)='blob' AND length(definition)<=262144),
  definition_sha256 TEXT NOT NULL CHECK(length(definition_sha256)=64),
  generation TEXT NOT NULL, owner_epoch TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('STAGING','READY')),
  copy_proof_version INTEGER NOT NULL DEFAULT 1 CHECK(copy_proof_version=1),
  section_ordinal INTEGER NOT NULL
    CHECK(typeof(section_ordinal)='integer' AND section_ordinal>=0),
  physical_rows INTEGER, copy_count INTEGER, excluded_sections INTEGER,
  projected_utf8_bytes INTEGER,
  UNIQUE(artifact_id,generation),
  CHECK(status<>'READY' OR (
    typeof(physical_rows)='integer' AND physical_rows>=0 AND
    typeof(copy_count)='integer' AND copy_count>=0 AND
    typeof(excluded_sections)='integer' AND excluded_sections>=0 AND
    typeof(projected_utf8_bytes)='integer' AND projected_utf8_bytes>=0)));
-- VIBEMINER-STATEMENT
CREATE TABLE sparse_name_contexts(
  artifact_id TEXT NOT NULL REFERENCES sparse_name_artifacts(artifact_id),
  snapshot_id TEXT NOT NULL, source_role TEXT NOT NULL, locale TEXT NOT NULL,
  evidence BLOB NOT NULL CHECK(typeof(evidence)='blob' AND length(evidence)<=2097152),
  evidence_sha256 TEXT NOT NULL CHECK(length(evidence_sha256)=64),
  PRIMARY KEY(artifact_id,snapshot_id,source_role,locale),
  FOREIGN KEY(snapshot_id,source_role)
    REFERENCES build_object_memberships(snapshot_id,role));
-- VIBEMINER-STATEMENT
CREATE TABLE sparse_name_copies(
  artifact_id TEXT NOT NULL, generation TEXT NOT NULL,
  target_id INTEGER NOT NULL
    CHECK(typeof(target_id)='integer' AND target_id BETWEEN 1 AND 4294967295),
  source_id INTEGER NOT NULL
    CHECK(typeof(source_id)='integer' AND source_id BETWEEN 1 AND 4294967295),
  record BLOB NOT NULL CHECK(
    typeof(record)='blob' AND length(record)=20 AND
    hex(substr(record,1,4))='564D4E02' AND
    hex(substr(record,5,8))=printf('%016X',target_id) AND
    hex(substr(record,13,8))=printf('%016X',source_id)),
  record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64),
  PRIMARY KEY(artifact_id,generation,target_id),
  FOREIGN KEY(artifact_id,generation)
    REFERENCES sparse_name_artifacts(artifact_id,generation) ON DELETE CASCADE);
-- VIBEMINER-STATEMENT
CREATE TABLE sparse_name_rows(
  artifact_id TEXT NOT NULL, generation TEXT NOT NULL,
  record_id INTEGER NOT NULL
    CHECK(typeof(record_id)='integer' AND record_id BETWEEN 1 AND 4294967295),
  physical_ordinal INTEGER NOT NULL
    CHECK(typeof(physical_ordinal)='integer' AND physical_ordinal>=0),
  utf8_bytes INTEGER NOT NULL
    CHECK(typeof(utf8_bytes)='integer' AND utf8_bytes BETWEEN 0 AND 65530),
  record BLOB NOT NULL CHECK(
    typeof(record)='blob' AND length(record)=40+utf8_bytes AND length(record)<=65600 AND
    hex(substr(record,1,4))='564D4E01' AND
    hex(substr(record,5,8))=printf('%016X',record_id) AND
    hex(substr(record,13,4))=printf('%08X',physical_ordinal) AND
    hex(substr(record,37,4))=printf('%08X',utf8_bytes)),
  record_sha256 TEXT NOT NULL CHECK(length(record_sha256)=64),
  PRIMARY KEY(artifact_id,generation,record_id),
  UNIQUE(artifact_id,generation,physical_ordinal),
  FOREIGN KEY(artifact_id,generation)
    REFERENCES sparse_name_artifacts(artifact_id,generation) ON DELETE CASCADE);
-- VIBEMINER-STATEMENT
CREATE TABLE workspaces(id TEXT PRIMARY KEY,snapshot_id TEXT NOT NULL REFERENCES snapshots(id),locale TEXT NOT NULL,name TEXT NOT NULL,saved_at TEXT NOT NULL);
-- VIBEMINER-STATEMENT
CREATE UNIQUE INDEX build_inventory_ready_context ON build_inventory_generations(snapshot_id,admission_id,en_artifact,en_generation,scope) WHERE status='READY';
-- VIBEMINER-STATEMENT
CREATE INDEX build_inventory_reference_content ON build_inventory_references(generation,ckey);
-- VIBEMINER-STATEMENT
CREATE INDEX build_library_group_events ON build_library_event_groups(group_id,event_id);
-- VIBEMINER-STATEMENT
CREATE UNIQUE INDEX build_retention_active_group ON build_retention_intents(group_id) WHERE mode='DOWNLOAD_ALL';
-- VIBEMINER-STATEMENT
CREATE INDEX build_retention_job_state ON build_retention_jobs(state,target_id,ordinal);
-- VIBEMINER-STATEMENT
CREATE INDEX discovery_history_channel ON discovery_history_builds(channel,event_id);
-- VIBEMINER-STATEMENT
CREATE INDEX discovery_history_filter ON discovery_history_events(product_id,region,event_sequence);
-- VIBEMINER-STATEMENT
CREATE UNIQUE INDEX en_lookup_active_writer ON en_lookup_artifacts(artifact) WHERE status='STAGING';
-- VIBEMINER-STATEMENT
CREATE INDEX en_lookup_reverse ON en_lookup_candidates(artifact,generation,ekey);
-- VIBEMINER-STATEMENT
CREATE INDEX en_lookup_reverse_origins ON en_lookup_candidates(artifact,generation,ekey,ckey,ordinal);
-- VIBEMINER-STATEMENT
CREATE INDEX metadata_repair_attempts_by_slot ON metadata_repair_attempts(canonical_token,started_at,attempt_id);
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_inventory_members_ready_DELETE BEFORE DELETE ON build_inventory_members WHEN EXISTS(SELECT 1 FROM build_inventory_generations WHERE id=OLD.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable inventory'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_inventory_members_ready_INSERT BEFORE INSERT ON build_inventory_members WHEN EXISTS(SELECT 1 FROM build_inventory_generations WHERE id=NEW.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable inventory'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_inventory_members_ready_UPDATE BEFORE UPDATE ON build_inventory_members WHEN EXISTS(SELECT 1 FROM build_inventory_generations WHERE id=OLD.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable inventory'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_inventory_members_ready_target BEFORE UPDATE ON build_inventory_members WHEN EXISTS(SELECT 1 FROM build_inventory_generations WHERE id=NEW.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable inventory target'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_inventory_ready_delete BEFORE DELETE ON build_inventory_generations WHEN OLD.status='READY' BEGIN SELECT RAISE(ABORT,'Immutable inventory generation'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_inventory_ready_immutable BEFORE UPDATE ON build_inventory_generations WHEN OLD.status='READY' BEGIN SELECT RAISE(ABORT,'Immutable inventory'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_inventory_references_ready_DELETE BEFORE DELETE ON build_inventory_references WHEN EXISTS(SELECT 1 FROM build_inventory_generations WHERE id=OLD.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable inventory'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_inventory_references_ready_INSERT BEFORE INSERT ON build_inventory_references WHEN EXISTS(SELECT 1 FROM build_inventory_generations WHERE id=NEW.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable inventory'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_inventory_references_ready_UPDATE BEFORE UPDATE ON build_inventory_references WHEN EXISTS(SELECT 1 FROM build_inventory_generations WHERE id=OLD.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable inventory'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_inventory_references_ready_target BEFORE UPDATE ON build_inventory_references WHEN EXISTS(SELECT 1 FROM build_inventory_generations WHERE id=NEW.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable inventory target'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_inventory_sources_ready_DELETE BEFORE DELETE ON build_inventory_sources WHEN EXISTS(SELECT 1 FROM build_inventory_generations WHERE id=OLD.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable inventory'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_inventory_sources_ready_INSERT BEFORE INSERT ON build_inventory_sources WHEN EXISTS(SELECT 1 FROM build_inventory_generations WHERE id=NEW.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable inventory'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_inventory_sources_ready_UPDATE BEFORE UPDATE ON build_inventory_sources WHEN EXISTS(SELECT 1 FROM build_inventory_generations WHERE id=OLD.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable inventory'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_inventory_sources_ready_target BEFORE UPDATE ON build_inventory_sources WHEN EXISTS(SELECT 1 FROM build_inventory_generations WHERE id=NEW.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable inventory target'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_open_admissions_immutable BEFORE UPDATE ON build_open_admissions BEGIN SELECT RAISE(ABORT,'Immutable build admission');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_open_admissions_no_delete BEFORE DELETE ON build_open_admissions BEGIN SELECT RAISE(ABORT,'Build admission is not pruned');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_open_receipts_immutable BEFORE UPDATE ON build_open_receipts BEGIN SELECT RAISE(ABORT,'Immutable build admission');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_open_receipts_no_delete BEFORE DELETE ON build_open_receipts BEGIN SELECT RAISE(ABORT,'Build admission is not pruned');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_open_role_facts_immutable BEFORE UPDATE ON build_open_role_facts BEGIN SELECT RAISE(ABORT,'Immutable build admission');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_open_role_facts_no_delete BEFORE DELETE ON build_open_role_facts BEGIN SELECT RAISE(ABORT,'Build admission is not pruned');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_retention_binding_delete BEFORE DELETE ON build_retention_bindings BEGIN SELECT RAISE(ABORT,'Immutable retention issuance'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_retention_binding_immutable BEFORE UPDATE ON build_retention_bindings BEGIN SELECT RAISE(ABORT,'Immutable retention issuance'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_retention_sources_immutable_delete
BEFORE DELETE ON build_retention_sources BEGIN
  SELECT RAISE(ABORT,'immutable build retention source'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER build_retention_sources_immutable_update
BEFORE UPDATE ON build_retention_sources BEGIN
  SELECT RAISE(ABORT,'immutable build retention source'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER en_lookup_candidates_ready_DELETE BEFORE DELETE ON en_lookup_candidates WHEN EXISTS(SELECT 1 FROM en_lookup_artifacts WHERE artifact=OLD.artifact AND generation=OLD.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable ready EN facts'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER en_lookup_candidates_ready_INSERT BEFORE INSERT ON en_lookup_candidates WHEN EXISTS(SELECT 1 FROM en_lookup_artifacts WHERE artifact=NEW.artifact AND generation=NEW.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable ready EN facts'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER en_lookup_candidates_ready_UPDATE BEFORE UPDATE ON en_lookup_candidates WHEN EXISTS(SELECT 1 FROM en_lookup_artifacts WHERE artifact=OLD.artifact AND generation=OLD.generation AND status='READY') OR EXISTS(SELECT 1 FROM en_lookup_artifacts WHERE artifact=NEW.artifact AND generation=NEW.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable ready EN facts'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER en_lookup_content_ready_DELETE BEFORE DELETE ON en_lookup_content WHEN EXISTS(SELECT 1 FROM en_lookup_artifacts WHERE artifact=OLD.artifact AND generation=OLD.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable ready EN facts'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER en_lookup_content_ready_INSERT BEFORE INSERT ON en_lookup_content WHEN EXISTS(SELECT 1 FROM en_lookup_artifacts WHERE artifact=NEW.artifact AND generation=NEW.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable ready EN facts'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER en_lookup_content_ready_UPDATE BEFORE UPDATE ON en_lookup_content WHEN EXISTS(SELECT 1 FROM en_lookup_artifacts WHERE artifact=OLD.artifact AND generation=OLD.generation AND status='READY') OR EXISTS(SELECT 1 FROM en_lookup_artifacts WHERE artifact=NEW.artifact AND generation=NEW.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable ready EN facts'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER en_lookup_encoding_ready_DELETE BEFORE DELETE ON en_lookup_encoding WHEN EXISTS(SELECT 1 FROM en_lookup_artifacts WHERE artifact=OLD.artifact AND generation=OLD.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable ready EN facts'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER en_lookup_encoding_ready_INSERT BEFORE INSERT ON en_lookup_encoding WHEN EXISTS(SELECT 1 FROM en_lookup_artifacts WHERE artifact=NEW.artifact AND generation=NEW.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable ready EN facts'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER en_lookup_encoding_ready_UPDATE BEFORE UPDATE ON en_lookup_encoding WHEN EXISTS(SELECT 1 FROM en_lookup_artifacts WHERE artifact=OLD.artifact AND generation=OLD.generation AND status='READY') OR EXISTS(SELECT 1 FROM en_lookup_artifacts WHERE artifact=NEW.artifact AND generation=NEW.generation AND status='READY') BEGIN SELECT RAISE(ABORT,'Immutable ready EN facts'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER en_lookup_ready_immutable BEFORE UPDATE ON en_lookup_artifacts WHEN OLD.status='READY' BEGIN SELECT RAISE(ABORT,'Immutable ready EN generation'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER en_lookup_ready_no_delete BEFORE DELETE ON en_lookup_artifacts WHEN OLD.status='READY' BEGIN SELECT RAISE(ABORT,'Immutable ready EN generation'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER item_projection_rows_identity_insert BEFORE INSERT ON item_projection_rows WHEN NOT (typeof(NEW.row)='blob' AND length(NEW.row)>=25 AND hex(substr(NEW.row,1,9))='00000005564D495231' AND hex(substr(NEW.row,10,8))=printf('%016X',NEW.record_id) AND hex(substr(NEW.row,18,4))=printf('%08X',NEW.physical_ordinal)) BEGIN SELECT RAISE(ABORT,'Projected row index differs from encoded identity'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER item_projection_rows_identity_update BEFORE UPDATE OF record_id,physical_ordinal,row ON item_projection_rows WHEN NOT (typeof(NEW.row)='blob' AND length(NEW.row)>=25 AND hex(substr(NEW.row,1,9))='00000005564D495231' AND hex(substr(NEW.row,10,8))=printf('%016X',NEW.record_id) AND hex(substr(NEW.row,18,4))=printf('%08X',NEW.physical_ordinal)) BEGIN SELECT RAISE(ABORT,'Projected row index differs from encoded identity'); END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_admission_monotonic BEFORE UPDATE OF ordinal ON build_library_admission_counter WHEN NEW.ordinal<>OLD.ordinal+1 BEGIN SELECT RAISE(ABORT,'Admission order cannot rewind or skip');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_compatible_identity_immutable BEFORE UPDATE ON build_library_snapshot_groups BEGIN SELECT RAISE(ABORT,'Immutable full-group compatibility');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_group_ack_monotonic BEFORE UPDATE OF acknowledged_at ON build_library_groups WHEN OLD.acknowledged_at IS NOT NULL AND (NEW.acknowledged_at IS NULL OR NEW.acknowledged_at<>OLD.acknowledged_at) BEGIN SELECT RAISE(ABORT,'Open acknowledgment cannot be reset');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_group_identity_immutable BEFORE UPDATE OF group_id,product_id,key_record,key_sha256,first_event_id,first_row_ordinal,first_observation_sequence,first_seen_at,new_eligible ON build_library_groups BEGIN SELECT RAISE(ABORT,'Immutable library group identity');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_immutable_build_library_baselines BEFORE UPDATE ON build_library_baselines BEGIN SELECT RAISE(ABORT,'Immutable library provenance');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_immutable_build_library_event_groups BEFORE UPDATE ON build_library_event_groups BEGIN SELECT RAISE(ABORT,'Immutable library provenance');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_immutable_build_library_registrations BEFORE UPDATE ON build_library_registrations BEGIN SELECT RAISE(ABORT,'Immutable library provenance');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_no_prune_build_library_baselines BEFORE DELETE ON build_library_baselines BEGIN SELECT RAISE(ABORT,'Library state is not pruned');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_no_prune_build_library_checks BEFORE DELETE ON build_library_checks BEGIN SELECT RAISE(ABORT,'Library state is not pruned');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_no_prune_build_library_product_checks BEFORE DELETE ON build_library_product_checks BEGIN SELECT RAISE(ABORT,'Library state is not pruned');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_no_prune_build_library_region_states BEFORE DELETE ON build_library_region_states BEGIN SELECT RAISE(ABORT,'Library state is not pruned');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_no_prune_build_library_snapshot_groups BEFORE DELETE ON build_library_snapshot_groups BEGIN SELECT RAISE(ABORT,'Library state is not pruned');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_baselines_DELETE AFTER DELETE ON build_library_baselines BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_baselines_INSERT AFTER INSERT ON build_library_baselines BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_baselines_UPDATE AFTER UPDATE ON build_library_baselines BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_checks_DELETE AFTER DELETE ON build_library_checks BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_checks_INSERT AFTER INSERT ON build_library_checks BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_checks_UPDATE AFTER UPDATE ON build_library_checks BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_event_groups_DELETE AFTER DELETE ON build_library_event_groups BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_event_groups_INSERT AFTER INSERT ON build_library_event_groups BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_event_groups_UPDATE AFTER UPDATE ON build_library_event_groups BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_groups_DELETE AFTER DELETE ON build_library_groups BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_groups_INSERT AFTER INSERT ON build_library_groups BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_groups_UPDATE AFTER UPDATE ON build_library_groups BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_product_checks_DELETE AFTER DELETE ON build_library_product_checks BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_product_checks_INSERT AFTER INSERT ON build_library_product_checks BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_product_checks_UPDATE AFTER UPDATE ON build_library_product_checks BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_region_states_DELETE AFTER DELETE ON build_library_region_states BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_region_states_INSERT AFTER INSERT ON build_library_region_states BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_region_states_UPDATE AFTER UPDATE ON build_library_region_states BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_registrations_DELETE AFTER DELETE ON build_library_registrations BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_registrations_INSERT AFTER INSERT ON build_library_registrations BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_registrations_UPDATE AFTER UPDATE ON build_library_registrations BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_snapshot_groups_DELETE AFTER DELETE ON build_library_snapshot_groups BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_snapshot_groups_INSERT AFTER INSERT ON build_library_snapshot_groups BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_library_snapshot_groups_UPDATE AFTER UPDATE ON build_library_snapshot_groups BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_object_memberships_DELETE AFTER DELETE ON build_object_memberships BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_object_memberships_INSERT AFTER INSERT ON build_object_memberships BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_object_memberships_UPDATE AFTER UPDATE ON build_object_memberships BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_open_admissions AFTER INSERT ON build_open_admissions BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_build_open_receipts AFTER INSERT ON build_open_receipts BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_monotonic BEFORE UPDATE OF revision ON build_library_revision WHEN NEW.revision<=OLD.revision BEGIN SELECT RAISE(ABORT,'Library revision cannot rewind');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_retention_scopes_DELETE AFTER DELETE ON retention_scopes BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_retention_scopes_INSERT AFTER INSERT ON retention_scopes BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_revision_retention_scopes_UPDATE AFTER UPDATE ON retention_scopes BEGIN UPDATE build_library_revision SET revision=revision+1 WHERE singleton=1;END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_scope_identity_immutable BEFORE UPDATE OF product_id,region ON build_library_region_states BEGIN SELECT RAISE(ABORT,'Immutable library scope identity');END;
-- VIBEMINER-STATEMENT
CREATE TRIGGER library_ticket_identity_immutable BEFORE UPDATE OF attempt_id,product_id,region,ordinal,requested_at,parent_attempt_id,parent_sha256 ON build_library_checks BEGIN SELECT RAISE(ABORT,'Immutable check admission');END;
