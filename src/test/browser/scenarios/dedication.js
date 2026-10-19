// The guest's dedication (V38): folded under the song's field on the guest page ("💌 Dodaj dedykację", a details element — no
// script), unfolded on a tap, at most 100 characters, sent with the song (GuestPageRenderTest renders the page).

S2P.scenario({
    name: 'guest-dedication',
    title: 'the guest page on a phone: "Dodaj dedykację" unfolds the field, it fits the screen and goes with the song',
    page: 'guest',
    viewport: '390,844',   // a phone's screen
    run: async function (t) {
        const box = document.getElementById('dedicationBox');
        const input = document.getElementById('dedicationInput');
        t.check('the dedication is there, folded', !!box && !box.open && !!input && !input.checkVisibility());
        if (!box || !input) {
            return;
        }
        box.querySelector('summary').click();
        await t.sleep(100);
        const field = input.getBoundingClientRect();
        t.check('a tap unfolds it: the field shows', box.open && input.checkVisibility() && field.height > 0);
        t.check('the field fits the screen', field.left >= 0 && field.right <= document.documentElement.clientWidth);
        t.check('no scrolling sideways', document.documentElement.scrollWidth <= document.documentElement.clientWidth);
        t.step('at most 100 characters', input.maxLength, 100);

        input.value = 'dla Ani na 18-stkę';
        const form = input.form;
        t.check('in the song\'s form', !!form && form.contains(document.getElementById('songInput')));
        t.step('it goes with the song', form ? new FormData(form).get('dedication') : null, 'dla Ani na 18-stkę');
    }
});
