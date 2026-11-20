/**
 * "Plan wieczoru" above the queue ("Plan imprezy", V77, variant B): the next moment of tonight's plan — the first one not ticked
 * off — with its time counted down ("za 12 min", "za 1 h 05 min", "teraz"), the whole plan under "Cały plan" with a ✓ for each
 * moment. The ticks are this device's (localStorage, by the event): nothing is sent, a reload keeps them. Every 30 s again.
 *
 * A tick is kept by the moment's name and time (data-key), not by its place: a moment added or removed in the plan meanwhile must
 * not move the ticks onto other moments. The countdown is to data-at — the moment in milliseconds, worked out by the server in Polish
 * time (a time before 6:00 on the night after the evening's day) — so a device whose clock is in another zone still counts right.
 *
 * Dependencies (DOM): #planCard with data-plan-event, data-text-in ("za {0} min"), data-text-in-hours ("za {0} h {1} min"),
 * data-text-now, data-text-done; #planNext with [data-plan-next-when], [data-plan-next-label], [data-plan-next-song]; the moments
 * [data-plan-moment] (data-key, data-at — none for a moment without a time —, data-time "HH:mm" or "", data-label, data-song) each with
 * a [data-plan-tick] button.
 */
const card = document.getElementById('planCard');

/** The ticked moments' keys kept on this device, by the event; none when the storage is not there. */
function load(key) {
    try {
        const kept = JSON.parse(window.localStorage.getItem(key) || '[]');
        return new Set(Array.isArray(kept) ? kept.filter(function (k) { return typeof k === 'string'; }) : []);
    } catch (e) {
        return new Set();
    }
}

function save(key, done) {
    try {
        window.localStorage.setItem(key, JSON.stringify(Array.from(done)));
    } catch (e) {
        // a private window: the ticks last until the page is closed
    }
}

/** "za 12 min", "za 1 h 05 min" or "teraz" for the minutes until a moment (a moment past is "teraz" until it is ticked off). */
export function untilText(minutes, texts) {
    if (minutes <= 0) return texts.now;
    if (minutes < 60) return texts.in.replace('{0}', String(minutes));
    return texts.inHours.replace('{0}', String(Math.floor(minutes / 60))).replace('{1}', String(minutes % 60).padStart(2, '0'));
}

if (card) {
    const key = 's2p-plan-done-' + card.dataset.planEvent;
    const texts = { in: card.dataset.textIn, inHours: card.dataset.textInHours, now: card.dataset.textNow, done: card.dataset.textDone };
    const next = document.getElementById('planNext');
    const moments = Array.from(card.querySelectorAll('[data-plan-moment]'));
    const done = load(key);

    const show = function () {
        moments.forEach(function (moment) {
            const ticked = done.has(moment.dataset.key);
            moment.classList.toggle('s2p-plan-moment-done', ticked);
            const tick = moment.querySelector('[data-plan-tick]');
            tick.hidden = false;
            tick.setAttribute('aria-pressed', String(ticked));
        });
        const first = moments.find(function (moment) { return !done.has(moment.dataset.key); });
        next.hidden = false;
        const when = next.querySelector('[data-plan-next-when]');
        const label = next.querySelector('[data-plan-next-label]');
        const song = next.querySelector('[data-plan-next-song]');
        if (!first) {
            when.hidden = false;
            when.textContent = texts.done;
            label.textContent = '';
            song.textContent = '';
            return;
        }
        const at = Number(first.dataset.at);
        when.textContent = at ? first.dataset.time + ' · ' + untilText(Math.ceil((at - Date.now()) / 60000), texts) : '';
        when.hidden = !at;
        label.textContent = first.dataset.label;
        song.textContent = first.dataset.song ? ' · ' + first.dataset.song : '';
    };

    card.addEventListener('click', function (e) {
        const tick = e.target.closest('[data-plan-tick]');
        if (!tick) return;
        const moment = tick.closest('[data-plan-moment]').dataset.key;
        if (done.has(moment)) {
            done.delete(moment);
        } else {
            done.add(moment);
        }
        // only the keys of this plan: a moment gone from it takes its tick along
        const keys = new Set(moments.map(function (m) { return m.dataset.key; }));
        Array.from(done).forEach(function (k) { if (!keys.has(k)) done.delete(k); });
        save(key, done);
        show();
    });

    show();
    window.setInterval(show, 30000);
}
