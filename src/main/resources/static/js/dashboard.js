/**
 * Dashboard Core JS — handles all DJ panel interactions.
 *
 * Responsibilities:
 *   - AJAX form submissions (preserves YouTube player on POST actions)
 *   - AJAX tab switching (Queue ↔ History without page reload)
 *   - Table polling (refreshes queue every 5 seconds)
 *   - Clipboard (copy party link)
 *
 * Dependencies (DOM):
 *   - <meta name="_csrf">          CSRF token
 *   - <meta name="_csrf_header">   CSRF header name
 *   - <input id="partyCode">       current party code
 *   - <div id="yt-player-card">    presence = YouTube provider
 *   - <tbody id="song-list">       queue table body
 *   - <div id="queue-content">     queue section wrapper
 *   - <div id="history-content">   AJAX-loaded history container
 */

// ==========================================================================
// HELPERS
// ==========================================================================

/** Returns CSRF token and header name from <meta> tags. */
function getCsrf() {
    return {
        token:  document.querySelector('meta[name="_csrf"]').getAttribute('content'),
        header: document.querySelector('meta[name="_csrf_header"]').getAttribute('content')
    };
}

/** True when the YouTube embedded player is present on the page. */
function isYouTubeProvider() {
    return !!document.getElementById('yt-player-card');
}

// ==========================================================================
// AJAX FORM INTERCEPTOR (YouTube only)
//
// When YouTube provider is active, most POST forms on the dashboard
// are submitted via fetch() to avoid a full page reload that would
// destroy the YouTube IFrame player.
//
// Excluded: logout, end-party, start-party (page reload is expected).
// ==========================================================================

(function initAjaxFormInterceptor() {
    if (!isYouTubeProvider()) return;

    document.addEventListener('submit', function(e) {
        const form = e.target.closest('form');
        if (!form || form.method.toLowerCase() !== 'post') return;
        if (!form.closest('.container')) return;

        // Allow these actions to do a full page reload
        const action = form.action || '';
        if (action.includes('/logout') || action.includes('/end-party') || action.includes('/start-party')) return;

        // Auto-Pilot toggle has its own AJAX handler — skip
        if (form.id === 'autoPilotForm') return;

        e.preventDefault();
        const csrf = getCsrf();

        fetch(action, {
            method: 'POST',
            headers: { [csrf.header]: csrf.token },
            body: new FormData(form),
            redirect: 'manual'
        }).then(function() {
            // Flash the submit button green briefly as confirmation
            const btn = form.querySelector('button[type="submit"]');
            if (btn) {
                const original = btn.textContent;
                btn.textContent = '✓';
                btn.classList.add('btn-success');
                setTimeout(function() {
                    btn.textContent = original;
                    btn.classList.remove('btn-success');
                }, 1500);
            }
        }).catch(function(err) {
            console.error('[Dashboard] AJAX form error:', err);
        });
    });
})();

// ==========================================================================
// AUTO-PILOT TOGGLE
//
// Called from: <input onchange="submitAutoPilotToggle(this)">
// YouTube: AJAX POST + local UI update (no reload).
// Spotify: standard form submit (page reload is fine).
// ==========================================================================

function submitAutoPilotToggle(checkbox) {
    if (!isYouTubeProvider()) {
        checkbox.form.submit();
        return;
    }

    const csrf = getCsrf();
    const formData = new FormData(checkbox.form);

    fetch('/dj/dashboard/playback-mode', {
        method: 'POST',
        headers: { [csrf.header]: csrf.token },
        body: formData,
        redirect: 'manual'
    }).then(function() {
        const tbody = document.getElementById('song-list');
        if (tbody) {
            const newMode = checkbox.checked ? 'AUTO' : 'MANUAL';
            tbody.setAttribute('data-playback-mode', newMode);
            console.log('[Auto-Pilot] Mode toggled to: ' + newMode);
            if (newMode === 'AUTO' && typeof checkYouTubeAutoPlay === 'function') {
                checkYouTubeAutoPlay();
            }
        }
    }).catch(function(err) {
        console.error('[Auto-Pilot] Toggle error:', err);
        checkbox.checked = !checkbox.checked;
    });
}

