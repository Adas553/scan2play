// "Plan imprezy" (V77): the plan's form (plan-form.js) on the hosts' page and the DJ's page — "+ Dodaj moment" adds an empty row
// with fields of their own, ↑ / ↓ move a row (the order on the page is the order saved), ✕ removes one (the last one is emptied),
// the plan's limit hides "+ Dodaj moment"; one spare empty row with the script; on a phone the time and the name on one line; a change
// not saved marks the form (data-plan-dirty: the page asks before it is left) — and the print's "Drukuj" opens the browser's print,
// the print nothing sideways on a small phone.
(function () {
    /** Nothing wider than the screen: measured on the document, not window.innerWidth (CLAUDE.md, "Mind" 2). */
    const fits = function () {
        return document.documentElement.scrollWidth <= document.documentElement.clientWidth;
    };
    const rows = function () {
        return Array.from(document.querySelectorAll('#planMoments [data-plan-row]'));
    };
    const labels = function () {
        return rows().map(function (row) { return row.querySelector('input[name="label"]').value; });
    };

    const rowsWork = async function (t) {
        const add = document.getElementById('planAdd');
        const form = document.getElementById('planForm');
        t.check('nothing sideways', fits());
        const isEmpty = function (row) {
            return Array.from(row.querySelectorAll('input')).every(function (input) { return input.value === ''; });
        };
        t.step('one spare empty row with the script', rows().filter(isEmpty).length, 1);
        const time = rows()[0].querySelector('input[name="time"]').getBoundingClientRect();
        const name = rows()[0].querySelector('input[name="label"]').getBoundingClientRect();
        t.check('the time and the name on one line', Math.abs(time.top - name.top) < 2 && time.right <= name.left);
        t.step('nothing changed yet', form.dataset.planDirty, 'false');
        t.check('"+ Dodaj moment" shown', !add.hidden);
        t.check('↑ ↓ ✕ shown on every row', rows().every(function (row) { return !row.querySelector('[data-plan-tools]').hidden; }));
        const before = rows().length;

        add.click();
        t.step('a row added', rows().length, before + 1);
        const added = rows()[rows().length - 1];
        t.check('the new row empty', Array.from(added.querySelectorAll('input')).every(function (input) { return input.value === ''; }));
        t.check('the new row\'s fields have ids of their own', Array.from(added.querySelectorAll('input')).every(function (input) {
            return document.querySelectorAll('#' + CSS.escape(input.id)).length === 1;
        }));
        t.check('the new row\'s name has the focus', document.activeElement === added.querySelector('input[name="label"]'));

        // the second moment moved up, then down again
        const first = labels()[0];
        const second = labels()[1];
        rows()[1].querySelector('[data-plan-up]').click();
        t.step('↑ moved the second moment first', labels().slice(0, 2), [second, first]);
        rows()[0].querySelector('[data-plan-down]').click();
        t.step('↓ moved it back', labels().slice(0, 2), [first, second]);
        t.step('a move is a change not saved', form.dataset.planDirty, 'true');
        // the form sends the rows in the page's order
        t.step('the form sends the names in the page\'s order', new FormData(document.getElementById('planForm')).getAll('label').slice(0, 2),
            [first, second]);

        rows()[0].querySelector('[data-plan-remove]').click();
        t.step('✕ removed the first moment', labels()[0], second);

        // up to the plan's limit: then no more rows, and the button hidden
        const max = Number(document.getElementById('planForm').dataset.planMax);
        for (let i = 0; i < max && rows().length < max && !add.hidden; i++) add.click();
        t.step('rows up to the plan\'s limit', rows().length, max);
        t.check('"+ Dodaj moment" hidden at the limit', add.hidden);
        for (let i = 0; i < max && rows().length > 1; i++) rows()[0].querySelector('[data-plan-remove]').click();
        rows()[0].querySelector('input[name="label"]').value = 'Tort';
        rows()[0].querySelector('[data-plan-remove]').click();
        t.step('the last row emptied, not removed', [rows().length, labels()[0]], [1, '']);
        t.check('"+ Dodaj moment" back under the limit', !add.hidden);
        t.check('nothing sideways after it all', fits());
    };

    S2P.scenario({
        name: 'event-plan-hosts-rows',
        title: 'the hosts\' plan on a phone (390 × 844): a moment added, moved up and down, removed; the limit hides "+ Dodaj moment"',
        page: 'host-plan',
        viewport: '390,844',
        run: rowsWork
    });

    S2P.scenario({
        name: 'event-plan-dj-rows-320',
        title: 'the DJ\'s plan on a small phone (320 × 640): the same rows, nothing sideways',
        page: 'event-plan',
        viewport: '320,640',
        run: rowsWork
    });

    S2P.scenario({
        name: 'event-plan-print-button',
        title: 'the plan\'s print: "Drukuj" opens the browser\'s print',
        page: 'event-plan-print',
        viewport: '1280,900',
        run: async function (t) {
            let printed = 0;
            window.print = function () { printed++; };
            document.querySelector('[data-plan-print]').click();
            t.step('the browser\'s print opened once', printed, 1);
        }
    });

    S2P.scenario({
        name: 'event-plan-print-fits-320',
        title: 'the plan\'s print looked at on a small phone (320 × 640): nothing sideways',
        page: 'event-plan-print',
        viewport: '320,640',
        run: async function (t) {
            t.check('nothing sideways', fits());
        }
    });
})();
