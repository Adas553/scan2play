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

// ---- "Kopiuj", one for every DJ page (review 2026-10-14: four copies of it, each failing its own way): a button with
// data-copy-target copies the value of that field (the party link, the hosts' and the staff's link), one with data-copy-text its text
// (the address in "Pomoc i kontakt" and on /pro). "Skopiowano" (data-copied) on the button for a moment — only when it was copied.
// Without the clipboard (plain HTTP, refused) the text is copied the old way, from a field of its own (the one shown may be folded
// away, and a hidden selection copies nothing, though the browser says it did); refused that way too, the text shown is selected for
// the person to copy (the field; the element data-copy-select names, else #helpEmail), and the button says nothing. ----
document.addEventListener('click', function (e) {
    var button = e.target.closest ? e.target.closest('button[data-copy-target], button[data-copy-text]') : null;
    if (!button) return;
    var field = button.hasAttribute('data-copy-target') ? document.getElementById(button.getAttribute('data-copy-target')) : null;
    var text = field ? field.value : button.getAttribute('data-copy-text');
    if (button.s2pCopyLabel === undefined) button.s2pCopyLabel = button.textContent;   // a second tap during "Skopiowano"
    var done = function () {
        button.textContent = button.getAttribute('data-copied') || button.s2pCopyLabel;
        clearTimeout(button.s2pCopyTimer);
        button.s2pCopyTimer = setTimeout(function () {
            button.textContent = button.s2pCopyLabel;
            button.s2pCopyLabel = undefined;
        }, 1500);
    };
    var selectShown = function () {
        if (field) {
            field.focus();
            field.select();
            return;
        }
        var shown = document.getElementById(button.getAttribute('data-copy-select') || 'helpEmail');
        if (!shown) return;
        var range = document.createRange();
        range.selectNodeContents(shown);
        var selection = window.getSelection();
        selection.removeAllRanges();
        selection.addRange(range);
    };
    var theOldWay = function () {
        var copy = document.createElement('textarea');
        copy.value = text;
        copy.setAttribute('readonly', '');
        copy.style.position = 'fixed';
        copy.style.opacity = '0';
        document.body.appendChild(copy);
        copy.focus({ preventScroll: true });
        copy.select();
        var copied = false;
        try {
            copied = document.execCommand('copy');
        } catch (err) {
            copied = false;
        }
        copy.remove();
        if (copied) {
            button.focus({ preventScroll: true });
            done();
        } else {
            selectShown();
        }
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done, theOldWay);
    } else {
        theOldWay();
    }
});

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
