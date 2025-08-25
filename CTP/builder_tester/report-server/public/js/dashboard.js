/**
 * Dashboard Client-Side JavaScript
 */

document.addEventListener('DOMContentLoaded', function() {
    const buildForm = document.getElementById('buildForm');
    
    if (buildForm) {
        buildForm.addEventListener('submit', async function(e) {
            e.preventDefault();
            await submitBuildRequest();
        });
    }
    
    // Load initial data
    loadGitHubCommits();
});

/**
 * Submit build request
 */
async function submitBuildRequest() {
    const button = document.querySelector('#buildForm button[type="submit"]');
    const originalText = button.textContent;
    
    try {
        button.disabled = true;
        button.textContent = 'Processing...';
        
        // Get form data
        const commitsText = document.getElementById('commits').value;
        const testsText = document.getElementById('tests').value;        const workerIps = document.getElementById('workerIps').value;
        const buildType = document.getElementById('buildType').value;
        const callbackUrl = document.getElementById('callbackUrl').value;
        
        // Parse commits and tests
        const commits = commitsText.split(/[\n,]/)
            .map(s => s.trim())
            .filter(s => s.length > 0);
            
        const tests = testsText.split('\n')
            .map(s => s.trim())
            .filter(s => s.length > 0);
            
        const workers = workerIps.split(',')
            .map(s => s.trim())
            .filter(s => s.length > 0);
        
        if (commits.length === 0) {
            throw new Error('Please enter at least one commit');
        }
        
        if (tests.length === 0) {
            throw new Error('Please enter at least one test');
        }
        
        // Prepare payload
        const payload = {
            commits,
            tests,
            callbackUrl,
            workerIps: workers,
            buildType,
            timeout: 7200,
            runMode: 'until-pass',
            minRuns: 1,
            maxRuns: 3
        };
        
        // Submit request
        const response = await fetch('/api/builder/build', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(payload)
        });
        
        const result = await response.json();
        
        if (!response.ok) {
            throw new Error(result.error || 'Request failed');
        }
        
        showNotification('Build request submitted successfully!', 'success');
        
        // Clear form
        document.getElementById('commits').value = '';
        document.getElementById('tests').value = '';
        
    } catch (err) {
        showNotification(err.message, 'error');
    } finally {
        button.disabled = false;
        button.textContent = originalText;
    }
}

/**
 * Load GitHub commits
 */
async function loadGitHubCommits() {
    try {
        const commits = await window.loadCommits();
        console.log('Loaded commits:', commits.length);
    } catch (err) {
        console.error('Failed to load commits:', err);
    }
}

/**
 * Show notification
 */
function showNotification(message, type = 'info') {
    // Create notification element
    const notification = document.createElement('div');
    notification.className = `notification notification-${type}`;
    notification.textContent = message;
    notification.style.cssText = `
        position: fixed;
        top: 20px;
        right: 20px;
        padding: 1rem 1.5rem;
        background: white;
        border-radius: 0.5rem;
        box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
        z-index: 1000;
        animation: slideIn 0.3s ease-out;
    `;
    
    document.body.appendChild(notification);
    
    // Remove after 3 seconds
    setTimeout(() => {
        notification.remove();
    }, 3000);
}