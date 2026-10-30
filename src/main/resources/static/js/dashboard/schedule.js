/**
 * The card "Godziny imprezy" of the page "Ustawienia imprezy" (V55): the quick choices ("dziś 20:00", "jutro 3:00") fill the day and
 * the time of their moment — the day from the server's today and tomorrow in Polish time (the form's data-today / data-tomorrow), not
 * this device's clock. Nothing is sent: "Zapisz" saves — in the background, as the card's "✕" (cards-in-place.js).
 *
 * Dependencies (DOM): #partyHoursForm with data-today, data-tomorrow; buttons [data-hours-fill="open|close"] with data-day ("today" /
 * "tomorrow") and data-time ("03:00"); the fields #openDate, #openTime, #closeDate, #closeTime. Delegated from the document: the card
 * the server sends back keeps them working.
 */
document.addEventListener('click', function (e) {
    const button = e.target.closest('#partyHoursCard [data-hours-fill]');
    const form = document.getElementById('partyHoursForm');
    if (!button || !form) return;
    const kind = button.dataset.hoursFill;
    const date = document.getElementById(kind + 'Date');
    const time = document.getElementById(kind + 'Time');
    if (!date || !time) return;
    date.value = button.dataset.day === 'tomorrow' ? form.dataset.tomorrow : form.dataset.today;
    time.value = button.dataset.time;
});
