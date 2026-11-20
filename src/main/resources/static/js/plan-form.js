// "Plan imprezy" (V77): the plan's form on the DJ's page and the hosts' page — "+ Dodaj moment" adds an empty row (up to the plan's
// limit, data-plan-max), ↑ / ↓ move a row, ✕ removes it (the last one is emptied instead). The rows are sent in the order on the page,
// so moving one is all its order needs. Without this script the form still works: its spare empty rows add, an emptied row removes.
// The print page's "Drukuj" button too. A file of its own for the Content-Security-Policy.
(function () {
    document.querySelectorAll('[data-plan-print]').forEach(function (button) {
        button.addEventListener('click', function () { window.print(); });
    });

    const form = document.getElementById('planForm');
    const list = form && form.querySelector('[data-plan-moments]');
    if (!list || form.querySelector('fieldset[disabled]')) return;
    const max = Number(form.dataset.planMax) || 30;
    const add = form.querySelector('[data-plan-add]');
    let made = list.children.length;

    function rows() {
        return Array.from(list.querySelectorAll('[data-plan-row]'));
    }
    function refresh() {
        add.hidden = rows().length >= max;
    }
    function empty(row) {
        row.querySelectorAll('input').forEach(function (input) { input.value = ''; });
    }
    function wire(row) {
        row.querySelector('[data-plan-tools]').hidden = false;
        row.querySelector('[data-plan-up]').addEventListener('click', function () {
            if (row.previousElementSibling) list.insertBefore(row, row.previousElementSibling);
            this.focus();
        });
        row.querySelector('[data-plan-down]').addEventListener('click', function () {
            if (row.nextElementSibling) list.insertBefore(row.nextElementSibling, row);
            this.focus();
        });
        row.querySelector('[data-plan-remove]').addEventListener('click', function () {
            if (rows().length > 1) {
                row.remove();
            } else {
                empty(row);
            }
            refresh();
        });
    }

    rows().forEach(wire);
    add.addEventListener('click', function () {
        if (rows().length >= max) return;
        const row = rows()[0].cloneNode(true);
        empty(row);
        // ids stay unique: a label's "for" and the page's tests find each field by its own
        row.querySelectorAll('input').forEach(function (input) {
            if (input.id) input.id = input.id.replace(/\d+$/, '') + made;
        });
        made++;
        list.appendChild(row);
        wire(row);
        row.querySelector('input[name="label"]').focus();
        refresh();
    });
    refresh();
})();
