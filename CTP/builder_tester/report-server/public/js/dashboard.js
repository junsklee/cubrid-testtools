        let commits = [];
        let selectedCommits = new Set();
        let commitMode = 'select';
        let workers = [];  // Initialize empty, will be populated on load
        let currentPage = 1;
        let isLoadingCommits = false;
        let builderCommitBuildMode = null;
        let baselineModeTouched = false;
        // Custom Script attachments (client-side buffer)
        let customScriptAttachments = []; // { name, size, targetPath, contentBase64 }
        let customScriptAttachmentsLoading = 0;

        function onBuildModeRadioChange(event) {
            const target = event && event.target;
            if (!target || target.name !== 'buildMode') return;
            baselineModeTouched = true;
            updateBaselineDisplay();
        }

        // Bind at parse-time (not inside DOMContentLoaded) so it still works even if some init code throws.
        // Capture phase makes it resilient across browsers and DOM structures.
        document.addEventListener('change', onBuildModeRadioChange, true);
        document.addEventListener('input', onBuildModeRadioChange, true);

        // Initialize
        document.addEventListener('DOMContentLoaded', () => {
            // Get and set default worker IP
            fetch('/api/local-ip')
                .then(res => res.json())
                .then(data => {
                    if (data.ip) {
                        workers = [`${data.ip}`];
                        renderWorkers();
                    }
                })
                .catch(() => {
                    workers = ['localhost'];
                    renderWorkers();
                });
            
            loadCommits();
            setupEventListeners();
            loadRecentReports();
            
            // Check for active sessions on page load
            checkForActiveSessions();
            refreshBuildQueue();
            updateBaselineDisplay();
            refreshBuilderCommitMode();
            applyBuildOnlyState();
        });

        // Latest queue snapshot (load-first: refreshed on page load, manual refresh, and background polling)
        // Builder runs at most one request at a time; the rest are queued.
        let lastQueueSnapshot = { running: null, queued: [] };
        // Transient UI state: show the most recently finished request in the queue list until a user action clears it.
        let transientFinishedTaskId = null;
        let transientFinishedAtMs = 0;
        let queueAutoRefreshTimer = null;
        const requestMetaCache = new Map(); // taskId -> request.json payload (best-effort)

        function buildQueueSnapshot(activeTaskIds, queuedTaskIds) {
            const active = Array.isArray(activeTaskIds) ? activeTaskIds.filter(Boolean) : [];
            const queued = Array.isArray(queuedTaskIds) ? queuedTaskIds.filter(Boolean) : [];

            // Stable order: request IDs embed timestamps, so lexicographic sort matches chronology.
            const activeSorted = [...new Set(active)].sort();
            const queuedOrdered = queued; // LinkedBlockingQueue iteration is insertion-ordered

            // Only one request runs at a time.
            const running = activeSorted.length > 0 ? activeSorted[0] : null;

            return {
                running,
                queued: queuedOrdered
            };
        }

        async function refreshBuildQueue(currentId) {
            const summaryEl = document.getElementById('build-queue-summary');
            const itemsEl = document.getElementById('build-queue-items');
            if (!summaryEl || !itemsEl) return;

            try {
                const prevRunning = lastQueueSnapshot ? lastQueueSnapshot.running : null;
                const resp = await fetch('/api/builder/status');
                if (!resp.ok) {
                    throw new Error(`Queue status unavailable: ${resp.status} ${resp.statusText}`);
                }
                const data = await resp.json();

                const activeIds = Array.isArray(data.activeTasks) ? data.activeTasks.map(t => t.taskId).filter(Boolean) : [];
                const queuedIds = Array.isArray(data.queuedTaskIds) ? data.queuedTaskIds : [];

                const newSnap = buildQueueSnapshot(activeIds, queuedIds);
                lastQueueSnapshot = newSnap;

                // Detect completion from queue transitions:
                // if previously we had a running request and it's no longer running AND not queued, consider it finished.
                if (prevRunning && prevRunning !== newSnap.running) {
                    const stillQueued = Array.isArray(newSnap.queued) && newSnap.queued.includes(prevRunning);
                    if (!stillQueued) {
                        transientFinishedTaskId = prevRunning;
                        transientFinishedAtMs = Date.now();

                        // If the status monitor is currently viewing the finished task, refresh it once so it flips to Completed.
                        try {
                            const currentViewId = sessionStorage.getItem('viewTaskId') || sessionStorage.getItem('activeTaskId');
                            if (currentViewId && currentViewId === prevRunning && typeof window.__pollStatus === 'function') {
                                window.__pollStatus(prevRunning);
                            }
                        } catch (e) {
                            // ignore
                        }
                    }
                }

                const pinnedId = sessionStorage.getItem('activeTaskId');
                const viewingId = currentId || sessionStorage.getItem('viewTaskId') || pinnedId;

                const runningCount = lastQueueSnapshot.running ? 1 : 0;
                const queuedCount = Array.isArray(lastQueueSnapshot.queued) ? lastQueueSnapshot.queued.length : 0;
                summaryEl.textContent = `Running: ${runningCount} | Queued: ${queuedCount}`;

                const items = [];
                const shouldShowFinished = !!transientFinishedTaskId &&
                    transientFinishedTaskId !== lastQueueSnapshot.running &&
                    !(Array.isArray(lastQueueSnapshot.queued) && lastQueueSnapshot.queued.includes(transientFinishedTaskId));

                if (!lastQueueSnapshot.running && queuedCount === 0 && !shouldShowFinished) {
                    itemsEl.innerHTML = '<div style="color: var(--text-secondary);">No active or queued builds.</div>';
                    return lastQueueSnapshot;
                }

                const renderItem = (id, state, subtitle) => {
                    const badges = [];
                    if (state === 'running') badges.push('<span class="queue-badge running">RUNNING</span>');
                    else if (state === 'finished') badges.push('<span class="queue-badge finished">FINISHED</span>');
                    else badges.push('<span class="queue-badge queued">QUEUED</span>');
                    
                    if (id && id === viewingId) badges.push('<span class="queue-badge viewing">VIEWING</span>');
                    if (id && pinnedId && id === pinnedId) badges.push('<span class="queue-badge pinned">PINNED</span>');
                    
                    return `
                        <div class="queue-item" data-task-id="${id}" data-queue-state="${state}">
                            <div class="queue-left">
                                <div class="queue-id">${id}</div>
                                <div class="queue-subtitle">${subtitle}</div>
                            </div>
                            <div class="queue-badges">${badges.join('')}</div>
                        </div>
                    `;
                };

                if (shouldShowFinished) {
                    items.push(renderItem(
                        transientFinishedTaskId,
                        'finished',
                        'Finished (temporary). Press Refresh Status or click another request to clear.'
                    ));
                }
                if (lastQueueSnapshot.running) {
                    items.push(renderItem(lastQueueSnapshot.running, 'running', 'Currently running on builder'));
                }
                // True queued tasks (position based ONLY on queued list)
                if (Array.isArray(lastQueueSnapshot.queued)) {
                    lastQueueSnapshot.queued.forEach((id, idx) => {
                        items.push(renderItem(id, 'queued', `Queue position ${idx + 1}`));
                    });
                }

                itemsEl.innerHTML = items.join('');

                // Allow clicking queue items to switch the monitor view (if monitoring is active)
                itemsEl.querySelectorAll('.queue-item').forEach(el => {
                    el.addEventListener('click', async () => {
                        const id = el.getAttribute('data-task-id');
                        // Clear transient finished marker only when switching to a different request.
                        if (transientFinishedTaskId && id && id !== transientFinishedTaskId) {
                            transientFinishedTaskId = null;
                            transientFinishedAtMs = 0;
                        }
                        if (window.switchMonitorTask && id) {
                            await window.switchMonitorTask(id);
                            await refreshBuildQueue(id);
                        } else {
                            // If no monitor active yet, try to bootstrap one from request.json
                            try {
                                const resp = await fetch(`/api/log-root/${id}/request.json`);
                                let req = { taskId: id, requestId: id, commits: [], tests: [] };
                                if (resp.ok) {
                                    const text = await resp.text();
                                    req = JSON.parse(text);
                                    req.taskId = req.taskId || id;
                                    req.requestId = req.requestId || id;
                                    if (!Array.isArray(req.commits)) req.commits = [];
                                    if (!Array.isArray(req.tests)) req.tests = [];
                                }
                                startStatusMonitoring(req);
                                showToast('Monitoring build from queue. Press Refresh to cycle to the next build.', 'info');
                            } catch (e) {
                                showToast('Could not load request details for this queue item.', 'error');
                            }
                        }
                    });
                });

                // Background auto-refresh while something is running (low frequency to limit load)
                if (queueAutoRefreshTimer) {
                    clearTimeout(queueAutoRefreshTimer);
                    queueAutoRefreshTimer = null;
                }
                const hasActivity = !!lastQueueSnapshot.running;
                if (hasActivity) {
                    queueAutoRefreshTimer = setTimeout(() => {
                        refreshBuildQueue(sessionStorage.getItem('viewTaskId') || sessionStorage.getItem('activeTaskId'));
                    }, 10000);
                }

                return lastQueueSnapshot;
            } catch (e) {
                summaryEl.textContent = 'Build queue unavailable';
                itemsEl.innerHTML = `<div style="color: var(--error);">${escapeHtml(e && e.message ? e.message : String(e))}</div>`;
                return lastQueueSnapshot;
            }
        }

        // Setup event listeners
        function setupEventListeners() {
            // Enter key to add worker
            document.getElementById('newWorkerInput').addEventListener('keypress', (e) => {
                if (e.key === 'Enter') {
                    addWorker();
                }
            });

            // Auto-validate callback URL
            document.getElementById('callbackUrl').addEventListener('blur', validateCallbackUrl);
            
            // Update commit count when manual input changes
            document.getElementById('manualCommitInput').addEventListener('input', updateCommitCount);
            // PR input updates count if present
            const prInput = document.getElementById('prNumberInput');
            if (prInput) prInput.addEventListener('input', updateCommitCount);

            // Custom Script attachments
            const attachInput = document.getElementById('customScriptAttachments');
            if (attachInput) {
                attachInput.addEventListener('change', onCustomScriptAttachmentsSelected);
            }

            const buildOnlyToggle = document.getElementById('buildOnlyToggle');
            if (buildOnlyToggle) {
                buildOnlyToggle.addEventListener('change', () => {
                    applyBuildOnlyState();
                });
            }
        }

        function setSectionDisabled(section, disabled) {
            if (!section) return;
            section.classList.toggle('section-disabled', !!disabled);
        }

        function isBuildOnlyEnabled() {
            const toggle = document.getElementById('buildOnlyToggle');
            return !!(toggle && toggle.checked);
        }

        function isBuildOnlyAvailable() {
            return commitMode !== 'custom';
        }

        function isBuildOnlyActive() {
            return isBuildOnlyAvailable() && isBuildOnlyEnabled();
        }

        function applyTestCasesDisabledState() {
            const testsInput = document.getElementById('testsInput');
            const testsInputLabel = document.getElementById('testsInputLabel');
            if (!testsInput) return;

            const buildOnly = isBuildOnlyActive();
            const isCustomMode = commitMode === 'custom';
            const disabled = buildOnly || isCustomMode;

            testsInput.disabled = disabled;
            testsInput.style.opacity = disabled ? '0.5' : '1';
            testsInput.style.cursor = disabled ? 'not-allowed' : 'text';

            if (isCustomMode) {
                testsInput.placeholder = 'Test cases are configured in Custom Script section above';
                testsInput.value = '';
            } else if (buildOnly) {
                testsInput.placeholder = 'Build-only mode skips tests';
            } else {
                testsInput.placeholder = 'Enter test case paths (one per line)\nSupported prefixes: shell/, shell_heavy/, shell_perf/\nExample:\nshell/_05_addition/cubridsus1961/cases/cubridsus1961.sh';
            }

            if (testsInputLabel) {
                testsInputLabel.style.opacity = disabled ? '0.5' : '1';
            }
        }

        function applyBuildOnlyState() {
            const section = document.getElementById('buildOnlySection');
            const toggle = document.getElementById('buildOnlyToggle');
            const available = isBuildOnlyAvailable();

            if (section) {
                section.style.display = available ? 'block' : 'none';
            }
            if (toggle) {
                toggle.disabled = !available;
                if (!available && toggle.checked) {
                    toggle.checked = false;
                }
            }

            const enabled = isBuildOnlyActive();
            const fields = document.getElementById('buildOnlyFields');
            if (fields) {
                fields.style.display = enabled ? 'block' : 'none';
            }

            setSectionDisabled(document.getElementById('workerSection'), enabled);
            setSectionDisabled(document.getElementById('callbackUrlGroup'), enabled);
            setSectionDisabled(document.getElementById('testCasesSection'), enabled);

            const callbackInput = document.getElementById('callbackUrl');
            if (callbackInput) {
                callbackInput.disabled = enabled;
            }

            const workerInput = document.getElementById('newWorkerInput');
            if (workerInput) {
                workerInput.disabled = enabled;
            }

            const buildOnlyInputs = [
                document.getElementById('buildUploadHost'),
                document.getElementById('buildUploadPort'),
                document.getElementById('buildUploadUser'),
                document.getElementById('buildUploadPassword'),
                document.getElementById('buildUploadRemoteDir')
            ];
            buildOnlyInputs.forEach(input => {
                if (input) input.disabled = !enabled;
            });

            applyBuildOnlyState();
        }

        function arrayBufferToBase64(buffer) {
            // Convert ArrayBuffer -> base64 (safe for binary). Size is limited by UI guardrails.
            let binary = '';
            const bytes = new Uint8Array(buffer);
            const chunkSize = 0x8000;
            for (let i = 0; i < bytes.length; i += chunkSize) {
                binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
            }
            return btoa(binary);
        }

        function validateAttachmentTargetPath(p) {
            const v = (p || '').trim();
            if (!v) return 'Path is required';
            if (v.startsWith('/')) return 'Must be a relative path (no leading /)';
            if (v.includes('\\')) return 'Backslashes are not allowed';
            if (v.includes('\0')) return 'NUL byte not allowed';
            if (/\s/.test(v)) return 'Whitespace not allowed in path';
            // Keep in sync with backend: disallow characters that break safe shell embedding
            if (/[\'\"\`\$]/.test(v)) return 'Quotes/backticks/$ are not allowed in path';
            const parts = v.split('/').filter(Boolean);
            if (parts.some(seg => seg === '.' || seg === '..')) return 'Path traversal not allowed (..)';
            return null;
        }

        function renderCustomScriptAttachments() {
            const list = document.getElementById('customScriptAttachmentsList');
            if (!list) return;
            updateCustomScriptAttachmentsSummary();

            if (!customScriptAttachments.length) {
                list.innerHTML = '<div style="color: var(--text-secondary);">No attachments selected.</div>';
                return;
            }

            const rows = customScriptAttachments.map((a, idx) => {
                const err = validateAttachmentTargetPath(a.targetPath);
                const errHtml = err ? `<div style="color: var(--error); font-size: 0.85rem; margin-top: 0.25rem;">${escapeHtml(err)}</div>` : '';
                return `
                    <div style="border: 1px solid #333; border-radius: 6px; padding: 0.75rem; margin-bottom: 0.5rem; background: rgba(0,0,0,0.2);">
                        <div style="display: flex; justify-content: space-between; gap: 0.75rem; align-items: center;">
                            <div style="min-width: 0;">
                                <div style="font-weight: 600; color: var(--text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                                    ${escapeHtml(a.name)} <span style="color: var(--text-secondary); font-weight: normal;">(${Math.ceil(a.size / 1024)} KB)</span>
                                </div>
                                <div style="margin-top: 0.35rem;">
                                    <label style="display:block; color: var(--text-secondary); font-size: 0.85rem; margin-bottom: 0.25rem;">Target path (relative)</label>
                                    <input type="text" class="form-input" data-attach-idx="${idx}" value="${escapeHtml(a.targetPath)}" style="width: 100%;">
                                    ${errHtml}
                                </div>
                            </div>
                            <button class="control-btn" data-attach-remove="${idx}" style="white-space: nowrap;">Remove</button>
                        </div>
                    </div>
                `;
            }).join('');

            const loadingNote = customScriptAttachmentsLoading > 0
                ? `<div style="color: var(--text-secondary); margin-top: 0.25rem;">Reading ${customScriptAttachmentsLoading} file(s)...</div>`
                : '';

            list.innerHTML = rows + loadingNote;

            // Wire input edits and removals
            list.querySelectorAll('input[data-attach-idx]').forEach(el => {
                el.addEventListener('input', () => {
                    const idx = parseInt(el.getAttribute('data-attach-idx'), 10);
                    if (!Number.isFinite(idx) || !customScriptAttachments[idx]) return;
                    customScriptAttachments[idx].targetPath = el.value;
                    // Re-render to reflect validation state
                    renderCustomScriptAttachments();
                });
            });
            list.querySelectorAll('button[data-attach-remove]').forEach(btn => {
                btn.addEventListener('click', () => {
                    const idx = parseInt(btn.getAttribute('data-attach-remove'), 10);
                    if (!Number.isFinite(idx)) return;
                    customScriptAttachments.splice(idx, 1);
                    renderCustomScriptAttachments();
                });
            });
        }

        function updateCustomScriptAttachmentsSummary() {
            const el = document.getElementById('customScriptAttachmentsSummary');
            if (!el) return;
            const count = (customScriptAttachments || []).length;
            const totalBytes = (customScriptAttachments || []).reduce((sum, a) => sum + (a && a.size ? a.size : 0), 0);
            const kb = Math.ceil(totalBytes / 1024);
            if (count === 0) {
                el.textContent = 'No files selected.';
                return;
            }
            el.textContent = `${count} file(s), ${kb} KB total`;
        }

        async function onCustomScriptAttachmentsSelected(e) {
            const files = Array.from((e && e.target && e.target.files) ? e.target.files : []);
            // Append to existing list (do not replace)

            // Guardrails aligned with backend limits
            const maxFiles = 20;
            const maxBytes = 5 * 1024 * 1024;
            const existingCount = (customScriptAttachments || []).length;
            const existingBytes = (customScriptAttachments || []).reduce((sum, a) => sum + (a && a.size ? a.size : 0), 0);
            if (existingCount + files.length > maxFiles) {
                showToast(`Too many files: ${existingCount + files.length}. Max is ${maxFiles}.`, 'error');
                renderCustomScriptAttachments();
                if (e && e.target) e.target.value = '';
                return;
            }
            const totalBytes = files.reduce((sum, f) => sum + (f && f.size ? f.size : 0), 0);
            if (existingBytes + totalBytes > maxBytes) {
                showToast(`Attachments too large: ${Math.ceil((existingBytes + totalBytes) / 1024)} KB. Max is ${Math.ceil(maxBytes / 1024)} KB.`, 'error');
                renderCustomScriptAttachments();
                if (e && e.target) e.target.value = '';
                return;
            }

            customScriptAttachmentsLoading = files.length;
            renderCustomScriptAttachments();

            for (const f of files) {
                try {
                    // If user re-selects the same file (name+size) and targetPath is default, skip to avoid duplicates.
                    const already = (customScriptAttachments || []).some(a =>
                        a && a.name === f.name && a.size === f.size && a.targetPath === f.name
                    );
                    if (already) {
                        showToast(`Skipped duplicate file: ${f.name}`, 'info');
                        continue;
                    }
                    const buf = await new Promise((resolve, reject) => {
                        const reader = new FileReader();
                        reader.onload = () => resolve(reader.result);
                        reader.onerror = () => reject(reader.error || new Error('File read error'));
                        reader.readAsArrayBuffer(f);
                    });
                    const b64 = arrayBufferToBase64(buf);
                    (customScriptAttachments || []).push({
                        name: f.name,
                        size: f.size,
                        targetPath: f.name,
                        contentBase64: b64
                    });
                } catch (err) {
                    showToast(`Failed to read file ${f.name}: ${err && err.message ? err.message : String(err)}`, 'error');
                } finally {
                    customScriptAttachmentsLoading = Math.max(0, customScriptAttachmentsLoading - 1);
                    renderCustomScriptAttachments();
                }
            }

            // Allow picking the same file again later by resetting the input value
            if (e && e.target) e.target.value = '';
        }

        // Load commits from GitHub
        async function loadCommits() {
            if (isLoadingCommits) return;
            isLoadingCommits = true;

            const commitList = document.getElementById('commitList');
            if (currentPage === 1) {
                commitList.innerHTML = '<div class="spinner"></div>';
            }

            try {
                const response = await fetch(`https://api.github.com/repos/CUBRID/cubrid/commits?sha=develop&per_page=30&page=${currentPage}`);
                if (!response.ok) throw new Error('Failed to fetch commits');
                
                const data = await response.json();
                if (data.commitBuildMode) {
                    builderCommitBuildMode = data.commitBuildMode;
                    updateBaselineDisplay();
                }
                
                if (currentPage === 1) {
                    commits = data;
                    commitList.innerHTML = '';
                } else {
                    commits = [...commits, ...data];
                }

                renderCommits();
                currentPage++;
                
                // Show Load More button if we got a full page of results (30 commits)
                const loadMoreBtn = document.getElementById('loadMoreBtn');
                if (loadMoreBtn && data.length === 30) {
                    loadMoreBtn.style.display = 'block';
                }
                
                showToast('Loaded commits successfully', 'success');
            } catch (error) {
                console.error('Error loading commits:', error);
                commitList.innerHTML = '<p style="color: var(--error);">Failed to load commits. Please try again.</p>';
                showToast('Failed to load commits', 'error');
            } finally {
                isLoadingCommits = false;
            }
        }

        // Load more commits
        function loadMoreCommits() {
            loadCommits();
        }

        // Render commits
        function renderCommits() {
            const commitList = document.getElementById('commitList');
            const existingCount = commitList.querySelectorAll('.commit-item').length;
            
            commits.slice(existingCount).forEach((commit, index) => {
                const commitElement = createCommitElement(commit, existingCount + index);
                commitList.appendChild(commitElement);
            });
        }

        // Create commit element
        function createCommitElement(commit, index) {
            const div = document.createElement('div');
            div.className = 'commit-item';
            div.dataset.sha = commit.sha;
            
            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.className = 'commit-checkbox';
            checkbox.id = `commit-${index}`;
            checkbox.onchange = () => toggleCommit(commit.sha);
            
            const info = document.createElement('div');
            info.className = 'commit-info';

            const shaDiv = document.createElement('div');
            shaDiv.className = 'commit-sha';

            const shaLink = document.createElement('a');
            shaLink.href = `https://github.com/CUBRID/cubrid/commit/${commit.sha}`;
            shaLink.target = '_blank';
            shaLink.rel = 'noopener noreferrer';
            shaLink.textContent = commit.sha.substring(0, 8);
            shaLink.title = commit.sha;
            shaLink.addEventListener('click', (event) => event.stopPropagation());

            shaDiv.appendChild(shaLink);

            const messageDiv = document.createElement('div');
            messageDiv.className = 'commit-message';
            messageDiv.textContent = commit.commit.message.split('\n')[0];

            const authorDiv = document.createElement('div');
            authorDiv.className = 'commit-author';
            authorDiv.textContent = `${commit.commit.author.name} - ${formatDate(commit.commit.author.date)}`;

            info.appendChild(shaDiv);
            info.appendChild(messageDiv);
            info.appendChild(authorDiv);
            
            div.appendChild(checkbox);
            div.appendChild(info);
            
            div.onclick = (e) => {
                if (e.target.type !== 'checkbox') {
                    checkbox.checked = !checkbox.checked;
                    toggleCommit(commit.sha);
                }
            };
            
            return div;
        }

        // Toggle commit selection
        function toggleCommit(sha) {
            const element = document.querySelector(`[data-sha="${sha}"]`);
            if (selectedCommits.has(sha)) {
                selectedCommits.delete(sha);
                if (element) element.classList.remove('selected');
            } else {
                selectedCommits.add(sha);
                if (element) element.classList.add('selected');
            }
            updateCommitCount();
        }

        // Update commit count display
        function updateCommitCount() {
            let count = 0;
            if (commitMode === 'select') {
                count = selectedCommits.size;
            } else if (commitMode === 'manual') {
                count = document.getElementById('manualCommitInput').value.split(/[\,\n]/).filter(s => s.trim()).length;
            } else if (commitMode === 'pr') {
                const prVal = (document.getElementById('prNumberInput').value || '').trim();
                count = prVal && /^\d+$/.test(prVal) ? 1 : 0;
            }
            
            const selectedCountSpan = document.getElementById('selectedCount');
            if (selectedCountSpan) {
                selectedCountSpan.textContent = count;
            }
            updateBaselineDisplay();
        }

        async function refreshBuilderCommitMode() {
            try {
                const response = await fetch('/api/builder/health');
                if (!response.ok) return;
                const data = await response.json();
                builderCommitBuildMode = data.commitBuildMode || null;
                if (!baselineModeTouched && builderCommitBuildMode) {
                    const checkoutRadio = document.getElementById('buildModeCheckout');
                    const baselineRadio = document.getElementById('buildModeBaseline');
                    if (builderCommitBuildMode === 'baseline_cherrypick' && baselineRadio) {
                        baselineRadio.checked = true;
                    } else if (checkoutRadio) {
                        checkoutRadio.checked = true;
                    }
                }
                updateBaselineDisplay();
            } catch (e) {
                // Ignore health fetch errors; baseline stays visible by default
            }
        }

        // Update baseline commit display
        function updateBaselineDisplay() {
            const baselineContainer = document.getElementById('baselineInfo');
            const baselineValue = document.getElementById('baselineSha');
            if (!baselineContainer || !baselineValue) return;

            const selectedBuildMode = (document.querySelector('input[name="buildMode"]:checked') || {}).value || 'checkout';
            const baselineEnabled = selectedBuildMode === 'baseline_cherrypick';

            baselineContainer.classList.remove('has-baseline', 'baseline-error');
            baselineValue.textContent = '';
            baselineValue.style.display = '';
            baselineValue.hidden = !baselineEnabled;
            if (!baselineEnabled) return;

            if (commitMode !== 'select') {
                baselineValue.textContent = 'Baseline: Available in Browse & Select mode.';
                return;
            }

            if (selectedCommits.size === 0) {
                baselineValue.textContent = 'Baseline: Select commits to determine baseline.';
                return;
            }

            let earliestCommit = null;
            let earliestTimestamp = Infinity;

            commits.forEach((commit) => {
                if (!selectedCommits.has(commit.sha)) return;
                const dateStr = commit && commit.commit && commit.commit.author && commit.commit.author.date;
                const ts = dateStr ? Date.parse(dateStr) : NaN;

                if (Number.isFinite(ts)) {
                    if (ts < earliestTimestamp) {
                        earliestCommit = commit;
                        earliestTimestamp = ts;
                    }
                } else if (!earliestCommit) {
                    // Fallback: keep the first selected commit if timestamps are unavailable.
                    earliestCommit = commit;
                }
            });

            if (!earliestCommit) {
                baselineValue.textContent = 'Baseline: Select commits to determine baseline.';
                return;
            }

            const parent = Array.isArray(earliestCommit.parents) && earliestCommit.parents.length > 0 ? earliestCommit.parents[0] : null;
            if (!parent || !parent.sha) {
                baselineValue.textContent = 'Baseline: Unable to determine baseline for selected commits.';
                baselineContainer.classList.add('baseline-error');
                return;
            }

            baselineValue.appendChild(document.createTextNode('Baseline: '));

            const link = document.createElement('a');
            link.href = `https://github.com/CUBRID/cubrid/commit/${parent.sha}`;
            link.target = '_blank';
            link.rel = 'noopener noreferrer';
            link.textContent = parent.sha.substring(0, 8);
            link.title = parent.sha;

            const note = document.createElement('span');
            note.className = 'baseline-note';
            note.textContent = ' (parent of earliest selected commit)';

            baselineValue.appendChild(link);
            baselineValue.appendChild(note);
            baselineContainer.classList.add('has-baseline');
        }

        // Select all commits
        function selectAllCommits() {
            commits.forEach(commit => {
                selectedCommits.add(commit.sha);
                const element = document.querySelector(`[data-sha="${commit.sha}"]`);
                if (element) {
                    element.classList.add('selected');
                    element.querySelector('.commit-checkbox').checked = true;
                }
            });
            updateCommitCount();
            showToast(`Selected all ${commits.length} commits`, 'info');
        }

        // Deselect all commits
        function deselectAllCommits() {
            selectedCommits.clear();
            document.querySelectorAll('.commit-item').forEach(item => {
                item.classList.remove('selected');
                item.querySelector('.commit-checkbox').checked = false;
            });
            updateCommitCount();
            showToast('Deselected all commits', 'info');
        }

        // Set commit mode (called from HTML onclick)
        function setCommitMode(mode) {
            commitMode = mode;

            // Update button states
            document.getElementById('selectModeBtn').classList.remove('active');
            document.getElementById('manualModeBtn').classList.remove('active');
            const prBtn = document.getElementById('prModeBtn');
            if (prBtn) prBtn.classList.remove('active');
            const customBtn = document.getElementById('customScriptModeBtn');
            if (customBtn) customBtn.classList.remove('active');

            // Hide all sections first
            document.getElementById('selectCommits').style.display = 'none';
            document.getElementById('manualCommits').style.display = 'none';
            const prSection = document.getElementById('prCommits');
            if (prSection) prSection.style.display = 'none';
            const customSection = document.getElementById('customScriptCommits');
            if (customSection) customSection.style.display = 'none';

            // Show selected section and activate button
            if (mode === 'select') {
                document.getElementById('selectModeBtn').classList.add('active');
                document.getElementById('selectCommits').style.display = 'block';
            } else if (mode === 'manual') {
                document.getElementById('manualModeBtn').classList.add('active');
                document.getElementById('manualCommits').style.display = 'block';
            } else if (mode === 'pr') {
                if (prBtn) prBtn.classList.add('active');
                if (prSection) prSection.style.display = 'block';
            } else if (mode === 'custom') {
                if (customBtn) customBtn.classList.add('active');
                if (customSection) customSection.style.display = 'block';
            }

            applyTestCasesDisabledState();

            updateCommitCount();
            const modeLabelMap = {
                select: 'Browse & Select',
                manual: 'Manual Input',
                pr: 'PR Number',
                custom: 'Custom Script'
            };
            showToast(`Switched to ${modeLabelMap[mode] || mode} mode`, 'info');
        }

        // Toggle advanced configuration panel
        function toggleAdvanced() {
            const panel = document.getElementById('advancedPanel');
            const icon = document.querySelector('.toggle-icon');

            if (panel.style.display === 'none' || !panel.style.display) {
                panel.style.display = 'block';
                icon.textContent = '▲';
                showToast('Advanced configuration expanded', 'info');
            } else {
                panel.style.display = 'none';
                icon.textContent = '▼';
                showToast('Advanced configuration collapsed', 'info');
            }
        }


        // Load sample tests
        function loadSampleTests() {
            const sampleTests = [
                'shell/_05_addition/cubridsus1961/cases/cubridsus1961.sh',
                'shell/_06_issues/_12_2h/bug_bts_9521_1/cases/bug_bts_9521_1.sh',
                'shell/_06_issues/_17_1h/cbrd_20867/cases/cbrd_20867.sh',
                'shell/_06_issues/_25_1h/cbrd_26020/cases/cbrd_26020.sh',
                'shell/_28_features_844/issue_10984_query_profiling/_03_mixed_test/_07_show_columns/cases/_07_show_columns.sh',
                'shell/_08_shard/_50_cubridsus/bug_bts_10130/cases/bug_bts_10130.sh',
                'shell/_10_plcsql/bug_fix/cbrd_25894/cases/cbrd_25894.sh',
                'shell/_38_fig/cbrd_24882/vacuumdb/cases/vacuumdb.sh',
                // Examples for the additional supported roots:
                'shell_heavy/_99_heavy/example/cases/example_heavy.sh',
                'shell_perf/_99_perf/example/cases/example_perf.sh'
            ];
            
            document.getElementById('testsInput').value = sampleTests.join('\n');
            showToast('Loaded sample test cases', 'success');
        }

        // Add worker with health check validation
        async function addWorker() {
            const input = document.getElementById('newWorkerInput');
            const workerIp = input.value.trim();
            
            if (!workerIp) {
                showToast('Please enter a worker IP', 'warning');
                return;
            }
            
            if (workers.includes(workerIp)) {
                showToast('Worker already exists', 'warning');
                return;
            }
            
            // Health check validation
            showToast('Checking worker health...', 'info');
            try {
                const response = await fetch(`/api/tester/health?ip=${encodeURIComponent(workerIp)}`);
                if (!response.ok) {
                    throw new Error('Worker is not responsive');
                }
                const data = await response.json();
                if (data.error || data.status !== 'healthy') {
                    throw new Error('Worker health check failed');
                }
                
                workers.push(workerIp);
                renderWorkers();
                input.value = '';
                showToast(`Added worker: ${workerIp}`, 'success');
            } catch (error) {
                showToast(`Cannot add worker ${workerIp}: ${error.message}`, 'error');
            }
        }

        // Remove worker
        function removeWorker(element) {
            const chip = element.parentElement;
            const workerIp = chip.textContent.replace('×', '').trim();
            workers = workers.filter(w => w !== workerIp);
            renderWorkers();
            showToast(`Removed worker: ${workerIp}`, 'info');
        }

        // Render workers
        function renderWorkers() {
            const workerList = document.getElementById('workerList');
            workerList.innerHTML = workers.map(worker => `
                <div class="worker-chip">
                    ${worker}
                    <span class="worker-remove" onclick="removeWorker(this)">×</span>
                </div>
            `).join('');
        }

        // Validate callback URL
        function validateCallbackUrl() {
            const url = document.getElementById('callbackUrl').value;
            try {
                new URL(url);
                return true;
            } catch {
                showToast('Invalid callback URL format', 'warning');
                return false;
            }
        }

        // Validate commits exist
        async function validateCommits(commitShas) {
            const validationPromises = commitShas.map(async (sha) => {
                try {
                    const response = await fetch(`https://api.github.com/repos/CUBRID/cubrid/commits/${sha}`);
                    return { sha, valid: response.ok };
                } catch {
                    return { sha, valid: false };
                }
            });
            
            const results = await Promise.all(validationPromises);
            const invalid = results.filter(r => !r.valid).map(r => r.sha);
            
            if (invalid.length > 0) {
                showToast(`Invalid commits: ${invalid.join(', ')}`, 'error');
                return false;
            }
            
            return true;
        }

        function validateTestPathsForSubmit(tests) {
            const invalid = [];
            const allowedRoots = ['shell/', 'shell_heavy/', 'shell_perf/'];
            for (const t of (tests || [])) {
                const v = (t || '').trim();
                if (!v) continue;
                if (v === 'custom_script_test') continue;

                // Enforce strict filesystem-like shell test paths.
                // Reject anything that doesn't start with an allowed shell root (this also rejects URLs/report links).
                if (!allowedRoots.some(r => v.startsWith(r))) {
                    invalid.push(v);
                    continue;
                }
                // No query strings or whitespace
                if (v.includes('?') || /\s/.test(v)) {
                    invalid.push(v);
                    continue;
                }
                // Require .sh to avoid passing arbitrary strings
                if (!v.endsWith('.sh')) {
                    invalid.push(v);
                    continue;
                }
            }
            if (invalid.length > 0) {
                throw new Error(
                    `Invalid test path(s): ${invalid.join(', ')}. ` +
                    `Expected filesystem test paths starting with shell/, shell_heavy/, or shell_perf/ (ending with .sh).`
                );
            }
        }

        function buildUploadPayload() {
            const host = (document.getElementById('buildUploadHost').value || '').trim();
            const portRaw = (document.getElementById('buildUploadPort').value || '').trim();
            const username = (document.getElementById('buildUploadUser').value || '').trim();
            const password = (document.getElementById('buildUploadPassword').value || '');
            const remoteDir = (document.getElementById('buildUploadRemoteDir').value || '').trim();

            if (!host) throw new Error('Destination host is required for build-only upload');
            if (!username) throw new Error('Username is required for build-only upload');
            if (!password) throw new Error('Password is required for build-only upload');

            let port = 22;
            if (portRaw) {
                port = parseInt(portRaw, 10);
                if (!Number.isFinite(port) || port < 1 || port > 65535) {
                    throw new Error('Destination port must be between 1 and 65535');
                }
            }

            return {
                host,
                port,
                username,
                password,
                remoteDir
            };
        }

        function redactBuildUpload(payload) {
            if (!payload || typeof payload !== 'object') return payload;
            const copy = JSON.parse(JSON.stringify(payload));
            if (copy.buildUpload && copy.buildUpload.password) {
                copy.buildUpload.password = '***';
            }
            return copy;
        }

        // Submit build request
        async function submitBuildRequest() {
            const button = document.getElementById('submitButton');
            button.disabled = true;
            button.textContent = 'Processing...';
            
            try {
                const buildOnly = isBuildOnlyActive();

                if (!isBuildOnlyAvailable() && isBuildOnlyEnabled()) {
                    throw new Error('Build-only mode is not available in Custom Script mode');
                }

                // Gather commits or PR
                let payloadCommits = [];
                let prNumberPayload = null;
                if (commitMode === 'select') {
                    payloadCommits = Array.from(selectedCommits);
                    if (payloadCommits.length === 0) throw new Error('Please select at least one commit');
                } else if (commitMode === 'manual') {
                    const manualInput = document.getElementById('manualCommitInput').value;
                    payloadCommits = manualInput.split(/[\,\n]/)
                        .map(s => s.trim())
                        .filter(s => s.length > 0);
                    // Validate manual commits
                    showToast('Validating commits...', 'info');
                    const valid = await validateCommits(payloadCommits);
                    if (!valid) {
                        throw new Error('Invalid commits detected');
                    }
                    if (payloadCommits.length === 0) throw new Error('Please enter at least one commit');
                } else if (commitMode === 'pr') {
                    const prVal = (document.getElementById('prNumberInput').value || '').trim();
                    if (!prVal || !/^\d+$/.test(prVal)) throw new Error('Please enter a valid PR number');
                    prNumberPayload = prVal;
                } else if (commitMode === 'custom') {
                    // Custom script mode - handle commit/PR input
                    const customCommitInput = (document.getElementById('customScriptCommitInput').value || '').trim();
                    if (customCommitInput) {
                        // Check if it's a PR reference (e.g., "pr:6402" or just "6402")
                        const prMatch = customCommitInput.match(/^(?:pr:)?(\d+)$/i);
                        if (prMatch) {
                            prNumberPayload = prMatch[1];
                        } else {
                            // Treat as commit SHA
                            payloadCommits = [customCommitInput];
                        }
                    } else {
                        // No commit specified - will use latest from develop
                        // Need at least one commit, so we'll use a placeholder that builder can resolve
                        payloadCommits = ['develop'];
                    }
                }

                // Check if custom script mode is enabled
                let customScriptContent = null;
                let customScriptTestPath = null;

                // Gather tests
                const testsInput = document.getElementById('testsInput').value;
                let tests = testsInput.split('\n')
                    .map(s => s.trim())
                    .filter(s => s.length > 0);

                if (!buildOnly) {
                    if (commitMode === 'custom') {
                        customScriptContent = document.getElementById('customScriptContent').value.trim();
                        customScriptTestPath = document.getElementById('customScriptTestPath').value.trim();

                        if (!customScriptContent) {
                            throw new Error('Custom script content is required in Custom Script mode');
                        }

                        // If custom test path is provided, use it; otherwise use placeholder
                        if (customScriptTestPath) {
                            tests = [customScriptTestPath];
                        } else {
                            // No test path provided - use placeholder for custom script only mode
                            tests = ['custom_script_test'];
                        }
                    } else {
                        if (tests.length === 0) {
                            throw new Error('Please enter at least one test case');
                        }
                    }

                    // Validate test paths (prevent accidental report URLs)
                    validateTestPathsForSubmit(tests);

                    // Validate custom attachments (if any)
                    if (commitMode === 'custom') {
                        if (customScriptAttachmentsLoading > 0) {
                            throw new Error(`Attachments are still loading (${customScriptAttachmentsLoading} file(s)). Please wait.`);
                        }
                        for (const a of (customScriptAttachments || [])) {
                            const err = validateAttachmentTargetPath(a.targetPath);
                            if (err) {
                                throw new Error(`Invalid attachment path '${a.targetPath}': ${err}`);
                            }
                        }
                    }
                } else {
                    tests = [];
                }
                
                // Validate callback URL
                if (!validateCallbackUrl()) {
                    throw new Error('Invalid callback URL');
                }
                
                // Build request payload
                const payload = {
                    tests: tests,
                    callbackUrl: document.getElementById('callbackUrl').value,
                    buildType: document.getElementById('buildType').value,
                    timeout: parseInt(document.getElementById('timeout').value),
                    runMode: document.getElementById('runMode').value,
                    minRuns: parseInt(document.getElementById('minRuns').value),
                    maxRuns: parseInt(document.getElementById('maxRuns').value),
                    buildOnly: buildOnly
                };
                if (!buildOnly) {
                    payload.workerIps = workers;
                }
                if (prNumberPayload) {
                    payload.prNumber = prNumberPayload;
                } else {
                    payload.commits = payloadCommits;
                }

                // Prefer commitBuildMode everywhere; builder also supports use_baseline_cherrypick for backward compatibility.
                const selectedBuildMode = (document.querySelector('input[name="buildMode"]:checked') || {}).value;
                if (selectedBuildMode) {
                    payload.commitBuildMode = selectedBuildMode;
                }

                // Add custom script if in custom mode
                if (!buildOnly && commitMode === 'custom' && customScriptContent) {
                    payload.customShellScript = customScriptContent;
                    if (customScriptTestPath) {
                        payload.customScriptTestPath = customScriptTestPath;
                    }
                    if (customScriptAttachments && customScriptAttachments.length > 0) {
                        payload.customAttachments = customScriptAttachments.map(a => ({
                            targetPath: (a.targetPath || '').trim(),
                            contentBase64: a.contentBase64
                        }));
                    }
                }

                if (buildOnly) {
                    payload.buildUpload = buildUploadPayload();
                }

                // Add environment variables if provided
                const envVars = document.getElementById('envVars').value.trim();
                if (envVars) {
                    try {
                        payload.envVars = JSON.parse(envVars);
                    } catch {
                        throw new Error('Invalid JSON in environment variables');
                    }
                }
                
                // Send request to builder service
                showToast('Sending build request...', 'info');
                const endpoint = payload.prNumber ? '/api/builder/build/pr' : '/api/builder/build';
                const response = await fetch(endpoint, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                    },
                    body: JSON.stringify(payload)
                });
                
                if (!response.ok) {
                    let errorMessage = `Build request failed: ${response.status} ${response.statusText}`;
                    try {
                        const errorData = await response.json();
                        if (errorData.message) {
                            errorMessage += `\n${errorData.message}`;
                        }
                    } catch {
                        const errorText = await response.text();
                        if (errorText) {
                            errorMessage += `\n${errorText}`;
                        }
                    }
                    throw new Error(errorMessage);
                }
                
                const result = await response.json();
                
                // Display successful response
                const requestId = result.taskId || result.requestId || 'Unknown';
                const safePayload = redactBuildUpload(payload);
                displayResponse('Build request sent successfully!\n\nRequest ID: ' + requestId + '\n\nStatus: ' + (result.status || 'Processing') + '\n\nPayload:\n' + JSON.stringify(safePayload, null, 2));
                showToast('Build request sent successfully!', 'success');
                
                // Start monitoring with actual request ID
                startStatusMonitoring({
                    ...safePayload,
                    requestId: requestId,
                    taskId: result.taskId
                });
                
            } catch (error) {
                showToast(error.message, 'error');
            } finally {
                button.disabled = false;
                button.textContent = 'Send Build Request';
            }
        }

        // Display response
        function displayResponse(content) {
            document.getElementById('responseDisplay').style.display = 'block';
            document.getElementById('responseContent').textContent = content;
        }

        // Start status monitoring with real-time updates
        function startStatusMonitoring(request) {
            const monitor = document.getElementById('statusMonitor');
            const refreshBtn = document.getElementById('refreshStatusBtn');
            const tailBtn = document.getElementById('tailBuilderLogBtn');
            const startTime = new Date();
            
            // Store session data for persistence across page refreshes
            const taskId = request.taskId || request.requestId;
            if (taskId) {
                sessionStorage.setItem('activeTaskId', taskId);
                sessionStorage.setItem('activeRequest', JSON.stringify(request));
            }

            const pinnedTaskId = taskId;
            let viewTaskId = sessionStorage.getItem('viewTaskId') || pinnedTaskId;
            sessionStorage.setItem('viewTaskId', viewTaskId);
            if (pinnedTaskId && request && typeof request === 'object') {
                requestMetaCache.set(pinnedTaskId, request);
            }

            function parseStartTimeFromRequestId(id) {
                try {
                    // req_YYYYMMDD_HHMMSS_xxxx
                    const m = String(id || '').match(/^req_(\d{8})_(\d{6})_/);
                    if (!m) return null;
                    const d = m[1];
                    const t = m[2];
                    const year = parseInt(d.slice(0, 4));
                    const month = parseInt(d.slice(4, 6)) - 1;
                    const day = parseInt(d.slice(6, 8));
                    const hour = parseInt(t.slice(0, 2));
                    const min = parseInt(t.slice(2, 4));
                    const sec = parseInt(t.slice(4, 6));
                    return new Date(year, month, day, hour, min, sec);
                } catch {
                    return null;
                }
            }

            async function getRequestMeta(id) {
                if (!id) return null;
                if (requestMetaCache.has(id)) return requestMetaCache.get(id);
                try {
                    // request.json is written by builder's LogRotationManager under log/requests/<id>/request.json
                    const resp = await fetch(`/api/log-root/${id}/request.json`);
                    if (!resp.ok) throw new Error('request.json not available');
                    const text = await resp.text();
                    const meta = JSON.parse(text);
                    requestMetaCache.set(id, meta);
                    return meta;
                } catch {
                    return null;
                }
            }

            function getCounts(meta) {
                if (!meta) return { commitCount: null, testCount: null, totalTasks: null };
                const isPr = meta.prNumber !== undefined && meta.prNumber !== null && String(meta.prNumber).trim() !== '';
                const commitCount = isPr ? 1 : (Array.isArray(meta.commits) ? meta.commits.length : null);
                const testCount = Array.isArray(meta.tests) ? meta.tests.length : null;
                const totalTasks = (commitCount != null && testCount != null)
                    ? (commitCount + (commitCount * testCount))
                    : null;
                return { commitCount, testCount, totalTasks };
            }

            function renderMonitor(meta, id) {
                const started = parseStartTimeFromRequestId(id) || (id === pinnedTaskId ? startTime : null);
                const startedText = started ? started.toLocaleString() : 'Unknown';
                const counts = getCounts(meta);
                const commitsText = (meta && meta.prNumber) ? `PR #${meta.prNumber}` : (counts.commitCount != null ? counts.commitCount : 'Unknown');
                const testsText = counts.testCount != null ? counts.testCount : 'Unknown';

                monitor.innerHTML = `
                    <div class="info-item">
                        <div class="info-label">Request ID</div>
                        <div class="info-value" id="request-id">${id || ''}</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Status</div>
                        <div class="status checking">
                            <span class="status-dot"></span>
                            <span id="status-text">Initializing...</span>
                        </div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Started</div>
                        <div class="info-value">${startedText}</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Commits</div>
                        <div class="info-value">${commitsText}</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Tests</div>
                        <div class="info-value">${testsText}</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Progress</div>
                        <div class="info-value">
                            <div id="progress-details">Preparing build environment...</div>
                            <div id="progress-bar" style="width: 100%; background: #333; height: 8px; border-radius: 4px; margin-top: 4px;">
                                <div id="progress-fill" style="width: 0%; background: var(--primary); height: 100%; border-radius: 4px; transition: width 0.5s ease;"></div>
                            </div>
                        </div>
                    </div>
                `;
            }
            
            // Show the refresh and tail buttons
            if (refreshBtn) {
                refreshBtn.style.display = 'inline-block';
            }
            if (tailBtn) {
                tailBtn.style.display = 'inline-block';
            }

            // Initial render for current view
            renderMonitor(requestMetaCache.get(viewTaskId) || request, viewTaskId);
            
            
            // Polling function for status updates
            async function pollStatus(targetTaskId) {
                try {
                    const id = targetTaskId || viewTaskId || pinnedTaskId;
                    
                    // Poll builder status with taskId if available
                    const statusUrl = id ? 
                        `/api/builder/status?taskId=${id}` : 
                        '/api/builder/status';
                    
                    const response = await fetch(statusUrl);
                    const statusData = await response.json();
                    
                    const statusElement = document.getElementById('status-text');
                    const progressDetails = document.getElementById('progress-details');
                    const progressFill = document.getElementById('progress-fill');
                    const hintEl = document.getElementById('statusMonitorHint');

                    // Update hint based on queue snapshot
                    try {
                        const snap = lastQueueSnapshot;
                        const isRunning = snap && snap.running === id;
                        const isPinned = id === pinnedTaskId;
                        
                        if (hintEl) {
                            const isQueued = snap && Array.isArray(snap.queued) && snap.queued.includes(id);
                            if (snap && snap.running && id !== snap.running) {
                                // Not viewing the highlighted running one
                                let msg = 'Viewing ';
                                if (isQueued) msg += 'queued';
                                else msg += 'previous';
                                msg += ' build. Press <b>Refresh Status</b> to switch to the running build.';
                                hintEl.innerHTML = msg;
                                hintEl.style.display = 'block';
                            } else {
                                hintEl.style.display = 'none';
                            }
                        }
                    } catch {}
                    
                    // Check for completion first
                    if (response.ok && statusData && statusData.status === 'not_found') {
                        // Task not found - check if report exists to confirm completion
                        if (id) {
                            try {
                                const reportResponse = await fetch(`/report?id=${id}`, { method: 'HEAD' });
                                if (reportResponse.ok) {
                                    // Report exists - task completed successfully
                                    statusElement.textContent = 'Completed';
                                    statusElement.parentElement.className = 'status success';
                                    progressDetails.textContent = 'Build and tests completed successfully - Report available';
                                    progressFill.style.width = '100%';
                                    return;
                                }
                            } catch (reportError) {
                                console.warn('Could not check report existence:', reportError);
                            }
                        }
                        
                        // Task not found but no report:
                        // - if it's in the queue snapshot, treat as queued/waiting
                        // - otherwise mark as not found
                        const snap = lastQueueSnapshot;
                        const queuedIdx = (snap && Array.isArray(snap.queued)) ? snap.queued.indexOf(id) : -1;
                        if (queuedIdx >= 0) {
                            statusElement.textContent = `Queued (${queuedIdx + 1})`;
                            statusElement.parentElement.className = 'status checking';
                            progressDetails.textContent = `Waiting in build queue (position ${queuedIdx + 1})`;
                            progressFill.style.width = '0%';
                        } else if (snap && snap.running === id) {
                            // Rare transient case: queue says running but status lookup missed it
                            statusElement.textContent = 'Starting...';
                            statusElement.parentElement.className = 'status checking';
                            progressDetails.textContent = 'Build is starting (initializing)...';
                            progressFill.style.width = '0%';
                        } else {
                            statusElement.textContent = 'Not Found';
                            statusElement.parentElement.className = 'status offline';
                            progressDetails.textContent = 'Build not found (no report yet)';
                            progressFill.style.width = '0%';
                        }
                        return;
                    }
                    
                    if (response.ok && statusData) {
                        // Update status based on response
                        if (statusData.status === 'completed') {
                            statusElement.textContent = 'Completed';
                            statusElement.parentElement.className = 'status success';
                            const meta = await getRequestMeta(id);
                            const counts = getCounts(meta);
                            progressDetails.textContent = counts.totalTasks != null
                                ? `All ${counts.totalTasks} tasks completed successfully`
                                : 'Build and tests completed successfully';
                            progressFill.style.width = '100%';
                        } else if (statusData.status === 'failed' || statusData.status === 'error') {
                            statusElement.textContent = 'Failed';
                            statusElement.parentElement.className = 'status offline';
                            progressDetails.textContent = statusData.message || 'Build or tests failed';
                        } else if (statusData.status === 'running' || statusData.status === 'building') {
                            // Update progress details
                            if (statusData.progressSummary && statusData.progressSummary.phase) {
                                const ps = statusData.progressSummary;
                                let phaseText = ps.phase.charAt(0).toUpperCase() + ps.phase.slice(1);
                                if (ps.phase === 'building') phaseText = 'Building...';
                                if (ps.phase === 'testing') phaseText = 'Testing...';
                                if (ps.phase === 'callback') phaseText = 'Finalizing...';
                                
                                statusElement.textContent = phaseText;
                                
                                let taskText = '';
                                if (ps.currentTest) {
                                    taskText = `Test: ${ps.currentTest}`;
                                    if (ps.currentCommit) taskText += ` on ${ps.currentCommit.substring(0, 7)}`;
                                } else if (ps.currentCommit) {
                                    taskText = `Commit: ${ps.currentCommit.substring(0, 7)}`;
                                } else if (ps.phase === 'testing') {
                                    taskText = 'Preparing tests...';
                                } else if (ps.phase === 'building') {
                                    taskText = 'Building sources...';
                                } else {
                                    taskText = 'Processing...';
                                }
                                progressDetails.textContent = taskText;
                            } else if (statusData.currentPhase) {
                                statusElement.textContent = `${statusData.currentPhase}...`;
                                progressDetails.textContent = statusData.currentTask || 
                                    `Processing ${statusData.currentPhase.toLowerCase()}...`;
                            } else {
                                statusElement.textContent = 'Building...';
                                progressDetails.textContent = 'Building commits and running tests...';
                            }
                            
                            // Calculate progress
                            if (statusData.progressSummary && typeof statusData.progressSummary.percent === 'number') {
                                let pct = statusData.progressSummary.percent;
                                // Double safety: never show 100% if status is still running
                                if (pct >= 100) pct = 99;
                                progressFill.style.width = `${pct}%`;
                                
                                const ps = statusData.progressSummary;
                                if (ps.totalTasks > 0) {
                                    progressDetails.textContent += ` (${ps.completedTasks}/${ps.totalTasks})`;
                                }
                            } else if (statusData.progress !== undefined && statusData.progress !== null) {
                                if (typeof statusData.progress === 'number') {
                                    progressFill.style.width = `${statusData.progress}%`;
                                } else if (typeof statusData.progress === 'object') {
                                    // Builder returns a per-commit progress map; show average percent.
                                    const vals = Object.values(statusData.progress)
                                        .map(v => typeof v === 'number' ? v : parseFloat(v))
                                        .filter(v => Number.isFinite(v) && v >= 0);
                                    if (vals.length > 0) {
                                        const avg = vals.reduce((a, b) => a + b, 0) / vals.length;
                                        progressFill.style.width = `${Math.max(0, Math.min(100, avg))}%`;
                                        progressDetails.textContent = `Build progress: ${vals.length} commit(s), avg ${avg.toFixed(0)}%`;
                                    }
                                }
                            } else if (statusData.completedTasks !== undefined && statusData.totalTasks !== undefined) {
                                const progress = (statusData.completedTasks / statusData.totalTasks) * 100;
                                progressFill.style.width = `${progress}%`;
                                progressDetails.textContent = 
                                    `${statusData.completedTasks}/${statusData.totalTasks} tasks completed`;
                            } else {
                                // Estimated progress based on time (simple fallback)
                                const elapsed = (new Date() - startTime) / 1000;
                                const estimatedProgress = Math.min(90, elapsed / 10); // Max 90% until completion
                                progressFill.style.width = `${estimatedProgress}%`;
                            }
                            
                        } else {
                            // Default processing state
                            statusElement.textContent = 'Processing...';
                            progressDetails.textContent = 'Build and test execution in progress...';
                        }
                        
                    } else {
                        // Status API not responding, show generic progress
                        const elapsed = (new Date() - startTime) / 1000;
                        if (elapsed < 60) {
                            statusElement.textContent = 'Building...';
                            progressDetails.textContent = 'Setting up build environment...';
                        } else if (elapsed < 300) {
                            statusElement.textContent = 'Testing...';
                            progressDetails.textContent = 'Compiling and running tests...';
                        } else {
                            statusElement.textContent = 'Processing...';
                            progressDetails.textContent = 'Long-running test execution...';
                        }
                        
                        // Estimated progress
                        const estimatedProgress = Math.min(85, elapsed / 20);
                        progressFill.style.width = `${estimatedProgress}%`;
                    }
                    
                } catch (error) {
                    console.warn('Status polling error:', error);
                    // Continue with generic updates
                    const elapsed = (new Date() - startTime) / 1000;
                    const statusElement = document.getElementById('status-text');
                    const progressDetails = document.getElementById('progress-details');
                    const progressFill = document.getElementById('progress-fill');
                    
                    if (statusElement) {
                        statusElement.textContent = 'Processing...';
                        progressDetails.textContent = 'Build and test execution in progress...';
                        const estimatedProgress = Math.min(80, elapsed / 30);
                        progressFill.style.width = `${estimatedProgress}%`;
                    }
                }
            }

            // Initial status check only (no automatic polling)
            pollStatus(viewTaskId);
            refreshBuildQueue(viewTaskId);

            // Expose a hook so queue auto-refresh can update the monitor on completion without enabling high-frequency polling.
            window.__pollStatus = pollStatus;
            
            // Store intervals for cleanup if needed
            window.statusMonitorIntervals = {};
            
            async function switchToTask(id) {
                if (!id) return;
                viewTaskId = id;
                sessionStorage.setItem('viewTaskId', id);
                await refreshBuildQueue(id);
                const meta = await getRequestMeta(id);
                renderMonitor(meta, id);
                await pollStatus(id);

                // If the log panel is open, re-point it to the newly selected task.
                // Note: selecting a queued item stops the tail timer (logTailActive=false) but the panel stays open,
                // so we must key off panel visibility, not logTailActive.
                const logContainer = document.getElementById('logTailContainer');
                const logPanelOpen = !!(logContainer && logContainer.style.display !== 'none');
                if (logPanelOpen) {
                    // If the selected request is queued (not active), don't tail the currently running log.
                    const snap = lastQueueSnapshot;
                    const isQueued = snap && Array.isArray(snap.queued) && snap.queued.includes(id);
                    const isActive = snap && snap.running === id;

                    const logArea = document.getElementById('builderLogArea');
                    if (logArea) {
                        logArea.textContent = isQueued
                            ? `Queued (${snap.queued.indexOf(id) + 1}) - builder.log will appear when this request starts.`
                            : 'Loading log for ' + id + '...';
                    }

                    // Always stop current tail
                    stopBuilderLogTail();

                    // Only tail if the request is active (or if a log already exists)
                    if (isActive) {
                        startBuilderLogTail();
                    }
                }
            }

            // Expose for queue clicks
            window.switchMonitorTask = switchToTask;

            // Make refresh available globally for manual refresh:
            // - refresh queue view
            // - if viewing a queued/previous task, switch to the currently running build
            window.refreshBuildStatus = async () => {
                // User action: clear transient finished marker.
                transientFinishedTaskId = null;
                transientFinishedAtMs = 0;

                const snap = await refreshBuildQueue(viewTaskId);

                // If viewing pinned build and there's a different running build, switch to it.
                if (pinnedTaskId && viewTaskId === pinnedTaskId && snap && snap.running && snap.running !== pinnedTaskId) {
                    await switchToTask(snap.running);
                    await refreshBuildQueue(snap.running);
                    return;
                }

                // If a build is currently running and we're not viewing it, switch to it.
                // If we're already viewing the running build, just refresh status (do NOT cycle to a queued item).
                if (snap && snap.running) {
                    if (viewTaskId !== snap.running) {
                        await switchToTask(snap.running);
                        await refreshBuildQueue(snap.running);
                        return;
                    }
                    await pollStatus(viewTaskId);
                    await refreshBuildQueue(viewTaskId);
                    return;
                }

                // No running build: just refresh the current view
                await pollStatus(viewTaskId);
            };

        // --- Log Tailing Logic ---
        let logTailActive = false;
        let logTailOffset = 0;
        let logTailKnownMtime = 0;
        let logTailTimer = null;
        let lastReportCheckMs = 0;
        let logTailTaskId = null;

        window.toggleBuilderLog = function() {
            const container = document.getElementById('logTailContainer');
            if (container.style.display === 'none') {
                container.style.display = 'block';
                if (!logTailActive) {
                    // If current view is queued, show placeholder instead of tailing another request.
                    const viewId = sessionStorage.getItem('viewTaskId') || sessionStorage.getItem('activeTaskId');
                    const snap = lastQueueSnapshot;
                    const isQueued = viewId && snap && Array.isArray(snap.queued) && snap.queued.includes(viewId);
                    if (isQueued) {
                        const pos = snap.queued.indexOf(viewId) + 1;
                        const titleEl = document.getElementById('builderLogTitle');
                        const areaEl = document.getElementById('builderLogArea');
                        if (titleEl) titleEl.textContent = `${viewId}/builder.log`;
                        if (areaEl) {
                            areaEl.textContent = `Queued (${pos}) - builder.log will appear when this request starts.`;
                        }
                        return;
                    }
                    startBuilderLogTail();
                }
            } else {
                container.style.display = 'none';
                stopBuilderLogTail();
            }
        };

        window.clearBuilderLog = function() {
            document.getElementById('builderLogArea').textContent = '';
            logTailOffset = 0;
            logTailKnownMtime = 0;
        };

        async function startBuilderLogTail() {
            const taskId = sessionStorage.getItem('viewTaskId') || sessionStorage.getItem('activeTaskId');
            if (!taskId) return;

            logTailTaskId = taskId;
            document.getElementById('builderLogTitle').textContent = `${taskId}/builder.log`;
            logTailActive = true;
            logTailOffset = 0;
            logTailKnownMtime = 0;
            document.getElementById('builderLogArea').textContent = 'Loading builder.log...';
            
            pollTail();
        }

        function stopBuilderLogTail() {
            logTailActive = false;
            logTailTaskId = null;
            if (logTailTimer) {
                clearTimeout(logTailTimer);
                logTailTimer = null;
            }
        }

        async function pollTail() {
            if (!logTailActive) return;

            const taskId = logTailTaskId;
            if (!taskId) {
                stopBuilderLogTail();
                return;
            }

            try {
                const url = `/log-tail/builder?taskId=${taskId}&offset=${logTailOffset}&knownMtimeMs=${logTailKnownMtime}`;
                const response = await fetch(url);
                const data = await response.json();

                if (data.status === 'ok') {
                    const area = document.getElementById('builderLogArea');
                    const isAtBottom = area.scrollHeight - area.scrollTop <= area.clientHeight + 50;

                    if (logTailOffset === 0 && data.content === '') {
                        area.textContent = 'Waiting for log data...';
                    } else if (logTailOffset === 0) {
                        area.textContent = data.content;
                    } else {
                        area.textContent += data.content;
                    }

                    logTailOffset = data.nextOffset;
                    logTailKnownMtime = data.mtimeMs;

                    if (isAtBottom && data.content.length > 0) {
                        area.scrollTop = area.scrollHeight;
                    }

                    // Adaptive backoff: if no content, wait longer
                    let delay = data.recommendedPollMs || 5000;
                    
                    // Check if the request is finished (load-friendly: infrequent).
                    // IMPORTANT: Do NOT use /report existence as a completion signal; the report can be created
                    // by per-test callbacks while the request is still running.
                    // Instead, consider the request finished only when Builder no longer reports it as running.
                    const now = Date.now();
                    if (now - lastReportCheckMs >= 30000) {
                        lastReportCheckMs = now;
                        try {
                            const statusResp = await fetch(`/api/builder/status?taskId=${taskId}`);
                            const statusJson = await statusResp.json();
                            if (statusResp.ok && statusJson && statusJson.status === 'not_found') {
                                console.log('Build completed (builder no longer reports task), stopping log tail.');
                                area.textContent += '\n--- Build Finished (Monitoring Stopped) ---\n';
                                area.scrollTop = area.scrollHeight;
                                stopBuilderLogTail();

                                // Auto-refresh status/progress so the monitor updates without manual click
                                try {
                                    await pollStatus(taskId);
                                    await refreshBuildQueue(taskId);
                                } catch (e) {
                                    console.warn('Failed to auto-refresh after completion:', e);
                                }
                                return;
                            }
                        } catch (e) {
                            // ignore completion check failures; we'll retry later
                        }
                    }

                    logTailTimer = setTimeout(pollTail, delay);
                } else if (data.status === 'not_found') {
                    document.getElementById('builderLogArea').textContent = 'Log file not yet available...';
                    logTailTimer = setTimeout(pollTail, 10000);
                } else {
                    console.warn('Log tail error:', data.message);
                    logTailTimer = setTimeout(pollTail, 15000);
                }
            } catch (err) {
                console.error('Failed to poll log tail:', err);
                logTailTimer = setTimeout(pollTail, 20000);
            }
        }
    }

        // Show system info modal
        async function showSystemInfo() {
            const modal = document.getElementById('systemInfoModal');
            modal.classList.add('show');
            
            // Load builder info
            loadBuilderInfo();
        }

        // Load builder info
        async function loadBuilderInfo() {
            const builderInfo = document.getElementById('builderInfo');
            builderInfo.innerHTML = '<div class="spinner"></div>';
            
            try {
                // Fetch real builder health and status information
                const response = await fetch('/api/builder/health');
                
                if (!response.ok) {
                    throw new Error(`Builder not accessible: ${response.status} ${response.statusText}`);
                }
                
                const data = await response.json();
                
                // Get additional status info if available
                let statusData = {};
                try {
                    const statusResponse = await fetch('/api/builder/status');
                    if (statusResponse.ok) {
                        statusData = await statusResponse.json();
                    }
                } catch (e) {
                    console.warn('Could not fetch builder status:', e);
                }
                
                // Display real builder information
                builderInfo.innerHTML = `
                    <div class="info-item">
                        <div class="info-label">Status</div>
                        <div class="status ${data.status === 'healthy' ? 'online' : 'offline'}">
                            <span class="status-dot"></span>
                            ${data.status === 'healthy' ? 'Online' : 'Offline'}
                        </div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Service</div>
                        <div class="info-value">${data.service || 'builder'}</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Host</div>
                        <div class="info-value">${window.SERVER_CONFIG.builderHost}:${window.SERVER_CONFIG.builderPort}</div>
                    </div>
                    ${statusData.activeTasks !== undefined ? `
                    <div class="info-item">
                        <div class="info-label">Active Tasks</div>
                        <div class="info-value">${statusData.activeTasks}</div>
                    </div>
                    ` : ''}
                    ${statusData.queueSize !== undefined ? `
                    <div class="info-item">
                        <div class="info-label">Queue Size</div>
                        <div class="info-value">${statusData.queueSize}</div>
                    </div>
                    ` : ''}
                    ${data.uptime ? `
                    <div class="info-item">
                        <div class="info-label">Uptime</div>
                        <div class="info-value">${data.uptime}</div>
                    </div>
                    ` : ''}
                    ${data.version ? `
                    <div class="info-item">
                        <div class="info-label">Version</div>
                        <div class="info-value">${data.version}</div>
                    </div>
                    ` : ''}
                `;
                
            } catch (error) {
                console.error('Error loading builder info:', error);
                builderInfo.innerHTML = `
                    <div class="info-item">
                        <div class="info-label">Status</div>
                        <div class="status offline">
                            <span class="status-dot"></span>
                            Connection Failed
                        </div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Error</div>
                        <div class="info-value" style="color: var(--error);">${error.message}</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Host</div>
                        <div class="info-value">${window.SERVER_CONFIG.builderHost}:${window.SERVER_CONFIG.builderPort}</div>
                    </div>
                `;
            }
        }

        // Check tester status
        async function checkTesterStatus() {
            const testerIp = document.getElementById('testerIp').value.trim();
            if (!testerIp) {
                showToast('Please enter tester IP address', 'warning');
                return;
            }
            
            const testerInfo = document.getElementById('testerInfo');
            testerInfo.innerHTML = '<div class="spinner"></div>';
            
            try {
                // Fetch real tester health information
                const response = await fetch(`/api/tester/health?ip=${encodeURIComponent(testerIp)}`);
                
                if (!response.ok) {
                    throw new Error(`Tester not accessible: ${response.status} ${response.statusText}`);
                }
                
                const data = await response.json();
                
                if (data.error) {
                    throw new Error(data.error);
                }
                
                // Display real tester information
                testerInfo.innerHTML = `
                    <div class="info-item">
                        <div class="info-label">Status</div>
                        <div class="status ${data.status === 'healthy' ? 'online' : 'offline'}">
                            <span class="status-dot"></span>
                            ${data.status === 'healthy' ? 'Connected' : 'Disconnected'}
                        </div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Address</div>
                        <div class="info-value">${testerIp}</div>
                    </div>
                    ${data.service ? `
                    <div class="info-item">
                        <div class="info-label">Service</div>
                        <div class="info-value">${data.service}</div>
                    </div>
                    ` : ''}
                    ${data.version ? `
                    <div class="info-item">
                        <div class="info-label">Version</div>
                        <div class="info-value">${data.version}</div>
                    </div>
                    ` : ''}
                    ${data.platform ? `
                    <div class="info-item">
                        <div class="info-label">Platform</div>
                        <div class="info-value">${data.platform}</div>
                    </div>
                    ` : ''}
                    ${data.cpuCores ? `
                    <div class="info-item">
                        <div class="info-label">CPU Cores</div>
                        <div class="info-value">${data.cpuCores}</div>
                    </div>
                    ` : ''}
                    ${data.memory ? `
                    <div class="info-item">
                        <div class="info-label">Memory</div>
                        <div class="info-value">${data.memory}</div>
                    </div>
                    ` : ''}
                    ${data.uptime ? `
                    <div class="info-item">
                        <div class="info-label">Uptime</div>
                        <div class="info-value">${data.uptime}</div>
                    </div>
                    ` : ''}
                `;
                
                showToast('Tester status retrieved successfully', 'success');
                
            } catch (error) {
                console.error('Error checking tester status:', error);
                testerInfo.innerHTML = `
                    <div class="info-item">
                        <div class="info-label">Status</div>
                        <div class="status offline">
                            <span class="status-dot"></span>
                            Connection Failed
                        </div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Address</div>
                        <div class="info-value">${testerIp}</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Error</div>
                        <div class="info-value" style="color: var(--error);">${error.message}</div>
                    </div>
                `;
                
                showToast(`Failed to connect to tester: ${error.message}`, 'error');
            }
        }

        // Close system info modal
        function closeSystemInfo() {
            document.getElementById('systemInfoModal').classList.remove('show');
        }

        // Load recent reports
        async function loadRecentReports() {
            const container = document.getElementById('recentReports');
            if (!container) return;
            
            container.innerHTML = '<div class="spinner"></div>';
            
            try {
                const res = await fetch('/reports');
                const html = await res.text();
                
                // Parse report links from HTML
                const parser = new DOMParser();
                const doc = parser.parseFromString(html, 'text/html');
                const links = doc.querySelectorAll('a[href^="/report?id="]');
                
                if (links.length === 0) {
                    container.innerHTML = '<p style="color: var(--text-secondary); text-align: center; padding: 2rem;">No reports available yet.<br>Submit a build request to generate reports.</p>';
                    return;
                }
                
                // Show first 5 reports with better formatting
                const reportsList = document.createElement('div');
                reportsList.className = 'reports-list';
                
                for (let i = 0; i < Math.min(5, links.length); i++) {
                    const link = links[i];
                    const reportItem = document.createElement('div');
                    reportItem.className = 'report-item';
                    
                    const reportLink = link.cloneNode(true);
                    reportLink.style.color = 'var(--primary)';
                    reportLink.style.textDecoration = 'none';
                    reportLink.addEventListener('mouseenter', function() {
                        this.style.color = 'var(--primary-hover)';
                    });
                    reportLink.addEventListener('mouseleave', function() {
                        this.style.color = 'var(--primary)';
                    });
                    
                    reportItem.appendChild(reportLink);
                    reportsList.appendChild(reportItem);
                }
                
                container.innerHTML = '';
                container.appendChild(reportsList);
                
                showToast('Reports loaded successfully', 'success');
            } catch (e) {
                console.error('Error loading reports:', e);
                container.innerHTML = '<p style="color: var(--error); text-align: center;">Failed to load reports</p>';
                showToast('Failed to load reports', 'error');
            }
        }

        // Refresh reports list
        function refreshReports() {
            showToast('Refreshing reports...', 'info');
            loadRecentReports();
        }

        // View all reports (opens reports page)
        function viewAllReports() {
            window.open('/reports', '_blank');
        }

        // Show toast notification
        function showToast(message, type = 'info') {
            const container = document.getElementById('toastContainer');
            const toast = document.createElement('div');
            toast.className = `toast ${type}`;
            
            const icon = {
                success: '✓',
                error: '✗',
                warning: '⚠',
                info: 'ℹ'
            }[type];
            
            toast.innerHTML = `
                <span style="font-size: 1.25rem;">${icon}</span>
                <span>${message}</span>
            `;
            
            container.appendChild(toast);
            
            setTimeout(() => {
                toast.style.animation = 'fadeOut 0.3s ease-out';
                setTimeout(() => {
                    container.removeChild(toast);
                }, 300);
            }, 3000);
        }

        // Check for active sessions on page load
        async function checkForActiveSessions() {
            try {
                // First check if we have a stored session
                const storedTaskId = sessionStorage.getItem('activeTaskId');
                const storedRequest = sessionStorage.getItem('activeRequest');
                
                if (storedTaskId && storedRequest) {
                    // Check if the stored session is still active
                    const response = await fetch(`/api/builder/status?taskId=${storedTaskId}`);
                    const statusData = await response.json();
                    
                    if (response.ok && statusData.status === 'running') {
                        // Session is still active, restore the monitoring
                        const request = JSON.parse(storedRequest);
                        startStatusMonitoring(request);
                        showToast('Resumed monitoring active build session', 'info');
                        return;
                    }

                    // Session not running anymore; if report exists, still restore and show as completed.
                    try {
                        const reportResponse = await fetch(`/report?id=${storedTaskId}`, { method: 'HEAD' });
                        if (reportResponse.ok) {
                            const request = JSON.parse(storedRequest);
                            startStatusMonitoring(request);
                            showToast('Restored last build session (completed). Press Refresh to view current running build.', 'info');
                            return;
                        }
                    } catch (e) {
                        // ignore
                    }

                    // Otherwise clear stale storage
                    sessionStorage.removeItem('activeTaskId');
                    sessionStorage.removeItem('activeRequest');
                    sessionStorage.removeItem('viewTaskId');
                }
                
                // Check for any active builds (without taskId)
                const generalResponse = await fetch('/api/builder/status');
                if (generalResponse.ok) {
                    const generalStatus = await generalResponse.json();
                    if (generalStatus.activeTasks && generalStatus.activeTasks.length > 0) {
                        // There are active builds but we don't have session data
                        showToast(`Found ${generalStatus.activeTasks.length} active build(s), but session was lost. You may need to resubmit to monitor progress.`, 'warning');
                    }
                }
            } catch (error) {
                console.warn('Could not check for active sessions:', error);
            }
        }

        // Utility functions
        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        function formatDate(dateString) {
            const date = new Date(dateString);
            const now = new Date();
            const diff = now - date;
            const days = Math.floor(diff / (1000 * 60 * 60 * 24));
            
            if (days === 0) return 'Today';
            if (days === 1) return 'Yesterday';
            if (days < 7) return `${days} days ago`;
            if (days < 30) return `${Math.floor(days / 7)} weeks ago`;
            return date.toLocaleDateString();
        }

        // Add fade out animation
        const style = document.createElement('style');
        style.textContent = `
            @keyframes fadeOut {
                from { opacity: 1; transform: translateX(0); }
                to { opacity: 0; transform: translateX(100%); }
            }
        `;
        document.head.appendChild(style);
