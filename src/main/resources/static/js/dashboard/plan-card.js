/**
 * "Plan wieczoru" above the queue ("Plan imprezy", V77, variant B): the next moment of tonight's plan — the first one not ticked
 * off — with its time counted down ("za 12 min", "za 1 h 05 min", "teraz"), the whole plan under "Cały plan" with a ✓ for each
 * moment. The ticks are this device's (localStorage, by the event): nothing is sent, a reload keeps them. The countdown follows this
 * device's clock, every 30 s.
 *
 * A moment's time is on the evening's date (#planCard data-plan-evening), or the next day's when it is earlier than 6:00 — the
 * evening runs until 6:00, as the evening summary counts it (oczepiny at 0:30 come after the cake at 23:00).
 *
 * Dependencies (DOM): #planCard with data-plan-event, data-plan-evening, data-text-in ("za {0} min"), data-text-in-hours
 * ("za {0} h {1} min"), data-text-now, data-text-done; #planNext with [data-plan-next-when], [data-plan-next-label],
 * [data-plan-next-song]; the moments [data-plan-moment] (data-index, data-time "HH:mm" or "", data-label, data-song) each with a
 * [data-plan-tick] button.
 */
const card = document.getElementById('planCard');

/** The ticked moments' indexes kept on this device, by the event; none when the storage is not there. */
function load(key) {
    try {
        const kept = JSON.parse(window.localStorage.getItem(key) || '[]');
        return new Set(Array.isArray(kept) ? kept : []);
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

/** The moment's time as a Date of this evening: the evening's day, the next day before 6:00; null for a moment without a time. */
export function momentAt(evening, time) {
    if (!time) return null;
    const [year, month, day] = evening.split('-').map(Number);
    const [hours, minutes] = time.split(':').map(Number);
    const at = new Date(year, month - 1, day, hours, minutes, 0, 0);
    if (hours < 6) at.setDate(at.getDate() + 1);
    return at;
}

/** "za 12 min", "za 1 h 05 min" or "teraz" for the minutes until a moment (a moment past is "teraz" until it is ticked off). */
export function untilText(minutes, texts) {
    if (minutes <= 0) return texts.now;
    if (minutes < 60) return texts.in.replace('{0}', String(minutes));
    return texts.inHours.replace('{0}', String(Math.floor(minutes / 60))).replace('{1}', String(minutes % 60).padStart(2, '0'));
}

if (card) {
    const key = 's2p-plan-done-' + card.dataset.planEvent;
    const evening = card.dataset.planEvening;
    const texts = { in: card.dataset.textIn, inHours: card.dataset.textInHours, now: card.dataset.textNow, done: card.dataset.textDone };
    const next = document.getElementById('planNext');
    const moments = Array.from(card.querySelectorAll('[data-plan-moment]'));
    const done = load(key);

    const show = function () {
        moments.forEach(function (moment) {
            const ticked = done.has(Number(moment.dataset.index));
            moment.classList.toggle('s2p-plan-moment-done', ticked);
            const tick = moment.querySelector('[data-plan-tick]');
            tick.hidden = false;
            tick.setAttribute('aria-pressed', String(ticked));
        });
        const first = moments.find(function (moment) { return !done.has(Number(moment.dataset.index)); });
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
        const at = momentAt(evening, first.dataset.time);
        when.textContent = at ? first.dataset.time + ' · ' + untilText(Math.ceil((at.getTime() - Date.now()) / 60000), texts) : '';
        when.hidden = !at;
        label.textContent = first.dataset.label;
        song.textContent = first.dataset.song ? ' · ' + first.dataset.song : '';
    };

    card.addEventListener('click', function (e) {
        const tick = e.target.closest('[data-plan-tick]');
        if (!tick) return;
        const index = Number(tick.closest('[data-plan-moment]').dataset.index);
        if (done.has(index)) {
            done.delete(index);
        } else {
            done.add(index);
        }
        save(key, done);
        show();
    });

    show();
    window.setInterval(show, 30000);
}
