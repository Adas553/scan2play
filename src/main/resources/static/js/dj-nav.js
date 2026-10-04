// The DJ navigation of every DJ page (fragments/components.html, "dj-nav"): the confirmation of the account buttons (end the party,
// log out, delete the account) and the feedback form. A file of its own, not inline scripts or onsubmit attributes, for the
// Content-Security-Policy (review 5.1). Loaded as a plain script inside the fragment, so it runs before the dashboard's modules.

// A form with data-confirm asks first. In the capture phase on the document, so it runs before any other submit listener — the
// dashboard's forms.js sends the panel's forms by fetch() and leaves alone a submit that was cancelled here.
document.addEventListener('submit', function (e) {
    var form = e.target.closest ? e.target.closest('form[data-confirm]') : null;
    if (form && !window.confirm(form.getAttribute('data-confirm'))) {
        e.preventDefault();
        e.stopImmediatePropagation();
    }
}, true);

// ---- Feedback: self-contained, no dependency on the dashboard's modules ----
    document.addEventListener('DOMContentLoaded', function () {
        var textarea  = document.getElementById('feedbackMessage');
        var submitBtn = document.getElementById('feedbackSubmitBtn');
        var charCount = document.getElementById('feedbackCharCount');
        var errorEl   = document.getElementById('feedbackError');
        var toastEl   = document.getElementById('feedbackToast');
        var toastBody = document.getElementById('feedbackToastBody');

        if (!textarea || !submitBtn) return;

        // --- Character counter ---
        textarea.addEventListener('input', function () {
            charCount.textContent = this.value.length;
        });

        // --- CSRF from <meta> tags (present on all DJ pages) ---
        function getCsrf() {
            var t = document.querySelector('meta[name="_csrf"]');
            var h = document.querySelector('meta[name="_csrf_header"]');
            return {
                token:  t ? t.getAttribute('content') : '',
                header: h ? h.getAttribute('content') : 'X-CSRF-TOKEN'
            };
        }

        // --- Toast helper ---
        function showToast(message, success) {
            toastBody.textContent = message;
            toastEl.className = 'toast text-white border-0 ' + (success ? 'bg-success' : 'bg-danger');
            new bootstrap.Toast(toastEl, { delay: 4000 }).show();
        }

        // --- Submit ---
        submitBtn.addEventListener('click', function () {
            var message = textarea.value.trim();

            // Reset validation state
            errorEl.textContent = '';
            errorEl.classList.add('d-none');

            if (!message) {
                errorEl.textContent = textarea.getAttribute('placeholder') || 'Please enter a message.';
                errorEl.classList.remove('d-none');
                textarea.focus();
                return;
            }

            // partyCode is stored as data attribute on the button that opened the modal
            var partyCode = document.querySelector('[data-bs-target="#feedbackModal"]')
                                    ?.getAttribute('data-party-code') || '';

            var csrf = getCsrf();
            var fd   = new FormData();
            fd.append('partyCode', partyCode);
            fd.append('message', message);

            submitBtn.disabled = true;

            fetch('/dj/feedback', {
                method: 'POST',
                headers: { [csrf.header]: csrf.token },
                body: fd
            })
            .then(function (r) {
                if (!r.ok) throw new Error('HTTP ' + r.status);
                return r.json();
            })
            .then(function () {
                var modal = bootstrap.Modal.getInstance(document.getElementById('feedbackModal'));
                if (modal) modal.hide();
                textarea.value = '';
                charCount.textContent = '0';
                showToast(submitBtn.getAttribute('data-success-msg'), true);
            })
            .catch(function (err) {
                console.error('[Feedback] Submission error:', err);
                showToast(submitBtn.getAttribute('data-error-msg'), false);
            })
            .finally(function () {
                submitBtn.disabled = false;
            });
        });
    });
