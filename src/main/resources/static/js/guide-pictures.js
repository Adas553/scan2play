// The guide's pictures (templates/guide*.html, #guidePicture1–3): a tap shows the picture whole in the middle of the screen, over a
// dark backdrop, on the same page — a tap anywhere, "✕" or Esc closes it and the page is where it was. Without the script the link
// opens the picture's file itself (the browser's own view, left at the top, back with "Wstecz").
(function () {
    'use strict';
    const dialog = document.getElementById('guidePictureDialog');
    if (!dialog || typeof dialog.showModal !== 'function') return;
    const big = dialog.querySelector('img');
    document.querySelectorAll('.s2p-guide-picture a').forEach(function (link) {
        link.addEventListener('click', function (event) {
            event.preventDefault();
            const small = link.querySelector('img');
            big.src = link.getAttribute('href');
            big.alt = small ? small.alt : '';
            // a computer's screen on an upright phone would be no bigger than on the page: app.css draws it wider, to slide along
            dialog.classList.toggle('s2p-picture-wide', !!small && Number(small.getAttribute('width')) > Number(small.getAttribute('height')));
            dialog.showModal();
            dialog.scrollLeft = 0;
        });
    });
    // anywhere: the picture, the backdrop, "✕" — the dialog has nothing else to tap
    dialog.addEventListener('click', function () { dialog.close(); });
    // the picture stays in the closed dialog: "close" comes a moment later, and clearing it there emptied the next one tapped quickly
})();
