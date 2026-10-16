// "Kopiuj" (review 2026-10-14: four ways of copying, each failing differently): the party link (the panel's card and its empty queue),
// the hosts' link (settings), the staff's link (Obsługa), the address in "Pomoc i kontakt" and on /pro — one handler, dj-nav.js. The
// button says "Skopiowano" only when the text was copied; without the clipboard (plain HTTP, refused) the text is copied the old way,
// and when that is refused too it stays selected for the person to copy — the button does not claim it was copied.
//
// A headless browser's clipboard is the system's: here it is a stand-in that records what was written, or refuses.
(function () {
    /** The clipboard ('ok' or 'refused') and the old way's copy (true: it copies) as the scenario sets them; what they were given. */
    function clipboard(mode, oldWayCopies) {
        const seen = { written: [], oldWay: [] };
        Object.defineProperty(navigator, 'clipboard', { configurable: true, value: {
            writeText: function (text) {
                if (mode === 'refused') return Promise.reject(new DOMException('Write permission denied.', 'NotAllowedError'));
                seen.written.push(text);
                return Promise.resolve();
            }
        } });
        document.execCommand = function (command) {
            const field = document.activeElement;
            const selected = field && typeof field.selectionStart === 'number'
                ? field.value.substring(field.selectionStart, field.selectionEnd) : String(window.getSelection());
            seen.oldWay.push(command + ':' + selected);
            return oldWayCopies;
        };
        return seen;
    }

    /** What one tap on {@code button} does, for each way the browser may answer. */
    async function tapEachWay(t, what, button, text, shown) {
        const label = button.textContent.trim();
        const copied = button.getAttribute('data-copied');
        const answer = async function () {
            await t.sleep(100);
            const now = button.textContent.trim();
            await t.waitFor(function () { return button.textContent.trim() === label; }, 'the label back', 3000).catch(function () {});
            return now;
        };

        let seen = clipboard('ok', true);
        button.click();
        t.step(what + ': copied, "' + copied + '" for a moment', [await answer(), seen.written, seen.oldWay], [copied, [text], []]);

        seen = clipboard('refused', true);
        button.click();
        t.step(what + ': the clipboard refused — copied the old way, "' + copied + '"', [await answer(), seen.oldWay], [copied, ['copy:' + text]]);

        seen = clipboard('refused', false);
        button.click();
        const said = await answer();
        if (!shown) {
            t.step(what + ': refused both ways — the button does not say it was copied', said, label);
            return;
        }
        const selected = document.activeElement && typeof document.activeElement.selectionStart === 'number'
            ? document.activeElement.value.substring(document.activeElement.selectionStart, document.activeElement.selectionEnd)
            : String(window.getSelection());
        t.step(what + ': refused both ways — the text left selected, the button does not say it was copied', [said, selected], [label, text]);
    }

    S2P.scenario({
        name: 'copy-the-party-link',
        title: '"Kopiuj" of the party link (the card and the empty queue) and of "Pomoc i kontakt": copied, or said it was not',
        run: async function (t) {
            const link = document.getElementById('partyLinkInput').value;
            const shows = function (id) { return document.getElementById(id).checkVisibility(); };
            await tapEachWay(t, 'the card', document.getElementById('copyPartyLinkBtn'), link, shows('partyLinkInput'));
            // the empty queue's button copies the card's field, folded away on a phone
            const empty = document.querySelector('[data-copy-target="partyLinkInput"]:not(#copyPartyLinkBtn)');
            if (empty) await tapEachWay(t, 'the empty queue', empty, link, shows('partyLinkInput'));
            // the address of "Pomoc i kontakt": in its window, closed — the copy needs no address shown
            const help = document.getElementById('helpEmailCopy');
            await tapEachWay(t, '"Pomoc i kontakt"', help, help.getAttribute('data-copy-text'), shows('helpEmail'));
        }
    });

    S2P.scenario({
        name: 'copy-the-hosts-link',
        title: '"Kopiuj" of the hosts\' link on "Ustawienia imprezy": copied, or said it was not',
        page: 'settings',
        run: async function (t) {
            const button = document.querySelector('[data-copy-target="hostLinkInput"]');
            t.step('the hosts\' link shows', !!button, true);
            if (button) await tapEachWay(t, 'the hosts\' link', button, document.getElementById('hostLinkInput').value, true);
        }
    });

    S2P.scenario({
        name: 'copy-the-staff-link',
        title: '"Kopiuj" of the staff\'s link on "Obsługa": copied, or said it was not',
        page: 'staff',
        run: async function (t) {
            const button = document.querySelector('[data-copy-target="staffLinkInput"]');
            t.step('the staff\'s link shows', !!button, true);
            if (button) await tapEachWay(t, 'the staff\'s link', button, document.getElementById('staffLinkInput').value, true);
        }
    });
    S2P.scenario({
        name: 'copy-the-address-on-pro',
        title: '"Kopiuj adres" on /pro: copied, or the address shown selected',
        page: 'pro-pl',
        run: async function (t) {
            const button = document.querySelector('button[data-copy-select="proAddress"]');
            t.step('the button shows', !!button, true);
            if (button) await tapEachWay(t, 'the address', button, button.getAttribute('data-copy-text'), true);
        }
    });
})();
