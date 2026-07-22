/**
 * Report SQL Add-on
 *
 * Injected by the report server into generated report.html only when the
 * report contains SQL test data (testType === 'sql' or ctpProvenance).
 * Shell reports never load this file, so their rendering stays unchanged.
 *
 * Adds:
 * - a CTP provenance line in the report header (develop base + merged PRs)
 * - an artifact panel per failed/flaky SQL result with tabs for
 *   Diff / Actual / Expected / Case SQL / Console (+ Warm console and
 *   Core list when present), fetched from the existing log endpoints
 * - client-side colorization of unified diffs (no library)
 * - executionEnv chips ("warm agent" / "fresh container") on artifacts
 */

(function initSqlReportAddon() {
    var SQL_ARTIFACT_TABS = [
        { type: 'answer_diff', label: 'Diff' },
        { type: 'actual_result', label: 'Actual' },
        { type: 'expected_answer', label: 'Expected' },
        { type: 'case_source', label: 'Case SQL' },
        { type: 'console', label: 'Console' },
        { type: 'warm_console', label: 'Warm console' },
        { type: 'core_list', label: 'Core list' }
    ];
    var EXECUTION_ENV_LABELS = {
        warm_agent: 'warm agent',
        fresh_container: 'fresh container'
    };
    var EXECUTION_ENV_TOOLTIP = 'Execution environment of this run. Failures observed in a warm agent are automatically re-verified in a fresh container.';

    var sqlIndex = {}; // testName -> full commit -> result entry
    var currentSqlDetail = null; // { testName, commit, entry, attempts }

    function getReportData() {
        if (typeof rawData !== 'undefined') return rawData;
        if (typeof reportData !== 'undefined') return reportData;
        return null;
    }

    function getSqlRequestId() {
        var data = getReportData();
        if (data && (data.requestId || data.taskId)) return data.requestId || data.taskId;
        try {
            var params = new URLSearchParams(window.location.search);
            return params.get('id') || '';
        } catch (e) {
            return '';
        }
    }

    function escapeSqlHtml(text) {
        return String(text == null ? '' : text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function shortShaSql(sha) {
        var text = String(sha || '');
        return text.substring(0, Math.min(7, text.length));
    }

    function normalizeAttemptSql(attempt) {
        var n = parseInt(attempt, 10);
        return (Number.isFinite(n) && n > 0) ? n : 1;
    }

    function buildSqlIndex(data) {
        var results = (data && Array.isArray(data.results)) ? data.results : [];
        results.forEach(function(entry) {
            if (!entry || entry.testType !== 'sql' || !entry.test || !entry.commit) return;
            if (!sqlIndex[entry.test]) sqlIndex[entry.test] = {};
            sqlIndex[entry.test][entry.commit] = entry;
        });
    }

    function getSqlEntry(testName, commit) {
        var byCommit = sqlIndex[testName];
        if (!byCommit) return null;
        if (byCommit[commit]) return byCommit[commit];
        // Allow short-sha lookups from callers holding a 7-char commit
        var commitText = String(commit || '');
        if (!commitText) return null;
        var fullCommit = Object.keys(byCommit).find(function(c) {
            return c.indexOf(commitText) === 0;
        });
        return fullCommit ? byCommit[fullCommit] : null;
    }

    function getEntryArtifacts(entry) {
        if (Array.isArray(entry.artifacts)) {
            return entry.artifacts.filter(function(m) { return m && m.artifactType && m.logFileName; });
        }
        // Fallback for payloads where artifacts were not split out server-side
        var meta = Array.isArray(entry.attemptLogMetadata) ? entry.attemptLogMetadata : [];
        return meta.filter(function(m) { return m && m.artifactType && m.logFileName; });
    }

    function getEntryAttemptLogs(entry) {
        var meta = Array.isArray(entry.attemptLogMetadata) ? entry.attemptLogMetadata : [];
        return meta.filter(function(m) { return m && m.logFileName && !m.artifactType; });
    }

    function findArtifact(artifacts, type, attempt) {
        if (type === 'case_source') {
            // The case source is attempt-independent (sql_case_<commit7>_<testName>.sql)
            return artifacts.find(function(m) { return m.artifactType === 'case_source'; }) || null;
        }
        return artifacts.find(function(m) {
            return m.artifactType === type && normalizeAttemptSql(m.attempt) === attempt;
        }) || null;
    }

    function findAttemptLog(attemptLogs, attempt) {
        return attemptLogs.find(function(m) { return normalizeAttemptSql(m.attempt) === attempt; }) || null;
    }

    function collectAttemptsSql(entry) {
        var seen = {};
        getEntryAttemptLogs(entry).forEach(function(m) { seen[normalizeAttemptSql(m.attempt)] = true; });
        getEntryArtifacts(entry).forEach(function(m) {
            if (m.artifactType !== 'case_source') seen[normalizeAttemptSql(m.attempt)] = true;
        });
        var attempts = Object.keys(seen).map(Number).sort(function(a, b) { return a - b; });
        return attempts.length > 0 ? attempts : [1];
    }

    function defaultAttemptSql(entry, attempts) {
        // Prefer the first attempt that produced an answer diff (a verified failure)
        var withDiff = getEntryArtifacts(entry)
            .filter(function(m) { return m.artifactType === 'answer_diff'; })
            .map(function(m) { return normalizeAttemptSql(m.attempt); })
            .sort(function(a, b) { return a - b; });
        return withDiff.length > 0 ? withDiff[0] : attempts[0];
    }

    function injectSqlStyles() {
        if (document.getElementById('sql-addon-styles')) return;
        var style = document.createElement('style');
        style.id = 'sql-addon-styles';
        style.textContent = [
            '.sql-ctp-provenance { margin-top: 1rem; padding: 0.5rem 0.75rem; font-family: ui-monospace, "JetBrains Mono", "Fira Code", "SF Mono", Consolas, monospace; font-size: 0.8125rem; font-weight: 600; color: #4338ca; background: linear-gradient(135deg, rgba(99, 102, 241, 0.08), rgba(139, 92, 246, 0.08)); border: 1px solid rgba(99, 102, 241, 0.2); border-radius: 0.5rem; }',
            '.sql-attempt-row { display: flex; flex-wrap: wrap; gap: 0.375rem; margin-bottom: 0.75rem; }',
            '.sql-attempt-btn, .sql-artifact-tab { padding: 0.375rem 0.75rem; border: 1px solid #d1d5db; border-radius: 0.375rem; background: #f9fafb; color: #374151; cursor: pointer; font-size: 0.8125rem; font-family: inherit; }',
            '.sql-attempt-btn.active, .sql-artifact-tab.active { border-color: #6366f1; background: rgba(99, 102, 241, 0.1); color: #4338ca; font-weight: 600; }',
            '.sql-artifact-tabs { display: flex; flex-wrap: wrap; gap: 0.375rem; margin: 0.75rem 0; padding-bottom: 0.5rem; border-bottom: 2px solid #e5e7eb; }',
            '.sql-artifact-file { font-family: ui-monospace, "JetBrains Mono", "Fira Code", "SF Mono", Consolas, monospace; font-size: 0.75rem; color: #6b7280; margin-bottom: 0.375rem; }',
            '.sql-artifact-content { background: #f3f4f6; padding: 1rem; border-radius: 0.5rem; overflow: auto; white-space: pre-wrap; max-height: 400px; font-size: 0.8125rem; line-height: 1.5; }',
            '.sql-diff-add { background: rgba(16, 185, 129, 0.15); color: #065f46; }',
            '.sql-diff-del { background: rgba(239, 68, 68, 0.15); color: #991b1b; }',
            '.sql-diff-hunk { background: rgba(59, 130, 246, 0.15); color: #1e40af; font-weight: 600; }',
            '.sql-env-chip { display: inline-block; margin-left: 0.5rem; padding: 0.0625rem 0.5rem; border-radius: 999px; font-size: 0.6875rem; font-weight: 600; vertical-align: middle; cursor: help; }',
            '.sql-env-chip.warm_agent { background: #fef3c7; color: #92400e; border: 1px solid rgba(245, 158, 11, 0.4); }',
            '.sql-env-chip.fresh_container { background: #e0f2fe; color: #0c4a6e; border: 1px solid rgba(14, 165, 233, 0.4); }'
        ].join('\n');
        document.head.appendChild(style);
    }

    function renderCtpProvenance(data) {
        var prov = data && data.ctpProvenance;
        if (!prov || !prov.baseSha || document.getElementById('sqlCtpProvenance')) return;
        var grid = document.querySelector('.test-execution-modern .execution-grid');
        var host = grid ? grid.parentNode : document.querySelector('.header');
        if (!host) return;

        var text = 'CTP: develop @' + shortShaSql(prov.baseSha);
        // Legacy reports may still carry layered PRs; show them only if present.
        (Array.isArray(prov.prs) ? prov.prs : []).forEach(function(pr) {
            if (!pr || pr.pr == null) return;
            text += ' + PR#' + pr.pr + ' @' + shortShaSql(pr.sha);
        });
        text += ' (built-in single-case runner)';

        var line = document.createElement('div');
        line.id = 'sqlCtpProvenance';
        line.className = 'sql-ctp-provenance';
        line.textContent = text;
        line.title = 'CTP version used to execute the SQL test cases (plain develop; single-case execution via builder-tester\'s own runner)';
        if (grid && grid.nextSibling) {
            host.insertBefore(line, grid.nextSibling);
        } else {
            host.appendChild(line);
        }
    }

    function statusColorsSql(status) {
        if (typeof getStatusColors === 'function') return getStatusColors(status);
        return { border: '#6b7280', background: '#f9fafb', text: '#374151' };
    }

    function renderEnvChipSql(meta) {
        if (!meta || !meta.executionEnv || !EXECUTION_ENV_LABELS[meta.executionEnv]) return '';
        return '<span class="sql-env-chip ' + escapeSqlHtml(meta.executionEnv) + '" title="' + escapeSqlHtml(EXECUTION_ENV_TOOLTIP) + '">' + escapeSqlHtml(EXECUTION_ENV_LABELS[meta.executionEnv]) + '</span>';
    }

    function renderEnvChipsSql(artifacts, attempt) {
        var seen = {};
        var html = '';
        artifacts.forEach(function(m) {
            if (m.artifactType !== 'case_source' && normalizeAttemptSql(m.attempt) !== attempt) return;
            if (!m.executionEnv || seen[m.executionEnv] || !EXECUTION_ENV_LABELS[m.executionEnv]) return;
            seen[m.executionEnv] = true;
            html += renderEnvChipSql(m);
        });
        return html;
    }

    function renderSqlDiff(text) {
        return String(text).split('\n').map(function(line) {
            var cls = '';
            if (line.lastIndexOf('@@', 0) === 0) cls = 'sql-diff-hunk';
            else if (line.lastIndexOf('+', 0) === 0) cls = 'sql-diff-add';
            else if (line.lastIndexOf('-', 0) === 0) cls = 'sql-diff-del';
            if (!cls) return escapeSqlHtml(line);
            return '<span class="' + cls + '">' + escapeSqlHtml(line) + '</span>';
        }).join('\n');
    }

    function buildSqlTabs(entry, attempt) {
        var artifacts = getEntryArtifacts(entry);
        var attemptLogs = getEntryAttemptLogs(entry);
        var tabs = [];
        SQL_ARTIFACT_TABS.forEach(function(def) {
            if (def.type === 'console') {
                var logMeta = findAttemptLog(attemptLogs, attempt);
                if (logMeta) tabs.push({ label: def.label, type: def.type, file: logMeta.logFileName, meta: logMeta });
                return;
            }
            var artifact = findArtifact(artifacts, def.type, attempt);
            if (artifact) tabs.push({ label: def.label, type: def.type, file: artifact.logFileName, meta: artifact });
        });
        return tabs;
    }

    function openSqlTab(tab) {
        var pane = document.getElementById('sqlArtifactPane');
        if (!pane || !tab) return;
        pane.innerHTML = '<p style="color: #6b7280;">Loading ' + escapeSqlHtml(tab.file) + '...</p>';
        fetch('/api/log/' + encodeURIComponent(getSqlRequestId()) + '/tests/' + encodeURIComponent(tab.file))
            .then(function(response) {
                if (!response.ok) throw new Error('HTTP ' + response.status);
                return response.text();
            })
            .then(function(text) {
                var header = '<div class="sql-artifact-file">' + escapeSqlHtml(tab.file) + renderEnvChipSql(tab.meta) + '</div>';
                if (tab.type === 'answer_diff') {
                    pane.innerHTML = header + '<pre class="sql-artifact-content">' + renderSqlDiff(text) + '</pre>';
                } else {
                    pane.innerHTML = header + '<pre class="sql-artifact-content">' + escapeSqlHtml(text) + '</pre>';
                }
            })
            .catch(function(err) {
                pane.innerHTML = '<p style="color: #991b1b;">Failed to load ' + escapeSqlHtml(tab.file) + ': ' + escapeSqlHtml(err && err.message ? err.message : String(err)) + '</p>';
            });
    }

    function renderSqlDetail(attempt) {
        var modalBody = document.getElementById('modalBody');
        if (!modalBody || !currentSqlDetail) return;

        var entry = currentSqlDetail.entry;
        var attempts = currentSqlDetail.attempts;
        var testName = currentSqlDetail.testName;
        var commitShort = shortShaSql(currentSqlDetail.commit);
        var testScript = testName.substring(testName.lastIndexOf('/') + 1);
        var artifacts = getEntryArtifacts(entry);
        var attemptLogs = getEntryAttemptLogs(entry);
        var status = (entry.status || 'unknown').toString();
        var colors = statusColorsSql(status);

        var html = '<div style="padding: 1.5rem;">';
        html += '<h3>SQL Test: ' + escapeSqlHtml(testScript) + ' @ ' + escapeSqlHtml(commitShort) + '</h3>';
        html += '<p style="font-size: 0.9em; color: #6b7280; margin: 0.5rem 0 1rem 0;"><strong>Full Path:</strong> ' + escapeSqlHtml(testName) + '</p>';
        html += '<p style="margin-bottom: 0.75rem;"><strong>Status:</strong> ';
        html += '<span style="color: ' + colors.text + '; background: ' + colors.background + '; border: 1px solid ' + colors.border + '; padding: 0.125rem 0.5rem; border-radius: 0.375rem; font-weight: 600;">' + escapeSqlHtml(status.toUpperCase()) + '</span>';
        html += renderEnvChipsSql(artifacts, attempt);
        html += '</p>';

        if (attempts.length > 1) {
            html += '<div class="sql-attempt-row">';
            attempts.forEach(function(a) {
                var logMeta = findAttemptLog(attemptLogs, a);
                var attemptStatus = (logMeta && logMeta.status) ? ' - ' + String(logMeta.status).toUpperCase() : '';
                html += '<button type="button" class="sql-attempt-btn' + (a === attempt ? ' active' : '') + '" data-sql-attempt="' + a + '">Attempt ' + a + escapeSqlHtml(attemptStatus) + '</button>';
            });
            html += '</div>';
        }

        var tabs = buildSqlTabs(entry, attempt);
        html += '<div class="sql-artifact-tabs">';
        tabs.forEach(function(tab, idx) {
            html += '<button type="button" class="sql-artifact-tab" data-sql-tab="' + idx + '">' + escapeSqlHtml(tab.label) + '</button>';
        });
        html += '</div>';
        html += '<div id="sqlArtifactPane">';
        if (tabs.length === 0) {
            html += '<p style="color: #6b7280;">No logs or artifacts are available for this attempt.</p>';
        }
        html += '</div>';
        html += '</div>';

        modalBody.innerHTML = html;

        modalBody.querySelectorAll('button[data-sql-attempt]').forEach(function(btn) {
            btn.addEventListener('click', function() {
                renderSqlDetail(normalizeAttemptSql(btn.getAttribute('data-sql-attempt')));
            });
        });
        var tabButtons = modalBody.querySelectorAll('button[data-sql-tab]');
        tabButtons.forEach(function(btn) {
            btn.addEventListener('click', function() {
                tabButtons.forEach(function(b) { b.classList.remove('active'); });
                btn.classList.add('active');
                openSqlTab(tabs[parseInt(btn.getAttribute('data-sql-tab'), 10)]);
            });
        });

        if (tabs.length > 0) {
            tabButtons[0].classList.add('active');
            openSqlTab(tabs[0]);
        }
    }

    function showSqlTestDetail(testName, commit) {
        var entry = getSqlEntry(testName, commit);
        if (!entry) return;
        var attempts = collectAttemptsSql(entry);
        currentSqlDetail = { testName: testName, commit: commit, entry: entry, attempts: attempts };

        var modalTitle = document.getElementById('modalTitle');
        if (modalTitle) modalTitle.textContent = 'SQL Test Details';
        renderSqlDetail(defaultAttemptSql(entry, attempts));
        var modal = document.getElementById('detailModal');
        if (modal) modal.style.display = 'block';
    }

    function wireSqlAddon() {
        var data = getReportData();
        if (!data) return;
        buildSqlIndex(data);
        if (Object.keys(sqlIndex).length === 0 && !data.ctpProvenance) return;
        injectSqlStyles();
        renderCtpProvenance(data);

        // Route SQL results to the artifact panel; everything else keeps the
        // existing shell log viewer behavior.
        var originalViewTestLog = window.viewTestLog;
        window.viewTestLog = function(testName, commit) {
            if (getSqlEntry(testName, commit)) {
                showSqlTestDetail(testName, commit);
                return;
            }
            if (typeof originalViewTestLog === 'function') {
                return originalViewTestLog(testName, commit);
            }
        };
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', wireSqlAddon);
    } else {
        wireSqlAddon();
    }
})();
