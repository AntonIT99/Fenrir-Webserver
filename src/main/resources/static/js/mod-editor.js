/* Shared, session-authenticated catalogue editing. All displayed data uses textContent. */
(() => {
    'use strict';
    const $ = id => document.getElementById(id);
    const make = (tag, text, className) => {
        const element = document.createElement(tag);
        if (text !== undefined) element.textContent = text;
        if (className) element.className = className;
        return element;
    };
    const labelFor = value => value.toLowerCase().replaceAll('_', ' ').replace(/^./, c => c.toUpperCase());
    const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content;
    const csrfHeader = document.querySelector('meta[name="csrf-header"]')?.content;
    async function request(path, body) {
        const headers = {Accept: 'application/json'};
        if (body !== undefined) {
            headers['Content-Type'] = 'application/json';
            headers[csrfHeader] = csrfToken;
            if (new TextEncoder().encode(body).length > 1048576) throw new Error('The batch exceeds 1 MiB. Split it into smaller batches.');
        }
        const response = await fetch('/admin/mods/api' + path, {
            method: body === undefined ? 'GET' : 'POST', headers, body, credentials: 'same-origin', redirect: 'error'
        });
        const content = response.headers.get('Content-Type') || '';
        if (!content.includes('application/json')) throw new Error('Your session may have expired. Open the admin page in another tab and sign in before retrying. Your edits are still here.');
        const data = await response.json();
        if (!response.ok) throw new Error(data.message || (response.status === 403 ? 'Your session or permissions changed. Sign in again before retrying.' : 'The request failed.'));
        return data;
    }
    const field = (label, kind = 'text', extra = {}) => ({label, kind, ...extra});
    const options = (label, values) => field(label, 'select', {values});
    const array = (label, singular, fields, initial, help = '') => ({label, kind: 'array', singular, fields, initial, help});
    const object = (label, fields, optional = false) => ({label, kind: 'object', fields, optional});
    const idField = field('Stable ID', 'text', {required: true, max: 100, help: 'Lowercase letters, numbers, hyphens, or underscores. Saved IDs stay fixed.'});
    const notes = field('Notes', 'textarea', {max: 6000});
    const refs = field('Evidence IDs', 'list', {help: 'Separate source IDs with commas or new lines. Add the sources in Research evidence below.'});
    const date = field('Last checked', 'date', {help: 'Required when making a support, maintenance, or parity claim.'});
    const targetFields = {
        minecraftVersion: field('Minecraft version', 'text', {required: true, max: 30, help: 'An exact version, such as 1.20.1 or 1.21.1.'}),
        loader: options('Loader', ['FORGE', 'NEOFORGE', 'FABRIC', 'QUILT'])
    };
    const target = () => ({minecraftVersion: '', loader: 'FORGE'});
    const branchFields = {projectId: field('Project ID', 'text', {required: true}), target: object('Version target', targetFields)};
    const releases = array('Releases', 'release', {
        platformReleaseId: field('Platform release ID', 'text', {required: true, max: 200}),
        platform: options('Platform', ['CURSEFORGE', 'MODRINTH']),
        modVersion: field('Mod version', 'text', {required: true, max: 200}),
        channel: options('Release channel', ['RELEASE', 'BETA', 'ALPHA']),
        publishedAt: field('Published at (UTC)', 'text', {required: true, help: 'ISO timestamp, for example 2026-10-22T12:00:00Z.'}),
        url: field('Release URL', 'url', {required: true, max: 2000})
    }, () => ({platformReleaseId: '', platform: 'MODRINTH', modVersion: '', channel: 'RELEASE', publishedAt: '', url: ''}));
    const support = array('Version support', 'support assessment', {
        target: object('Version target', targetFields),
        availability: options('Availability', ['UNKNOWN', 'RELEASED', 'ANNOUNCED', 'NO_BUILD_FOUND']),
        maintenance: options('Maintenance', ['UNKNOWN', 'ACTIVE', 'FIXES_ONLY', 'DISCONTINUED']),
        notes, checkedAt: date, evidenceIds: refs, releases
    }, () => ({target: target(), availability: 'UNKNOWN', maintenance: 'UNKNOWN', notes: '', checkedAt: null, evidenceIds: [], releases: []}), 'Record Minecraft version and loader with source evidence. Exact mod-release versions are optional.');
    const projectFields = {
        id: idField,
        name: field('Project name', 'text', {required: true, max: 200}),
        description: field('What does this project do?', 'textarea', {required: true, max: 12000}),
        listings: array('Platform listings', 'listing', {
            platform: options('Platform', ['CURSEFORGE', 'MODRINTH']),
            platformId: field('Platform project ID', 'text', {required: true, max: 100}),
            url: field('Project URL', 'url', {required: true, max: 2000})
        }, () => ({platform: 'MODRINTH', platformId: '', url: ''})),
        support,
        relationships: array('Related projects', 'relationship', {
            relatedProjectId: field('Related project ID', 'text', {required: true, help: 'Use a project ID in this family or another saved family.'}),
            type: options('Relationship', ['REQUIRED_DEPENDENCY', 'OPTIONAL_DEPENDENCY', 'ADDON', 'FORK', 'SUCCESSOR', 'ALTERNATIVE', 'LOADER_EQUIVALENT', 'INCOMPATIBILITY']),
            target: object('Limit to a Minecraft version and loader', targetFields, true), notes, evidenceIds: refs
        }, () => ({relatedProjectId: '', type: 'REQUIRED_DEPENDENCY', target: null, notes: '', evidenceIds: []}))
    };
    const emptyProject = () => ({id: '', name: '', description: '', listings: [], support: [], relationships: []});
    const schema = {
        id: idField,
        name: field('Family name', 'text', {required: true, max: 200}),
        description: field('Family description', 'textarea', {required: true, max: 12000}),
        aliases: field('Alternative names', 'list', {help: 'Separate names with commas or new lines.'}),
        tags: field('Themes', 'list', {help: 'Tags such as scifi, furniture, vehicles, or server-utility. Separate with commas.'}),
        published: field('Publish this family for everyone to browse', 'checkbox'),
        archived: field('Archive this family (hide it from public browsing)', 'checkbox'),
        projects: array('Projects', 'project', projectFields, emptyProject, 'Separate the original, forks, revivals, and libraries. Saved project IDs cannot be removed.'),
        evidence: array('Research evidence', 'source', {
            id: idField, url: field('Source URL', 'url', {required: true, max: 2000}),
            claim: field('What claim does this source support?', 'text', {required: true, max: 1000}),
            summary: field('Finding / evidence summary', 'textarea', {required: true, max: 4000}),
            checkedAt: field('Checked on', 'date', {required: true}), uncertain: field('This finding is uncertain or needs review', 'checkbox')
        }, () => ({id: '', url: '', claim: '', summary: '', checkedAt: '', uncertain: true})),
        comparisons: array('Branch comparisons', 'comparison', {
            id: idField, left: object('First branch', branchFields), right: object('Compare with', branchFields),
            parity: options('Feature parity', ['UNKNOWN', 'EQUIVALENT', 'PARTIAL', 'DIVERGENT']), notes, checkedAt: date, evidenceIds: refs
        }, () => ({id: '', left: {projectId: '', target: target()}, right: {projectId: '', target: target()}, parity: 'UNKNOWN', notes: '', checkedAt: null, evidenceIds: []}))
    };

    if ($('mod-editor')) {
        const form = $('mod-editor');
        const family = JSON.parse($('initial-family').value);
        let revision = Number(form.dataset.revision);
        const originalProjectIds = new Set(family.projects.map(p => p.id).filter(Boolean));
        let dirty = false;
        let saving = false;
        let pendingRequest = null;
        const markDirty = () => { dirty = true; pendingRequest = null; $('save-status').textContent = ''; };
        const status = (text, error = false) => { $('save-status').textContent = text; $('save-status').className = 'feedback ' + (error ? 'error' : 'success'); };
        function renderFields(parent, values, fields, context = '') {
            const grid = make('div', undefined, 'editor-grid');
            parent.append(grid);
            Object.entries(fields).forEach(([key, spec]) => {
                if (spec.kind === 'array') {
                    const box = make('section', undefined, 'nested-array');
                    box.append(make('h3', spec.label));
                    if (spec.help) box.append(make('p', spec.help, 'field-help'));
                    const items = make('div', undefined, 'array-items');
                    values[key].forEach((item, index) => {
                        const card = make('div', undefined, 'array-item');
                        const header = make('div', undefined, 'array-heading');
                        header.append(make('h3', `${spec.singular[0].toUpperCase() + spec.singular.slice(1)} ${index + 1}`));
                        const remove = make('button', 'Remove'); remove.type = 'button';
                        const fixed = key === 'projects' && originalProjectIds.has(item.id);
                        remove.disabled = fixed;
                        if (fixed) remove.title = 'Saved project IDs are permanent.';
                        remove.addEventListener('click', () => { values[key].splice(index, 1); markDirty(); render(); });
                        header.append(remove); card.append(header);
                        renderFields(card, item, spec.fields, key);
                        items.append(card);
                    });
                    box.append(items);
                    const add = make('button', '+ Add ' + spec.singular); add.type = 'button';
                    add.addEventListener('click', () => { values[key].push(spec.initial()); markDirty(); render(); });
                    box.append(add); grid.append(box); return;
                }
                if (spec.kind === 'object') {
                    const box = make('div', undefined, 'nested-array');
                    if (spec.optional) {
                        const label = make('label', undefined, 'check');
                        const check = make('input'); check.type = 'checkbox'; check.checked = values[key] !== null;
                        check.addEventListener('change', () => { values[key] = check.checked ? target() : null; markDirty(); render(); });
                        label.append(check, document.createTextNode(spec.label)); box.append(label);
                    } else box.append(make('h3', spec.label));
                    if (values[key] !== null) renderFields(box, values[key], spec.fields, key);
                    grid.append(box); return;
                }
                const label = make('label', undefined, ['textarea', 'list'].includes(spec.kind) ? 'wide' : '');
                let input;
                if (spec.kind === 'select') {
                    input = make('select');
                    spec.values.forEach(value => { const option = make('option', labelFor(value)); option.value = value; input.append(option); });
                } else if (['textarea', 'list'].includes(spec.kind)) {
                    input = make('textarea'); input.rows = spec.kind === 'list' ? 2 : 3;
                } else { input = make('input'); input.type = spec.kind; }
                input.required = !!spec.required;
                if (spec.max) input.maxLength = spec.max;
                if (spec.kind === 'checkbox') { input.checked = values[key]; label.className = 'check'; label.append(input, document.createTextNode(spec.label)); }
                else {
                    label.append(document.createTextNode(spec.label + (spec.required ? ' *' : '')));
                    input.value = spec.kind === 'list' ? values[key].join('\n') : (values[key] ?? '');
                    if (key === 'id') {
                        input.pattern = '[a-z0-9][a-z0-9_-]{0,99}';
                        input.readOnly = (context === 'projects' && originalProjectIds.has(values[key])) || (context === '' && revision > 0);
                    }
                    label.append(input);
                }
                if (spec.help) label.append(make('span', spec.help, 'field-help'));
                input.addEventListener('input', () => {
                    values[key] = spec.kind === 'checkbox' ? input.checked : spec.kind === 'list'
                        ? input.value.split(/[,\n]/).map(v => v.trim()).filter(Boolean)
                        : spec.kind === 'date' && !input.value && !spec.required ? null : input.value;
                    markDirty();
                });
                grid.append(label);
            });
        }
        function render() {
            const root = $('family-fields'); root.replaceChildren();
            const basics = make('section', undefined, 'panel'); basics.id = 'family-essentials'; basics.append(make('h2', 'Family essentials'));
            renderFields(basics, family, Object.fromEntries(Object.entries(schema).filter(([key]) => !['projects', 'evidence', 'comparisons'].includes(key))));
            root.append(basics);
            for (const key of ['projects', 'evidence', 'comparisons']) {
                const section = make('section', undefined, 'panel'); section.id = 'family-' + key;
                renderFields(section, family, {[key]: schema[key]}); root.append(section);
            }
        }
        function batchBody() {
            const reason = $('change-reason').value;
            const changes = [{expectedRevision: revision, family}];
            const content = JSON.stringify({reason, changes});
            // Keep the request ID on retry if a response was lost after committing.
            if (!pendingRequest || pendingRequest.content !== content) pendingRequest = {content, id: crypto.randomUUID()};
            return JSON.stringify({requestId: pendingRequest.id, reason, changes});
        }
        async function submit(dryRun) {
            if (saving || !form.reportValidity()) return;
            saving = true;
            const controls = form.querySelectorAll('button,input,select,textarea'); controls.forEach(b => { b.dataset.wasDisabled = String(b.disabled); b.disabled = true; });
            try {
                status(dryRun ? 'Validating the complete family…' : 'Saving your changes…');
                const result = await request('/batches' + (dryRun ? '?dryRun=true' : ''), batchBody());
                if (dryRun) status('Validation passed. No changes were saved.');
                else { dirty = false; window.location.assign('/admin/mods/' + result.entries[0].family.id + '/edit?saved=true'); }
            } catch (error) { status(error.message + ' Your edits have been kept on this page.', true); }
            finally { saving = false; controls.forEach(b => { b.disabled = b.dataset.wasDisabled === 'true'; }); }
        }
        form.addEventListener('submit', event => { event.preventDefault(); submit(false); });
        $('validate-family').addEventListener('click', () => submit(true));
        $('change-reason').addEventListener('input', markDirty);
        window.addEventListener('beforeunload', event => { if (dirty) { event.preventDefault(); event.returnValue = ''; } });
        render();
        if (new URLSearchParams(location.search).get('saved') === 'true') status('Family saved. You are editing the latest revision.');
    }

    if ($('import-json')) {
        let validatedBody = null;
        let busy = false;
        const area = $('import-json');
        const status = (text, error = false) => { $('import-status').textContent = text; $('import-status').className = 'feedback ' + (error ? 'error' : 'success'); };
        const invalidate = () => { validatedBody = null; $('commit-import').disabled = true; $('import-preview').replaceChildren(); };
        area.addEventListener('input', invalidate);
        $('import-file').addEventListener('change', async event => {
            invalidate();
            const file = event.target.files[0]; if (!file) return;
            if (file.size > 1048576) { status('The file exceeds 1 MiB.', true); return; }
            area.value = await file.text(); status('File loaded. Preview the batch before importing.');
        });
        async function apply(dryRun) {
            if (busy) return;
            const body = area.value;
            if (!dryRun && body !== validatedBody) { invalidate(); status('Preview this batch again before importing.', true); return; }
            busy = true; $('preview-import').disabled = true; $('commit-import').disabled = true;
            try {
                JSON.parse(body);
                const result = await request('/batches' + (dryRun ? '?dryRun=true' : ''), body);
                if (dryRun && area.value !== body) { invalidate(); status('The batch changed during preview. Preview the current content again.', true); return; }
                const preview = $('import-preview'); preview.replaceChildren();
                result.entries.forEach(entry => {
                    const row = make('div', undefined, 'import-change');
                    row.append(make('strong', entry.family.name), make('p', `${entry.family.id} · revision ${entry.revision} · ${entry.family.projects.length} projects · ${entry.family.published ? 'Published' : 'Draft'}${entry.family.archived ? ' · Archived' : ''}`));
                    if (!dryRun) { const link = make('a', 'Open saved family'); link.href = '/admin/mods/' + entry.family.id + '/edit'; row.append(link); }
                    preview.append(row);
                });
                validatedBody = dryRun ? body : null;
                status(dryRun ? 'Preview passed. No changes saved. Review the families below, then import.' : 'Batch imported successfully. All changes were saved together.');
            } catch (error) { invalidate(); status(error.message, true); }
            finally { busy = false; $('preview-import').disabled = false; $('commit-import').disabled = validatedBody === null; }
        }
        $('preview-import').addEventListener('click', () => apply(true));
        $('commit-import').addEventListener('click', () => apply(false));
        $('export-catalogue').addEventListener('click', async () => {
            const button = $('export-catalogue'); button.disabled = true; $('export-status').textContent = 'Preparing export…';
            try {
                const entries = [];
                for (let offset = 0; ; offset += 100) {
                    const page = await request('/export?offset=' + offset);
                    entries.push(...page.entries);
                    if (offset + page.entries.length >= page.total || !page.entries.length) break;
                }
                const blob = new Blob([JSON.stringify({format: 'mod-catalogue-export-v1', exportedAt: new Date().toISOString(), entries}, null, 2)], {type: 'application/json'});
                const url = URL.createObjectURL(blob); const link = make('a'); link.href = url; link.download = 'minecraft-mod-catalogue.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
                $('export-status').textContent = entries.length + ' families exported.';
            } catch (error) { $('export-status').textContent = error.message; }
            finally { button.disabled = false; }
        });
    }
})();
