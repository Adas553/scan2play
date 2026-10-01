// The print page of the party's QR code (templates/qr-print.html): its "Print" button opens the browser's print window, where
// "Save as PDF" makes a file too. A file, not an inline handler: the pages are moving towards a Content-Security-Policy.
(function () {
    'use strict';
    const button = document.getElementById('printBtn');
    if (button) button.addEventListener('click', function () { window.print(); });
})();
