CREATE TABLE mod_families (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    published INTEGER NOT NULL,
    archived INTEGER NOT NULL,
    revision INTEGER NOT NULL CHECK (revision > 0),
    document TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE mod_aliases (
    family_id TEXT NOT NULL REFERENCES mod_families(id) ON DELETE CASCADE,
    alias TEXT NOT NULL,
    PRIMARY KEY (family_id, alias)
);
CREATE INDEX mod_alias_search ON mod_aliases(alias COLLATE NOCASE);
CREATE TABLE mod_tags (
    family_id TEXT NOT NULL REFERENCES mod_families(id) ON DELETE CASCADE,
    tag TEXT NOT NULL,
    PRIMARY KEY (family_id, tag)
);
CREATE INDEX mod_tag_search ON mod_tags(tag);
CREATE TABLE mod_projects (
    id TEXT PRIMARY KEY,
    family_id TEXT NOT NULL REFERENCES mod_families(id),
    name TEXT NOT NULL,
    document TEXT NOT NULL
);
CREATE INDEX mod_project_family ON mod_projects(family_id);
CREATE TABLE mod_listings (
    project_id TEXT NOT NULL REFERENCES mod_projects(id),
    platform TEXT NOT NULL,
    platform_id TEXT NOT NULL,
    url TEXT NOT NULL,
    PRIMARY KEY (platform, platform_id),
    UNIQUE (project_id, platform)
);
CREATE TABLE mod_targets (
    minecraft_version TEXT NOT NULL,
    loader TEXT NOT NULL,
    PRIMARY KEY (minecraft_version, loader)
);
INSERT INTO mod_targets VALUES ('1.20.1', 'FORGE'), ('1.21.1', 'NEOFORGE'), ('1.7.10', 'FORGE'), ('1.12.2', 'FORGE');
CREATE TABLE mod_support (
    project_id TEXT NOT NULL REFERENCES mod_projects(id),
    minecraft_version TEXT NOT NULL,
    loader TEXT NOT NULL,
    availability TEXT NOT NULL,
    maintenance TEXT NOT NULL,
    document TEXT NOT NULL,
    PRIMARY KEY (project_id, minecraft_version, loader),
    FOREIGN KEY (minecraft_version, loader) REFERENCES mod_targets(minecraft_version, loader)
);
CREATE INDEX mod_support_search ON mod_support(minecraft_version, loader, availability);
CREATE TABLE mod_relationships (
    project_id TEXT NOT NULL REFERENCES mod_projects(id),
    related_project_id TEXT NOT NULL REFERENCES mod_projects(id),
    type TEXT NOT NULL,
    scope TEXT NOT NULL,
    document TEXT NOT NULL,
    PRIMARY KEY (project_id, related_project_id, type, scope)
);
CREATE INDEX mod_relationship_incoming ON mod_relationships(related_project_id);
CREATE TABLE mod_evidence (
    family_id TEXT NOT NULL REFERENCES mod_families(id),
    id TEXT NOT NULL,
    document TEXT NOT NULL,
    PRIMARY KEY (family_id, id)
);
CREATE TABLE mod_comparisons (
    family_id TEXT NOT NULL REFERENCES mod_families(id),
    id TEXT NOT NULL,
    document TEXT NOT NULL,
    PRIMARY KEY (family_id, id)
);
CREATE TABLE mod_revisions (
    family_id TEXT NOT NULL REFERENCES mod_families(id),
    revision INTEGER NOT NULL,
    actor TEXT NOT NULL,
    changed_at TEXT NOT NULL,
    reason TEXT NOT NULL,
    request_id TEXT NOT NULL,
    document TEXT NOT NULL,
    PRIMARY KEY (family_id, revision)
);
CREATE TABLE mod_requests (
    request_id TEXT PRIMARY KEY,
    payload_hash TEXT NOT NULL,
    response TEXT NOT NULL,
    created_at TEXT NOT NULL
);