// ==========================================================================
// AJAX TAB SWITCHING (YouTube only)
//
// Loads History tab content via AJAX into #history-content,
// toggling visibility with #queue-content.
// YouTube player stays alive across tab switches.
// When Spotify is the provider, normal page navigation is used.
// ==========================================================================

(function initTabSwitching() {
    if (!isYouTubeProvider()) return;

    const historyLink = document.querySelector('a[href="/dj/history-view"]');
    const queueLink   = document.querySelector('a[href="/dj/dashboard"]');
    if (!historyLink || !queueLink) return;

    const queueContent   = document.getElementById('queue-content');
    const historyContent = document.getElementById('history-content');
    let activeTab = 'queue';

    historyLink.addEventListener('click', function(e) {
        e.preventDefault();
        if (activeTab === 'history') return;

        const partyCode = document.getElementById('partyCode').value;
        const csrf = getCsrf();

        fetch('/dj/history-view/fragment?partyCode=' + encodeURIComponent(partyCode), {
            headers: { [csrf.header]: csrf.token }
        })
        .then(function(r) { return r.text(); })
        .then(function(html) {
            historyContent.innerHTML = html;
            queueContent.style.display = 'none';
            historyContent.style.display = '';
            historyLink.classList.add('active');
            queueLink.classList.remove('active');
            activeTab = 'history';
        })
        .catch(function(err) { console.error('[Tabs] History load error:', err); });
    });

    queueLink.addEventListener('click', function(e) {
        e.preventDefault();
        if (activeTab === 'queue') return;
        queueContent.style.display = '';
        historyContent.style.display = 'none';
        queueLink.classList.add('active');
        historyLink.classList.remove('active');
        activeTab = 'queue';
    });
})();

// ==========================================================================
// TABLE POLLING — refreshes the queue table every 5 seconds
// ==========================================================================

(function initPolling() {
    async function refreshTable() {
        try {
            const partyCodeEl = document.getElementById('partyCode');
            if (!partyCodeEl) return;

            const csrf = getCsrf();
            const response = await fetch('/dj/dashboard/updates?partyCode=' + partyCodeEl.value, {
                method: 'GET',
                headers: { [csrf.header]: csrf.token }
            });

            if (response.redirected) {
                window.location.reload();
                return;
            }
            if (!response.ok) throw new Error('HTTP ' + response.status);

            const html = await response.text();

            // Use <table> as temp container — <div>.innerHTML strips tbody/tr/td
            const tempTable = document.createElement('table');
            tempTable.innerHTML = html;
            const newTbody = tempTable.querySelector('#song-list');
            if (newTbody) {
                document.getElementById('song-list').replaceWith(newTbody);
            } else {
                document.getElementById('song-list').innerHTML = html;
            }

            if (typeof checkYouTubeAutoPlay === 'function') {
                checkYouTubeAutoPlay();
            }
        } catch (err) {
            console.error('[Polling] Refresh error:', err);
        } finally {
            setTimeout(refreshTable, 5000);
        }
    }

    setTimeout(refreshTable, 5000);
})();

// ==========================================================================
// COPY PARTY LINK
// ==========================================================================

function copyPartyLink() {
    const input = document.getElementById('partyLinkInput');
    input.select();
    input.setSelectionRange(0, 99999);
    navigator.clipboard.writeText(input.value).then(function() {
        const btn = input.nextElementSibling;
        const original = btn.innerText;
        const copied = btn.getAttribute('data-copied') || 'Copied!';
        btn.innerText = copied;
        setTimeout(function() { btn.innerText = original; }, 2000);
    });
}

