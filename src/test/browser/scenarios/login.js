// The login page (PasswordLoginSessionWebTest renders it, Polish) on a 390 px phone, with the real app.css and Bootstrap, under the
// real policy: "Nie wylogowuj mnie na tym urządzeniu" — clear until ticked — counts for both ways in. One form: Google's button posts
// the box to /login/google without asking for the address and the password; the password's button posts it with them to
// /login/password; Enter in a field is the password's login, never Google's (the form's first submit button). No script on the page.
(function () {
    /** Where the form would go and what it would send: the navigation caught (and stopped), with its form's fields. */
    function catchSubmits() {
        const sent = [];
        navigation.addEventListener('navigate', function (e) {
            const fields = {};
            if (e.formData) {
                e.formData.forEach(function (value, name) { if (name !== '_csrf') fields[name] = value; });
            }
            sent.push({ path: new URL(e.destination.url).pathname, fields: fields });
            e.preventDefault();
        });
        return sent;
    }

    S2P.scenario({
        name: 'login-stay-logged-in',
        title: 'the login page on a 390 px phone: "Nie wylogowuj mnie na tym urządzeniu" clear by default, one 44 px line, sent with Google\'s button and with the password\'s; Enter is the password\'s login',
        page: 'login',
        viewport: '390,844',
        run: async function (t) {
            const sent = catchSubmits();
            const box = document.getElementById('stayLoggedIn');
            const google = document.getElementById('googleLogin');
            const password = document.getElementById('passwordLogin');
            t.check('the box is there', box !== null && box.type === 'checkbox');
            if (!box) return;
            const label = document.querySelector('label[for="stayLoggedIn"]');
            t.step('its words', label.textContent.trim(), 'Nie wylogowuj mnie na tym urządzeniu');
            t.step('clear by default', box.checked, false);
            t.step('one form for the box and both buttons', [box.form === google.form, box.form === password.form], [true, true]);
            t.step('the box above Google\'s button', box.getBoundingClientRect().bottom <= google.getBoundingClientRect().top, true);

            const form = box.form;
            const first = form.querySelector('button[type="submit"], input[type="submit"]');
            // (a button's formAction is the page's own address when it has no formaction: the form's action is what counts)
            t.step('Enter in a field: the form\'s first submit button is the password\'s', [first !== google,
                first.hasAttribute('formaction'), new URL(form.action).pathname], [true, false, '/login/password']);

            const line = label.getBoundingClientRect();
            t.check('the box\'s line is 44 px high (' + Math.round(line.height) + ')', line.height >= 43.5);
            t.check('nothing wider than the screen', document.documentElement.scrollWidth <= document.documentElement.clientWidth);

            label.click();
            t.step('a tap on the words ticks the box', box.checked, true);
            google.click();
            await t.waitFor(function () { return sent.length >= 1; }, 'Google\'s button sent', 3000);
            t.step('Google\'s button: to /login/google with the box, the empty fields not in its way', sent[0],
                { path: '/login/google', fields: { 'remember-me': 'true', email: '', password: '' } });

            label.click();
            document.getElementById('loginEmail').value = 'ola@example.com';
            document.getElementById('loginPassword').value = 'dobre-haslo-1';
            password.click();
            await t.waitFor(function () { return sent.length >= 2; }, 'the password\'s button sent', 3000);
            t.step('the box cleared, the password\'s login: no "remember-me"', sent[1],
                { path: '/login/password', fields: { email: 'ola@example.com', password: 'dobre-haslo-1' } });

            label.click();
            password.click();
            await t.waitFor(function () { return sent.length >= 3; }, 'the password\'s button sent again', 3000);
            t.step('the box ticked, the password\'s login: "remember-me" with it', sent[2],
                { path: '/login/password', fields: { 'remember-me': 'true', email: 'ola@example.com', password: 'dobre-haslo-1' } });
        }
    });
})();
