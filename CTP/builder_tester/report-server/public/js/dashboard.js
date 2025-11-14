        let commits = [];
        let selectedCommits = new Set();
        let commitMode = 'select';
        let workers = [];  // Initialize empty, will be populated on load
        let currentPage = 1;
        let isLoadingCommits = false;

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
            updateBaselineDisplay();
        });

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
            if (selectedCommits.has(sha)) {
                selectedCommits.delete(sha);
                document.querySelector(`[data-sha="${sha}"]`).classList.remove('selected');
            } else {
                selectedCommits.add(sha);
                document.querySelector(`[data-sha="${sha}"]`).classList.add('selected');
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

        // Update baseline commit display
        function updateBaselineDisplay() {
            const baselineContainer = document.getElementById('baselineInfo');
            const baselineValue = document.getElementById('baselineSha');
            if (!baselineContainer || !baselineValue) return;

            baselineContainer.classList.remove('has-baseline', 'baseline-error');
            baselineValue.textContent = '';

            if (commitMode !== 'select') {
                baselineValue.textContent = 'Available in Browse & Select mode.';
                return;
            }

            let earliestCommit = null;
            let earliestIndex = -1;

            commits.forEach((commit, index) => {
                if (selectedCommits.has(commit.sha) && index > earliestIndex) {
                    earliestCommit = commit;
                    earliestIndex = index;
                }
            });

            if (!earliestCommit) {
                baselineValue.textContent = 'Select commits to determine baseline.';
                return;
            }

            const parent = Array.isArray(earliestCommit.parents) ? earliestCommit.parents[0] : null;
            if (!parent || !parent.sha) {
                baselineValue.textContent = 'Unable to determine baseline for selected commits.';
                baselineContainer.classList.add('baseline-error');
                return;
            }

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
                'shell/_38_fig/cbrd_24882/vacuumdb/cases/vacuumdb.sh'
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

        // Submit build request
        async function submitBuildRequest() {
            const button = document.getElementById('submitButton');
            button.disabled = true;
            button.textContent = 'Processing...';
            
            try {
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
                
                // Validate callback URL
                if (!validateCallbackUrl()) {
                    throw new Error('Invalid callback URL');
                }
                
                // Build request payload
                const payload = {
                    tests: tests,
                    callbackUrl: document.getElementById('callbackUrl').value,
                    workerIps: workers,
                    buildType: document.getElementById('buildType').value,
                    timeout: parseInt(document.getElementById('timeout').value),
                    runMode: document.getElementById('runMode').value,
                    minRuns: parseInt(document.getElementById('minRuns').value),
                    maxRuns: parseInt(document.getElementById('maxRuns').value)
                };
                if (prNumberPayload) {
                    payload.prNumber = prNumberPayload;
                } else {
                    payload.commits = payloadCommits;
                }

                // Add custom script if in custom mode
                if (commitMode === 'custom' && customScriptContent) {
                    payload.customShellScript = customScriptContent;
                    if (customScriptTestPath) {
                        payload.customScriptTestPath = customScriptTestPath;
                    }
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
                displayResponse('Build request sent successfully!\n\nRequest ID: ' + requestId + '\n\nStatus: ' + (result.status || 'Processing') + '\n\nPayload:\n' + JSON.stringify(payload, null, 2));
                showToast('Build request sent successfully!', 'success');
                
                // Start monitoring with actual request ID
                startStatusMonitoring({
                    ...payload,
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
            const startTime = new Date();
            
            // Store session data for persistence across page refreshes
            const taskId = request.taskId || request.requestId;
            if (taskId) {
                sessionStorage.setItem('activeTaskId', taskId);
                sessionStorage.setItem('activeRequest', JSON.stringify(request));
            }
            
            // Show the refresh button
            if (refreshBtn) {
                refreshBtn.style.display = 'inline-block';
            }
            
            // Initial display
            monitor.innerHTML = `
                <div class="info-item">
                    <div class="info-label">Status</div>
                    <div class="status checking">
                        <span class="status-dot"></span>
                        <span id="status-text">Initializing...</span>
                    </div>
                </div>
                <div class="info-item">
                    <div class="info-label">Started</div>
                    <div class="info-value">${startTime.toLocaleString()}</div>
                </div>
                <div class="info-item">
                    <div class="info-label">Commits</div>
                    <div class="info-value">${request.prNumber ? `PR #${request.prNumber}` : request.commits.length}</div>
                </div>
                <div class="info-item">
                    <div class="info-label">Tests</div>
                    <div class="info-value">${request.tests.length}</div>
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
            
            const totalCommitCount = request.prNumber ? 1 : request.commits.length;
            const totalTasks = totalCommitCount + (totalCommitCount * request.tests.length);
            
            
            // Polling function for status updates
            async function pollStatus() {
                try {
                    const taskId = request.taskId || request.requestId;
                    
                    // Poll builder status with taskId if available
                    const statusUrl = taskId ? 
                        `/api/builder/status?taskId=${taskId}` : 
                        '/api/builder/status';
                    
                    const response = await fetch(statusUrl);
                    const statusData = await response.json();
                    
                    const statusElement = document.getElementById('status-text');
                    const progressDetails = document.getElementById('progress-details');
                    const progressFill = document.getElementById('progress-fill');
                    
                    // Check for completion first
                    if (response.ok && statusData && statusData.status === 'not_found') {
                        // Task not found - check if report exists to confirm completion
                        if (taskId) {
                            try {
                                const reportResponse = await fetch(`/report?id=${taskId}`, { method: 'HEAD' });
                                if (reportResponse.ok) {
                                    // Report exists - task completed successfully
                                    statusElement.textContent = 'Completed';
                                    statusElement.parentElement.className = 'status success';
                                    progressDetails.textContent = 'Build and tests completed successfully - Report available';
                                    progressFill.style.width = '100%';
                                    
                                    // Stop monitoring and hide refresh button
                                    const refreshBtn = document.getElementById('refreshStatusBtn');
                                    if (refreshBtn) {
                                        refreshBtn.style.display = 'none';
                                    }
                                    
                                    // Clear session storage
                                    sessionStorage.removeItem('activeTaskId');
                                    sessionStorage.removeItem('activeRequest');
                                    
                                    showToast('Build completed successfully! Report is available.', 'success');
                                    return;
                                }
                            } catch (reportError) {
                                console.warn('Could not check report existence:', reportError);
                            }
                        }
                        
                        // Task not found but no report - unclear completion state
                        statusElement.textContent = 'Completed';
                        statusElement.parentElement.className = 'status success';
                        progressDetails.textContent = 'Build session completed (status lost)';
                        progressFill.style.width = '100%';
                        
                        // Stop monitoring and hide refresh button
                        const refreshBtn = document.getElementById('refreshStatusBtn');
                        if (refreshBtn) {
                            refreshBtn.style.display = 'none';
                        }
                        
                        // Clear session storage
                        sessionStorage.removeItem('activeTaskId');
                        sessionStorage.removeItem('activeRequest');
                        
                        showToast('Build session has completed', 'info');
                        return;
                    }
                    
                    if (response.ok && statusData) {
                        // Update status based on response
                        if (statusData.status === 'completed') {
                            statusElement.textContent = 'Completed';
                            statusElement.parentElement.className = 'status success';
                            progressDetails.textContent = `All ${totalTasks} tasks completed successfully`;
                            progressFill.style.width = '100%';
                            
                            // Stop polling and hide refresh button
                            const refreshBtn = document.getElementById('refreshStatusBtn');
                            if (refreshBtn) {
                                refreshBtn.style.display = 'none';
                            }
                            
                            // Clear session storage
                            sessionStorage.removeItem('activeTaskId');
                            sessionStorage.removeItem('activeRequest');
                            
                            showToast('Build and tests completed successfully!', 'success');
                            
                        } else if (statusData.status === 'failed' || statusData.status === 'error') {
                            statusElement.textContent = 'Failed';
                            statusElement.parentElement.className = 'status offline';
                            progressDetails.textContent = statusData.message || 'Build or tests failed';
                            
                            // Stop polling and hide refresh button
                            const refreshBtn = document.getElementById('refreshStatusBtn');
                            if (refreshBtn) {
                                refreshBtn.style.display = 'none';
                            }
                            
                            // Clear session storage
                            sessionStorage.removeItem('activeTaskId');
                            sessionStorage.removeItem('activeRequest');
                            
                            showToast('Build or tests failed', 'error');
                            
                        } else if (statusData.status === 'running' || statusData.status === 'building') {
                            // Update progress details
                            if (statusData.currentPhase) {
                                statusElement.textContent = `${statusData.currentPhase}...`;
                                progressDetails.textContent = statusData.currentTask || 
                                    `Processing ${statusData.currentPhase.toLowerCase()}...`;
                            } else {
                                statusElement.textContent = 'Building...';
                                progressDetails.textContent = 'Building commits and running tests...';
                            }
                            
                            // Calculate progress
                            if (statusData.progress !== undefined) {
                                progressFill.style.width = `${statusData.progress}%`;
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
                            
                            // Show current build/test details if available
                            if (statusData.currentCommit && statusData.currentTest) {
                                progressDetails.textContent = 
                                    `Testing ${statusData.currentTest} on ${statusData.currentCommit.substring(0, 7)}`;
                            } else if (statusData.currentCommit) {
                                progressDetails.textContent = 
                                    `Building commit ${statusData.currentCommit.substring(0, 7)}`;
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
            pollStatus();
            
            // Store intervals for cleanup if needed
            window.statusMonitorIntervals = {};
            
            // Make pollStatus available globally for manual refresh
            window.refreshBuildStatus = pollStatus;
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
                    } else {
                        // Session is no longer active, clear storage
                        sessionStorage.removeItem('activeTaskId');
                        sessionStorage.removeItem('activeRequest');
                    }
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
