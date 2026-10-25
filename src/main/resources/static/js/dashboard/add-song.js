/**
 * "Dodaj piosenkę" over the queue (V44): a song a guest asked for at the booth. The button folds the form out under the queue's
 * heading and puts the cursor in its field (song-autocomplete.js gives the suggestions); a second tap folds it away. forms.js sends
 * it in the background, empties the field and fetches the queue at once.
 */
const toggle = document.getElementById('addSongToggle');
const form = document.getElementById('addSongForm');

if (toggle && form) {
    toggle.addEventListener('click', function () {
        const open = form.hidden;
        form.hidden = !open;
        toggle.setAttribute('aria-expanded', String(open));
        if (open) form.querySelector('input[name="song"]').focus();
    });
}
