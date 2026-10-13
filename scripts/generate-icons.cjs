// Run with Node.js and sharp available: node scripts/generate-icons.cjs
// Only resizes/pads the user-approved artwork; never redraws the TV letterforms.
const fs = require('node:fs/promises');
const path = require('node:path');
const sharp = require('sharp');

const root = path.resolve(__dirname, '..');
const source = path.join(root, 'design/icon-tv2-d-approved.png');
const res = path.join(root, 'app/src/main/res');

async function output(folder, name, pipeline) {
  const target = path.join(res, folder);
  await fs.mkdir(target, { recursive: true });
  await pipeline.png().toFile(path.join(target, name));
}

async function main() {
  for (const [density, size] of Object.entries({mdpi:48, hdpi:72, xhdpi:96, xxhdpi:144, xxxhdpi:192})) {
    await output(`mipmap-${density}`, 'ic_launcher.png', sharp(source).resize(size, size));
  }
  // 108dp adaptive layer: the original square occupies 80dp, keeping the
  // approximately 74%-wide TV mark inside the central 66dp safe area.
  // Preserve the approved navy background in the foreground bitmap too.
  await output('drawable-nodpi', 'ic_launcher_artwork.png', sharp(source)
    .resize(800, 800)
    .extend({top:140, bottom:140, left:140, right:140, background:'#0B1423'}));
  // Crop only background from the square; the complete TV mark fits 16:9.
  await output('drawable-xhdpi', 'tv_banner.png', sharp(source).resize(320, 180, {fit:'cover'}));
  console.log('Generated launcher densities, adaptive artwork and TV banner from approved D artwork.');
}

main().catch(error => { console.error(error); process.exitCode = 1; });
