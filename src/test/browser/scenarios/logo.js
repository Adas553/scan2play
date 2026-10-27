// The DJ's logo (V45, logo.js on the page "Ustawienia imprezy"): the chosen image is drawn in the browser at most 512 px a side and
// sent as that PNG, the request's body, with the CSRF token in the header (PartyLogoController makes its own PNG of it). An SVG or a
// file over 2 MB is refused here, nothing sent; the server's refusal is its own sentence; a saved logo brings the card again in place.
// The guests' pages, the TV and the print show it from our address under the real CSP, and the print still fits its paper.
(function () {
    /** A real picture of width × height as a File of that type, its halves of two colours — no margin to cut off, as a photo. */
    async function picture(width, height, type, name) {
        const canvas = document.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const context = canvas.getContext('2d');
        context.fillStyle = '#e07000';
        context.fillRect(0, 0, width / 2, height);
        context.fillStyle = '#20a040';
        context.fillRect(width / 2, 0, width / 2, height);
        context.fillStyle = '#1040c0';
        context.fillRect(width / 4, height / 4, width / 2, height / 2);
        const blob = await new Promise(function (resolve) { canvas.toBlob(resolve, type); });
        return new File([blob], name, { type: type });
    }

    function choose(file) {
        const files = new DataTransfer();
        files.items.add(file);
        const input = document.getElementById('logoFile');
        input.files = files.files;
        input.dispatchEvent(new Event('change', { bubbles: true }));
    }

    function noPageLoads() {
        const loads = [];
        navigation.addEventListener('navigate', function (e) {
            loads.push(e.navigationType);
            e.preventDefault();
        });
        return loads;
    }

    S2P.scenario({
        name: 'logo-upload',
        page: 'settings',
        title: 'the DJ\'s logo: an SVG or a file over 2 MB refused in the browser; a picture drawn at 512 px, previewed, sent as a PNG body with the CSRF header; the server\'s refusal said; saved, the card again in place',
        setup: { logoStatus: 422, logoAnswer: 'To nie jest obraz PNG, JPEG ani WebP.' },
        run: async function (t) {
            const loads = noPageLoads();
            const card = document.getElementById('logoCard');
            card.open = true;
            const form = document.getElementById('logoForm');
            const answer = document.getElementById('logoAnswer');
            const button = document.getElementById('logoUpload');
            const preview = document.getElementById('logoPreview');
            t.step('at first: "Wgraj" disabled, no preview', [button.disabled, preview.hidden], [true, true]);

            choose(new File(['<svg xmlns="http://www.w3.org/2000/svg"></svg>'], 'logo.svg', { type: 'image/svg+xml' }));
            await t.waitFor(function () { return !answer.hidden; }, 'the refusal of an SVG', 2000);
            t.step('an SVG: refused here, nothing to send', [answer.textContent, answer.classList.contains('text-danger'), button.disabled,
                preview.hidden], ['To nie jest obraz PNG, JPEG ani WebP.', true, true, true]);

            choose(new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'huge.png', { type: 'image/png' }));
            await t.waitFor(function () { return answer.textContent.indexOf('za duży') >= 0; }, 'the refusal of a large file', 2000);
            t.step('over 2 MB: refused here', [answer.textContent, button.disabled], ['Plik jest za duży: najwyżej 2 MB.', true]);
            t.step('nothing sent', await t.stand.count('POST /dj/logo'), 0);

            choose(await picture(1600, 400, 'image/webp', 'logo.webp'));
            await t.waitFor(function () { return !preview.hidden; }, 'the preview', 3000);
            const canvas = document.getElementById('logoCanvas');
            t.step('a WebP of 1600 × 400: drawn at 512 × 128, "Wgraj" enabled', [canvas.width, canvas.height, button.disabled], [512, 128, false]);
            t.step('the field names the file', document.querySelector('#logoDrop [data-file-name]').textContent, 'logo.webp');

            button.click();
            await t.waitFor(function () { return answer.classList.contains('text-danger') && answer.textContent.indexOf('PNG') > 0; },
                'the server\'s refusal', 4000);
            t.step('refused by the server: its own sentence, the button back', [answer.textContent, button.disabled],
                ['To nie jest obraz PNG, JPEG ani WebP.', false]);
            const sent = (await t.stand.requests('POST /dj/logo'))[0].q;
            t.step('the body is our PNG of 512 × 128', [sent.__contentType, sent.__head, sent.__size],
                ['application/octet-stream', '89504e470d0a1a0a', [512, 128]]);
            t.step('the CSRF token in the header, the party in the address', [sent['X-CSRF-TOKEN'], sent.partyCode],
                ['harness-csrf-token', 'HARN1']);

            await t.stand.config({ logoStatus: 200, logoAnswer: 'Gotowe — logo zapisane.' });
            button.click();
            await t.waitFor(function () { return document.getElementById('logoForm') !== form; }, 'the card again', 4000);
            const fresh = document.getElementById('logoAnswer');
            t.step('saved: the card from the server in place, open, with the server\'s sentence',
                [fresh.textContent, fresh.hidden, document.getElementById('logoCard').open], ['Gotowe — logo zapisane.', false, true]);
            t.step('nothing loaded', loads, []);
        }
    });

    /** A logo in the middle of a large white square, as many are saved: what is sent is the logo, its margins cut off. */
    S2P.scenario({
        name: 'logo-trims-margins',
        page: 'settings',
        title: 'the DJ\'s logo: a logo in the middle of a large white (or transparent) square is sent without the empty margins',
        run: async function (t) {
            document.getElementById('logoCard').open = true;
            const canvas = document.getElementById('logoCanvas');
            async function framed(background, type) {
                const square = document.createElement('canvas');
                square.width = 1000;
                square.height = 1000;
                const context = square.getContext('2d');
                if (background) {
                    context.fillStyle = background;
                    context.fillRect(0, 0, 1000, 1000);
                }
                context.fillStyle = '#1040c0';
                context.fillRect(300, 450, 400, 100);
                const blob = await new Promise(function (resolve) { square.toBlob(resolve, type, 0.95); });
                return new File([blob], 'logo', { type: type });
            }
            choose(await framed('#ffffff', 'image/jpeg'));
            await t.waitFor(function () { return !document.getElementById('logoPreview').hidden; }, 'the preview', 3000);
            t.check('on white: the logo with a thin margin (' + canvas.width + ' × ' + canvas.height + ')',
                canvas.width >= 410 && canvas.width <= 450 && canvas.height >= 110 && canvas.height <= 150);
            document.getElementById('logoPreview').hidden = true;
            choose(await framed(null, 'image/png'));
            await t.waitFor(function () { return !document.getElementById('logoPreview').hidden; }, 'the preview', 3000);
            t.check('transparent: the same (' + canvas.width + ' × ' + canvas.height + ')',
                canvas.width >= 410 && canvas.width <= 450 && canvas.height >= 110 && canvas.height <= 150);
            choose(await picture(1600, 400, 'image/png', 'photo.png'));
            await t.waitFor(function () { return canvas.width === 512; }, 'a picture whose corners differ', 3000).catch(function () {});
            t.step('a picture whose corners differ: whole', [canvas.width, canvas.height], [512, 128]);
        }
    });

    S2P.scenario({
        name: 'logo-delete',
        page: 'settings-logo',
        title: 'the DJ\'s logo: the saved one shows (our address, the real CSP); "Usuń" asks first, then goes in the background and the card comes again',
        run: async function (t) {
            const loads = noPageLoads();
            const card = document.getElementById('logoCard');
            card.open = true;
            const current = document.getElementById('logoCurrent');
            await t.waitFor(function () { return current.complete; }, 'the logo', 3000);
            t.check('the saved logo shows (' + current.naturalWidth + ' px)', current.naturalWidth > 0);
            const asked = [];
            let yes = false;
            window.confirm = function (text) { asked.push(text); return yes; };
            document.querySelector('#logoDeleteForm button[type="submit"]').click();
            await t.sleep(300);
            t.step('asked first; "Anuluj": nothing sent', [asked.length, asked[0] && asked[0].indexOf('Usunąć logo?') === 0,
                await t.stand.count('POST /dj/logo/delete')], [1, true, 0]);
            yes = true;
            document.querySelector('#logoDeleteForm button[type="submit"]').click();
            await t.waitFor(function () { return document.getElementById('logoCard') !== card; }, 'the card again', 4000);
            const sent = (await t.stand.requests('POST /dj/logo/delete'))[0].q;
            t.step('sent once, with the party and the CSRF token in the header', [await t.stand.count('POST /dj/logo/delete'), sent.partyCode,
                sent['X-CSRF-TOKEN']], [1, 'HARN1', 'harness-csrf-token']);
            t.step('nothing loaded', loads, []);
        }
    });

    S2P.scenario({
        name: 'logo-phone',
        page: 'settings',
        viewport: '390,844',
        title: 'the DJ\'s logo on a phone: the card with a wide preview fits the screen — no sideways scroll, "Wgraj" a full tap target',
        run: async function (t) {
            document.getElementById('logoCard').open = true;
            choose(await picture(2000, 300, 'image/jpeg', 'baner.jpg'));
            await t.waitFor(function () { return !document.getElementById('logoPreview').hidden; }, 'the preview', 3000);
            const width = document.documentElement.clientWidth;
            const card = document.getElementById('logoCard').getBoundingClientRect();
            const canvas = document.getElementById('logoCanvas').getBoundingClientRect();
            const button = document.getElementById('logoUpload').getBoundingClientRect();
            t.check('no sideways scroll (' + document.documentElement.scrollWidth + ' ≤ ' + width + ')',
                document.documentElement.scrollWidth <= width);
            t.check('the preview inside the card', canvas.left >= card.left && canvas.right <= card.right);
            t.check('"Wgraj" at least 44 px high (' + Math.round(button.height) + ')', button.height >= 43.5);
        }
    });

    /** A guest's page with the logo: it loads from our address under the real CSP, above our own name, inside a phone's screen. */
    function guestPageShowsTheLogo(ours) {
        return async function (t) {
            const logo = document.getElementById('djLogo');
            await t.waitFor(function () { return logo && logo.complete; }, 'the logo', 3000);
            t.check('the DJ\'s logo shows (' + logo.naturalWidth + ' × ' + logo.naturalHeight + ')', logo.naturalWidth > 0);
            const box = logo.getBoundingClientRect();
            const width = document.documentElement.clientWidth;
            t.check('inside the screen (' + Math.round(box.left) + '–' + Math.round(box.right) + ' of ' + width + ')',
                box.left >= 0 && box.right <= width);
            t.check('at most 110 px high (' + Math.round(box.height) + ')', box.height <= 110.5);
            if (ours) {
                t.check('above our own name', box.bottom <= document.querySelector(ours).getBoundingClientRect().top + 0.5);
            }
            t.check('no sideways scroll', document.documentElement.scrollWidth <= width);
        };
    }

    S2P.scenario({
        name: 'logo-guest-page',
        page: 'guest-logo',
        viewport: '390,844',
        title: 'the DJ\'s logo on the guest page (a phone): from our address under the real CSP, above our name, inside the screen',
        run: guestPageShowsTheLogo('.s2p-logo')
    });

    S2P.scenario({
        name: 'logo-result-page',
        page: 'result-logo',
        viewport: '390,844',
        title: 'the DJ\'s logo on the result page (a phone): above our name, inside the screen',
        run: guestPageShowsTheLogo('.s2p-logo')
    });

    S2P.scenario({
        name: 'logo-party-ended',
        page: 'party-ended-logo',
        viewport: '390,844',
        title: 'the DJ\'s logo on the page "party ended" (a phone): shows, inside the screen',
        run: guestPageShowsTheLogo(null)
    });

    /** The printable height of an A4 page in CSS px: 297 mm less the page's margins (qr-print.css, @page 12 mm). */
    const A4_PRINTABLE = (297 - 2 * 12) * 96 / 25.4;

    S2P.scenario({
        name: 'logo-poster-fits-a4',
        page: 'qr-print-poster-logo',
        title: 'the QR poster with the DJ\'s logo and every profile and the tip: the logo heads it, ours a quiet line at the foot, all on one A4 page',
        run: async function (t) {
            const logo = document.querySelector('.poster .dj-logo');
            await t.waitFor(function () { return logo && logo.complete; }, 'the logo', 3000);
            t.check('the DJ\'s logo shows', logo.naturalWidth > 0);
            t.check('at the top, above the heading', logo.getBoundingClientRect().bottom <= document.querySelector('.poster h1').getBoundingClientRect().top + 0.5);
            const ours = document.querySelector('.poster .powered');
            t.check('ours a quiet line at the foot', !!ours && ours.getBoundingClientRect().height < 30
                && ours.getBoundingClientRect().top > document.querySelector('.poster .tip').getBoundingClientRect().bottom);
            const words = ours.firstElementChild.getBoundingClientRect(), mark = ours.querySelector('.logo img').getBoundingClientRect();
            const apart = Math.abs((words.top + words.bottom) / 2 - (mark.top + mark.bottom) / 2);
            t.check('ours: the words and the mark on one centre line (' + Math.round(apart * 10) / 10 + ' px apart)', apart <= 1);
            const box = logo.getBoundingClientRect();
            t.check('the logo heads the poster: at least 40 mm high (' + Math.round(box.height * 25.4 / 96) + ' mm)', box.height >= 40 * 96 / 25.4);
            const poster = document.querySelector('.poster').getBoundingClientRect();
            t.check('one A4 page: ' + Math.round(poster.height) + ' ≤ ' + Math.round(A4_PRINTABLE) + ' px', poster.height <= A4_PRINTABLE);
        }
    });

    S2P.scenario({
        name: 'logo-cards-fit',
        page: 'qr-print-cards-logo',
        title: 'the QR cards with the DJ\'s logo, the three profiles and the tip: the logo above the code in place of ours, nothing cut off any card',
        run: async function (t) {
            const logos = Array.from(document.querySelectorAll('.card .dj-logo'));
            await t.waitFor(function () { return logos.length === 8 && logos.every(function (img) { return img.complete; }); }, 'the logos', 3000);
            t.check('on every card, shown', logos.every(function (img) { return img.naturalWidth > 0; }));
            const card = document.querySelector('.card');
            const whole = card.getBoundingClientRect();
            const logo = card.querySelector('.dj-logo').getBoundingClientRect();
            const qr = card.querySelector('.qr').getBoundingClientRect();
            const links = card.querySelector('.side .dj-links').getBoundingClientRect();
            const tip = card.querySelector('.text .tip').getBoundingClientRect();
            t.check('the logo above the code', logo.bottom <= qr.top + 0.5);
            const text = card.querySelector('.text').getBoundingClientRect();
            const off = Math.abs((text.top + text.bottom) / 2 - (qr.top + qr.bottom) / 2);
            t.check('the texts centred on the code (' + Math.round(off * 10) / 10 + ' px off)', off <= 2);
            t.check('a square logo at least 13 mm high (' + Math.round(logo.height * 25.4 / 96) + ' mm)', logo.height >= 13 * 96 / 25.4);
            t.check('in place of ours: one mark on a card', card.querySelector('.logo') === null);
            t.check('the profiles end inside the card (' + Math.round(whole.bottom - links.bottom) + ' px to spare)', links.bottom <= whole.bottom - 2);
            t.check('the tip ends inside the card', tip.bottom <= whole.bottom - 2 && tip.right <= whole.right - 2);
        }
    });
})();
