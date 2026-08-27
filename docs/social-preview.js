const sharp = require('sharp');

// The two paths are lifted verbatim from ic_launcher_foreground.xml — Android
// vector pathData is plain SVG path syntax, so no redrawing is involved and the
// social preview cannot drift from the launcher icon.
const ARROW = 'M54,28v28l9,-9 5,5 -14,14 -14,-14 5,-5 9,9V28z';
const TRAY  = 'M36,74h36v6h-36z';
const MINT = '#7DF9C3';
const BG   = '#0E1414';

// GitHub renders the social preview at 1280x640 and crops anything else.
const W = 1280, H = 640;
const S = 4.4;                    // icon scale: 108 * 4.4 ~= 475px tall
const iconW = 108 * S;
const x = 150, y = (H - iconW) / 2;

const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">
  <rect width="${W}" height="${H}" fill="${BG}"/>
  <g transform="translate(${x},${y}) scale(${S})">
    <path d="${ARROW}" fill="${MINT}"/>
    <path d="${TRAY}" fill="${MINT}"/>
  </g>
  <text x="640" y="300" fill="${MINT}" font-family="DejaVu Sans, Segoe UI, Arial, sans-serif"
        font-size="132" font-weight="700" letter-spacing="-4">slurp</text>
  <text x="646" y="368" fill="#8AA0A0" font-family="DejaVu Sans, Segoe UI, Arial, sans-serif"
        font-size="40">Paste a link, get the file.</text>
  <text x="646" y="424" fill="#5C7070" font-family="DejaVu Sans, Segoe UI, Arial, sans-serif"
        font-size="30">YouTube · TikTok · Instagram</text>
  <text x="646" y="466" fill="#5C7070" font-family="DejaVu Sans, Segoe UI, Arial, sans-serif"
        font-size="30">Facebook · Threads · X · ~1800 more</text>
</svg>`;

sharp(Buffer.from(svg)).png().toFile('social-preview.png')
  .then(i => console.log('written:', i.width + 'x' + i.height, i.size + ' bytes'))
  .catch(e => console.error('FAILED:', e.message));
