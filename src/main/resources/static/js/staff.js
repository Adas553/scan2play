// The owner's page "Obsługa" (staff.html, V32): a role picked ticks its permissions; a tick changed picks the role it makes ("Własne"
// when none) — the server decides the same way (StaffRole.of). A file of its own for the CSP ("Kopiuj" of the link: dj-nav.js).
(function () {
    document.querySelectorAll('form[data-staff-form]').forEach(function (form) {
        var select = form.querySelector('[data-role-select]');
        var help = form.querySelector('[data-role-help]');
        var boxes = Array.prototype.slice.call(form.querySelectorAll('[data-permission-box]'));
        var perms = form.querySelector('details');

        function showHelp() {
            var option = select.options[select.selectedIndex];
            if (help && option) help.textContent = option.getAttribute('data-help') || '';
        }

        select.addEventListener('change', function () {
            var option = select.options[select.selectedIndex];
            var set = (option.getAttribute('data-permissions') || '').split(',').filter(Boolean);
            if (select.value === 'CUSTOM') {
                if (perms) perms.open = true;      // the ticks as they are, to change one by one
            } else {
                boxes.forEach(function (box) { box.checked = set.indexOf(box.value) >= 0; });
            }
            showHelp();
        });

        boxes.forEach(function (box) {
            box.addEventListener('change', function () {
                var ticked = boxes.filter(function (b) { return b.checked; }).map(function (b) { return b.value; }).join(',');
                var match = 'CUSTOM';
                Array.prototype.forEach.call(select.options, function (option) {
                    if (option.value !== 'CUSTOM' && option.getAttribute('data-permissions') === ticked) match = option.value;
                });
                select.value = match;
                showHelp();
            });
        });
    });

})();
