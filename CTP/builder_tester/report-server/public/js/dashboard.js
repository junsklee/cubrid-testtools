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
            info.innerHTML = `
                <div class="commit-sha">${commit.sha.substring(0, 8)}</div>
                <div class="commit-message">${escapeHtml(commit.commit.message.split('\n')[0])}</div>
                <div class="commit-author">${commit.commit.author.name} - ${formatDate(commit.commit.author.date)}</div>
            `;
            
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
            const count = commitMode === 'select' ? selectedCommits.size : 
                          document.getElementById('manualCommitInput').value.split(/[,\n]/).filter(s => s.trim()).length;
            
            const selectedCountSpan = document.getElementById('selectedCount');
            if (selectedCountSpan) {
                selectedCountSpan.textContent = count;
            }
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
            
            if (mode === 'select') {
                document.getElementById('selectModeBtn').classList.add('active');
                document.getElementById('selectCommits').style.display = 'block';
                document.getElementById('manualCommits').style.display = 'none';
            } else {
                document.getElementById('manualModeBtn').classList.add('active');
                document.getElementById('selectCommits').style.display = 'none';
                document.getElementById('manualCommits').style.display = 'block';
            }
            
            updateCommitCount();
            showToast(`Switched to ${mode === 'select' ? 'Browse & Select' : 'Manual Input'} mode`, 'info');
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
                // Gather commits
                let commitShas = [];
                if (commitMode === 'select') {
                    commitShas = Array.from(selectedCommits);
                } else {
                    const manualInput = document.getElementById('manualCommitInput').value;
                    commitShas = manualInput.split(/[,\n]/)
                        .map(s => s.trim())
                        .filter(s => s.length > 0);
                    
                    // Validate manual commits
                    showToast('Validating commits...', 'info');
                    const valid = await validateCommits(commitShas);
                    if (!valid) {
                        throw new Error('Invalid commits detected');
                    }
                }
                
                if (commitShas.length === 0) {
                    throw new Error('Please select or enter at least one commit');
                }
                
                // Gather tests
                const testsInput = document.getElementById('testsInput').value;
                const tests = testsInput.split('\n')
                    .map(s => s.trim())
                    .filter(s => s.length > 0);
                
                if (tests.length === 0) {
                    throw new Error('Please enter at least one test case');
                }
                
                // Validate callback URL
                if (!validateCallbackUrl()) {
                    throw new Error('Invalid callback URL');
                }
                
                // Build request payload
                const payload = {
                    commits: commitShas,
                    tests: tests,
                    callbackUrl: document.getElementById('callbackUrl').value,
                    workerIps: workers,
                    buildType: document.getElementById('buildType').value,
                    timeout: parseInt(document.getElementById('timeout').value),
                    runMode: document.getElementById('runMode').value,
                    minRuns: parseInt(document.getElementById('minRuns').value),
                    maxRuns: parseInt(document.getElementById('maxRuns').value)
                };
                
                // Add environment variables if provided
                const envVars = document.getElementById('envVars').value.trim();
                if (envVars) {
                    try {
                        payload.envVars = JSON.parse(envVars);
                    } catch {
                        throw new Error('Invalid JSON in environment variables');
                    }
                }
                
                // Display request
                displayResponse('Request sent:\n' + JSON.stringify(payload, null, 2));
                
                // Send request (in real implementation)
                showToast('Build request sent successfully!', 'success');
                
                // Start monitoring
                startStatusMonitoring(payload);
                
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

        // Start status monitoring
        function startStatusMonitoring(request) {
            const monitor = document.getElementById('statusMonitor');
            monitor.innerHTML = `
                <div class="info-item">
                    <div class="info-label">Status</div>
                    <div class="status checking">
                        <span class="status-dot"></span>
                        Building...
                    </div>
                </div>
                <div class="info-item">
                    <div class="info-label">Started</div>
                    <div class="info-value">${new Date().toLocaleString()}</div>
                </div>
                <div class="info-item">
                    <div class="info-label">Commits</div>
                    <div class="info-value">${request.commits.length}</div>
                </div>
                <div class="info-item">
                    <div class="info-label">Tests</div>
                    <div class="info-value">${request.tests.length}</div>
                </div>
            `;
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
            
            // Simulate loading builder configuration
            setTimeout(() => {
                builderInfo.innerHTML = `
                    <div class="info-item">
                        <div class="info-label">Status</div>
                        <div class="status online">
                            <span class="status-dot"></span>
                            Online
                        </div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Version</div>
                        <div class="info-value">2.4.1</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Host</div>
                        <div class="info-value">localhost:8088</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Workers</div>
                        <div class="info-value">4 active</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Queue Size</div>
                        <div class="info-value">0</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Uptime</div>
                        <div class="info-value">24h 35m</div>
                    </div>
                `;
            }, 1000);
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
            
            // Simulate checking tester status
            setTimeout(() => {
                testerInfo.innerHTML = `
                    <div class="info-item">
                        <div class="info-label">Status</div>
                        <div class="status online">
                            <span class="status-dot"></span>
                            Connected
                        </div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Address</div>
                        <div class="info-value">${testerIp}</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Version</div>
                        <div class="info-value">2.4.1</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Platform</div>
                        <div class="info-value">Linux x86_64</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">CPU Cores</div>
                        <div class="info-value">8</div>
                    </div>
                    <div class="info-item">
                        <div class="info-label">Memory</div>
                        <div class="info-value">16 GB</div>
                    </div>
                `;
                showToast('Tester status retrieved successfully', 'success');
            }, 1500);
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
