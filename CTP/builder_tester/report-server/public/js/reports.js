/**
 * Reports Page - Client-side logic for reports management
 */

(function() {
    'use strict';

    // State
    const state = {
        currentPage: 1,
        pageSize: 25,
        totalItems: 0,
        totalPages: 0,
        filters: {
            q: '',
            sort: 'modified',
            order: 'desc',
            from: null,
            to: null
        },
        selectedIds: new Set(),
        reports: []
    };

    // DOM Elements
    let elements = {};

    // Initialize
    function init() {
        cacheElements();
        loadStateFromURL();
        attachEventListeners();
        loadReports();
    }

    function cacheElements() {
        elements = {
            loading: document.getElementById('loading'),
            tbody: document.getElementById('reports-tbody'),
            table: document.getElementById('reports-table'),
            emptyState: document.getElementById('empty-state'),
            paginationInfo: document.getElementById('pagination-info-text'),
            paginationControls: document.getElementById('pagination-controls'),

            // Filters
            filterSearch: document.getElementById('filter-search'),
            filterFrom: document.getElementById('filter-from'),
            filterTo: document.getElementById('filter-to'),
            filterSort: document.getElementById('filter-sort'),
            filterPageSize: document.getElementById('filter-pagesize'),
            btnApplyFilters: document.getElementById('btn-apply-filters'),
            btnResetFilters: document.getElementById('btn-reset-filters'),

            // Bulk actions
            selectAll: document.getElementById('select-all'),
            thSelectAll: document.getElementById('th-select-all'),
            selectedCount: document.getElementById('selected-count'),
            btnDeleteSelected: document.getElementById('btn-delete-selected'),
            btnBulkMenu: document.getElementById('btn-bulk-menu'),
            bulkMenu: document.getElementById('bulk-menu'),

            // Modals
            modal: document.getElementById('modal'),
            modalTitle: document.getElementById('modal-title'),
            modalMessage: document.getElementById('modal-message'),
            modalPreview: document.getElementById('modal-preview'),
            modalConfirm: document.getElementById('modal-confirm'),
            modalCancel: document.getElementById('modal-cancel'),

            inputModal: document.getElementById('input-modal'),
            inputModalTitle: document.getElementById('input-modal-title'),
            inputModalMessage: document.getElementById('input-modal-message'),
            inputModalLabel: document.getElementById('input-modal-label'),
            inputModalValue: document.getElementById('input-modal-value'),
            inputModalDryRun: document.getElementById('input-modal-dryrun'),
            inputModalConfirm: document.getElementById('input-modal-confirm'),
            inputModalCancel: document.getElementById('input-modal-cancel'),

            toast: document.getElementById('toast')
        };
    }

    function loadStateFromURL() {
        const params = new URLSearchParams(window.location.search);
        state.currentPage = parseInt(params.get('page')) || 1;
        state.pageSize = parseInt(params.get('pageSize')) || 25;
        state.filters.q = params.get('q') || '';
        state.filters.from = params.get('from') || null;
        state.filters.to = params.get('to') || null;

        const sort = params.get('sort') || 'modified-desc';
        const [sortField, sortOrder] = sort.split('-');
        state.filters.sort = sortField;
        state.filters.order = sortOrder;

        // Update UI to match state
        elements.filterSearch.value = state.filters.q;
        elements.filterFrom.value = state.filters.from || '';
        elements.filterTo.value = state.filters.to || '';
        elements.filterSort.value = `${state.filters.sort}-${state.filters.order}`;
        elements.filterPageSize.value = state.pageSize;
    }

    function updateURL() {
        const params = new URLSearchParams();
        params.set('page', state.currentPage);
        params.set('pageSize', state.pageSize);
        if (state.filters.q) params.set('q', state.filters.q);
        if (state.filters.from) params.set('from', state.filters.from);
        if (state.filters.to) params.set('to', state.filters.to);
        params.set('sort', `${state.filters.sort}-${state.filters.order}`);

        const newURL = window.location.pathname + '?' + params.toString();
        window.history.pushState({}, '', newURL);
    }

    function attachEventListeners() {
        // Filter actions
        if (elements.btnApplyFilters) {
            elements.btnApplyFilters.addEventListener('click', applyFilters);
        }
        if (elements.btnResetFilters) {
            elements.btnResetFilters.addEventListener('click', resetFilters);
        }

        // Enter key in search box
        if (elements.filterSearch) {
            elements.filterSearch.addEventListener('keypress', (e) => {
                if (e.key === 'Enter') applyFilters();
            });
        }

        // Select all checkboxes
        if (elements.selectAll) {
            elements.selectAll.addEventListener('change', toggleSelectAll);
        }
        if (elements.thSelectAll) {
            elements.thSelectAll.addEventListener('change', toggleSelectAll);
        }

        // Delete selected
        if (elements.btnDeleteSelected) {
            elements.btnDeleteSelected.addEventListener('click', deleteSelected);
        }

        // Bulk menu toggle
        if (elements.btnBulkMenu) {
            elements.btnBulkMenu.addEventListener('click', () => {
                if (elements.bulkMenu) {
                    elements.bulkMenu.classList.toggle('show');
                }
            });
        }

        // Close bulk menu when clicking outside
        document.addEventListener('click', (e) => {
            if (elements.bulkMenu && !e.target.closest('.bulk-dropdown')) {
                elements.bulkMenu.classList.remove('show');
            }
        });

        // Bulk action buttons
        if (elements.bulkMenu) {
            elements.bulkMenu.querySelectorAll('[data-action]').forEach(btn => {
                btn.addEventListener('click', (e) => {
                    const action = e.target.dataset.action;
                    handleBulkAction(action);
                    if (elements.bulkMenu) {
                        elements.bulkMenu.classList.remove('show');
                    }
                });
            });
        }

        // Modal cancel buttons
        if (elements.modalCancel) {
            elements.modalCancel.addEventListener('click', closeModal);
        }
        if (elements.inputModalCancel) {
            elements.inputModalCancel.addEventListener('click', closeInputModal);
        }
    }

    function applyFilters() {
        const sortValue = elements.filterSort.value;
        const [sortField, sortOrder] = sortValue.split('-');

        state.filters.q = elements.filterSearch.value.trim();
        state.filters.from = elements.filterFrom.value || null;
        state.filters.to = elements.filterTo.value || null;
        state.filters.sort = sortField;
        state.filters.order = sortOrder;
        state.pageSize = parseInt(elements.filterPageSize.value);
        state.currentPage = 1; // Reset to first page

        loadReports();
    }

    function resetFilters() {
        state.filters = {
            q: '',
            sort: 'modified',
            order: 'desc',
            from: null,
            to: null
        };
        state.pageSize = 25;
        state.currentPage = 1;

        elements.filterSearch.value = '';
        elements.filterFrom.value = '';
        elements.filterTo.value = '';
        elements.filterSort.value = 'modified-desc';
        elements.filterPageSize.value = '25';

        loadReports();
    }

    function loadReports() {
        showLoading(true);
        clearSelection();

        const params = new URLSearchParams({
            page: state.currentPage,
            pageSize: state.pageSize,
            sort: state.filters.sort,
            order: state.filters.order
        });

        if (state.filters.q) params.set('q', state.filters.q);
        if (state.filters.from) params.set('from', state.filters.from);
        if (state.filters.to) params.set('to', state.filters.to);

        fetch(`/api/reports?${params.toString()}`)
            .then(response => response.json())
            .then(data => {
                state.reports = data.items || [];
                state.totalItems = data.totalItems || 0;
                state.totalPages = data.totalPages || 0;
                state.currentPage = data.page || 1;

                renderTable();
                renderPagination();
                updateURL();
                showLoading(false);
            })
            .catch(err => {
                console.error('Error loading reports:', err);
                showToast('Error loading reports', 'error');
                showLoading(false);
            });
    }

    function renderTable() {
        elements.tbody.innerHTML = '';

        if (state.reports.length === 0) {
            elements.table.style.display = 'none';
            elements.emptyState.style.display = 'block';
            return;
        }

        elements.table.style.display = 'table';
        elements.emptyState.style.display = 'none';

        state.reports.forEach(report => {
            const row = createTableRow(report);
            elements.tbody.appendChild(row);
        });
    }

    function createTableRow(report) {
        const tr = document.createElement('tr');
        tr.dataset.id = report.id;

        // Checkbox
        const tdCheckbox = document.createElement('td');
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.className = 'row-checkbox';
        checkbox.dataset.id = report.id;
        checkbox.checked = state.selectedIds.has(report.id);
        checkbox.addEventListener('change', (e) => {
            if (e.target.checked) {
                state.selectedIds.add(report.id);
            } else {
                state.selectedIds.delete(report.id);
            }
            updateSelectionUI();
        });
        tdCheckbox.appendChild(checkbox);
        tr.appendChild(tdCheckbox);

        // Request ID
        const tdId = document.createElement('td');
        const link = document.createElement('a');
        link.href = `/report?id=${report.id}`;
        link.className = 'report-link';
        link.textContent = report.id;
        link.target = '_blank';
        tdId.appendChild(link);
        tr.appendChild(tdId);

        // Date & Time
        const tdDate = document.createElement('td');
        const date = new Date(report.modified);
        tdDate.textContent = date.toLocaleString();
        tdDate.className = 'report-date';
        tr.appendChild(tdDate);

        // Build Type
        const tdBuildType = document.createElement('td');
        tdBuildType.textContent = report.buildType || '-';
        tdBuildType.className = 'report-buildtype';
        tr.appendChild(tdBuildType);

        // Run Mode (or Build Only indicator)
        const tdRunMode = document.createElement('td');
        if (report.buildOnly) {
            tdRunMode.innerHTML = '<span style="color: var(--primary); font-weight: 600;">Build Only</span>';
        } else {
            tdRunMode.textContent = report.runMode || '-';
        }
        tr.appendChild(tdRunMode);

        // Commits
        const tdCommits = document.createElement('td');
        if (report.commitCount > 0) {
            const commitsSpan = document.createElement('span');
            commitsSpan.className = 'commits-info';
            commitsSpan.textContent = report.commitCount === 1
                ? report.firstCommit
                : `${report.firstCommit}...${report.lastCommit} (${report.commitCount})`;
            tdCommits.appendChild(commitsSpan);
        } else {
            tdCommits.textContent = '-';
        }
        tr.appendChild(tdCommits);

        // Test Results (counts)
        const tdResults = document.createElement('td');
        if (report.testCounts) {
            const countsContainer = document.createElement('div');
            countsContainer.className = 'test-counts';

            const counts = report.testCounts;
            const items = [];

            if (counts.pass > 0) {
                items.push({ label: 'P', count: counts.pass, class: 'count-pass' });
            }
            if (counts.fail > 0) {
                items.push({ label: 'F', count: counts.fail, class: 'count-fail' });
            }
            if (counts.error > 0) {
                items.push({ label: 'E', count: counts.error, class: 'count-error' });
            }
            if (counts.flaky > 0) {
                items.push({ label: 'Fl', count: counts.flaky, class: 'count-flaky' });
            }
            if (counts.unstable > 0) {
                items.push({ label: 'U', count: counts.unstable, class: 'count-unstable' });
            }

            if (items.length === 0) {
                tdResults.textContent = '-';
            } else {
                items.forEach(item => {
                    const badge = document.createElement('span');
                    badge.className = `count-badge ${item.class}`;
                    badge.textContent = `${item.label}:${item.count}`;
                    badge.title = `${item.label === 'P' ? 'Pass' : item.label === 'F' ? 'Fail' : item.label === 'E' ? 'Error' : item.label === 'U' ? 'Unstable' : item.label === 'Fl' ? 'Flaky' : item.label}`;
                    countsContainer.appendChild(badge);
                });
                tdResults.appendChild(countsContainer);
            }
        } else {
            tdResults.textContent = '-';
        }
        tr.appendChild(tdResults);

        // Actions
        const tdActions = document.createElement('td');
        tdActions.className = 'actions-cell';

        const btnDelete = document.createElement('button');
        btnDelete.className = 'btn-icon btn-delete';
        btnDelete.innerHTML = '✕';
        btnDelete.title = 'Delete report';
        btnDelete.addEventListener('click', (e) => {
            e.stopPropagation();
            deleteReport(report.id);
        });

        tdActions.appendChild(btnDelete);
        tr.appendChild(tdActions);

        return tr;
    }

    function renderPagination() {
        const startItem = state.totalItems === 0 ? 0 : (state.currentPage - 1) * state.pageSize + 1;
        const endItem = Math.min(state.currentPage * state.pageSize, state.totalItems);

        elements.paginationInfo.textContent = `Showing ${startItem}-${endItem} of ${state.totalItems} reports`;

        elements.paginationControls.innerHTML = '';

        if (state.totalPages <= 1) return;

        // Previous button
        const btnPrev = createPaginationButton('← Prev', state.currentPage - 1, state.currentPage === 1);
        elements.paginationControls.appendChild(btnPrev);

        // Page number buttons
        const pages = generatePageNumbers();
        pages.forEach(page => {
            if (page === '...') {
                const ellipsis = document.createElement('span');
                ellipsis.className = 'pagination-ellipsis';
                ellipsis.textContent = '...';
                elements.paginationControls.appendChild(ellipsis);
            } else {
                const btn = createPaginationButton(page, page, false, page === state.currentPage);
                elements.paginationControls.appendChild(btn);
            }
        });

        // Next button
        const btnNext = createPaginationButton('Next →', state.currentPage + 1, state.currentPage === state.totalPages);
        elements.paginationControls.appendChild(btnNext);
    }

    function generatePageNumbers() {
        const pages = [];
        const currentPage = state.currentPage;
        const totalPages = state.totalPages;

        if (totalPages <= 7) {
            for (let i = 1; i <= totalPages; i++) {
                pages.push(i);
            }
        } else {
            pages.push(1);

            if (currentPage > 3) {
                pages.push('...');
            }

            const start = Math.max(2, currentPage - 1);
            const end = Math.min(totalPages - 1, currentPage + 1);

            for (let i = start; i <= end; i++) {
                pages.push(i);
            }

            if (currentPage < totalPages - 2) {
                pages.push('...');
            }

            pages.push(totalPages);
        }

        return pages;
    }

    function createPaginationButton(label, page, disabled, active = false) {
        const btn = document.createElement('button');
        btn.className = 'pagination-btn' + (active ? ' active' : '');
        btn.textContent = label;
        btn.disabled = disabled;

        if (!disabled && !active) {
            btn.addEventListener('click', () => {
                state.currentPage = page;
                loadReports();
            });
        }

        return btn;
    }

    function toggleSelectAll(e) {
        const checked = e.target.checked;

        // Sync both checkboxes
        elements.selectAll.checked = checked;
        elements.thSelectAll.checked = checked;

        // Update selection
        if (checked) {
            state.reports.forEach(report => state.selectedIds.add(report.id));
        } else {
            state.reports.forEach(report => state.selectedIds.delete(report.id));
        }

        // Update checkboxes in table
        document.querySelectorAll('.row-checkbox').forEach(cb => {
            cb.checked = checked;
        });

        updateSelectionUI();
    }

    function clearSelection() {
        state.selectedIds.clear();
        elements.selectAll.checked = false;
        elements.thSelectAll.checked = false;
        updateSelectionUI();
    }

    function updateSelectionUI() {
        const count = state.selectedIds.size;
        elements.selectedCount.textContent = `${count} selected`;
        elements.btnDeleteSelected.disabled = count === 0;

        // Update "select all" checkbox state
        const allOnPageSelected = state.reports.length > 0 && state.reports.every(r => state.selectedIds.has(r.id));
        elements.selectAll.checked = allOnPageSelected;
        elements.thSelectAll.checked = allOnPageSelected;
    }

    function deleteReport(id) {
        showModal(
            'Delete Report',
            `Are you sure you want to delete report "${id}"? This action cannot be undone.`,
            () => performDelete(id)
        );
    }

    function deleteSelected() {
        const ids = Array.from(state.selectedIds);
        if (ids.length === 0) return;

        showModal(
            'Delete Selected Reports',
            `Are you sure you want to delete ${ids.length} selected report(s)? This action cannot be undone.`,
            () => performBulkDelete('selected', { ids })
        );
    }

    function handleBulkAction(action) {
        if (action === 'delete-newest') {
            showInputModal(
                'Delete Newest N Reports',
                'Enter the number of newest reports to delete:',
                'Number of reports:',
                (count, dryRun) => performBulkDelete('newest', { count, dryRun })
            );
        } else if (action === 'delete-oldest') {
            showInputModal(
                'Delete Oldest N Reports',
                'Enter the number of oldest reports to delete:',
                'Number of reports:',
                (count, dryRun) => performBulkDelete('oldest', { count, dryRun })
            );
        } else if (action === 'delete-daterange') {
            const from = state.filters.from;
            const to = state.filters.to;

            if (!from || !to) {
                showToast('Please set both "From Date" and "To Date" filters first', 'error');
                return;
            }

            showModal(
                'Delete Reports in Date Range',
                `Delete all reports between ${from} and ${to}? This will perform a dry run first.`,
                () => performBulkDelete('dateRange', { from, to, dryRun: true })
            );
        } else if (action === 'keep-last') {
            showInputModal(
                'Keep Last N Reports',
                'Enter the number of newest reports to keep (all others will be deleted):',
                'Number to keep:',
                (count, dryRun) => performBulkDelete('keepLast', { count, dryRun })
            );
        }
    }

    function performDelete(id) {
        fetch(`/api/reports/${id}`, {
            method: 'DELETE'
        })
        .then(response => response.json())
        .then(data => {
            if (data.status === 'deleted') {
                showToast(`Report ${id} deleted successfully`, 'success');
                state.selectedIds.delete(id);
                loadReports();
            } else {
                showToast(`Failed to delete report: ${data.message}`, 'error');
            }
        })
        .catch(err => {
            console.error('Error deleting report:', err);
            showToast('Error deleting report', 'error');
        });
    }

    function performBulkDelete(mode, options) {
        const payload = { mode, ...options };

        fetch('/api/reports/bulk-delete', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(payload)
        })
        .then(response => response.json())
        .then(data => {
            if (data.dryRun) {
                // Show preview and ask for confirmation
                const message = `This will delete ${data.count} report(s). Do you want to proceed?`;
                const preview = data.targetIds.join(', ');

                showModalWithPreview(
                    'Confirm Bulk Delete',
                    message,
                    preview,
                    () => performBulkDelete(mode, { ...options, dryRun: false })
                );
            } else {
                const { summary } = data;
                if (summary.failed > 0) {
                    showToast(`Deleted ${summary.deleted} reports, ${summary.failed} failed`, 'warning');
                } else {
                    showToast(`Successfully deleted ${summary.deleted} report(s)`, 'success');
                }
                clearSelection();
                loadReports();
            }
        })
        .catch(err => {
            console.error('Error bulk deleting reports:', err);
            showToast('Error deleting reports', 'error');
        });
    }

    function showModal(title, message, onConfirm) {
        if (!elements.modal || !elements.modalTitle || !elements.modalMessage || !elements.modalConfirm) {
            return;
        }

        elements.modalTitle.textContent = title;
        elements.modalMessage.textContent = message;
        if (elements.modalPreview) {
            elements.modalPreview.textContent = '';
            elements.modalPreview.style.display = 'none';
        }

        elements.modal.classList.add('show');

        elements.modalConfirm.onclick = () => {
            closeModal();
            onConfirm();
        };
    }

    function showModalWithPreview(title, message, preview, onConfirm) {
        elements.modalTitle.textContent = title;
        elements.modalMessage.textContent = message;
        elements.modalPreview.textContent = preview;
        elements.modalPreview.style.display = 'block';
        elements.modal.classList.add('show');

        elements.modalConfirm.onclick = () => {
            closeModal();
            onConfirm();
        };
    }

    function closeModal() {
        elements.modal.classList.remove('show');
        elements.modalConfirm.onclick = null;
    }

    function showInputModal(title, message, label, onConfirm) {
        elements.inputModalTitle.textContent = title;
        elements.inputModalMessage.textContent = message;
        elements.inputModalLabel.textContent = label;
        elements.inputModalValue.value = '';
        elements.inputModalDryRun.checked = true;
        elements.inputModal.classList.add('show');

        elements.inputModalConfirm.onclick = () => {
            const value = parseInt(elements.inputModalValue.value);
            const dryRun = elements.inputModalDryRun.checked;

            if (!value || value <= 0) {
                showToast('Please enter a valid number', 'error');
                return;
            }

            closeInputModal();
            onConfirm(value, dryRun);
        };
    }

    function closeInputModal() {
        elements.inputModal.classList.remove('show');
        elements.inputModalConfirm.onclick = null;
    }

    function showLoading(show) {
        elements.loading.style.display = show ? 'flex' : 'none';
    }

    function showToast(message, type = 'info') {
        elements.toast.textContent = message;
        elements.toast.className = `toast ${type} show`;

        setTimeout(() => {
            elements.toast.classList.remove('show');
        }, 3000);
    }

    // Initialize on DOM ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
