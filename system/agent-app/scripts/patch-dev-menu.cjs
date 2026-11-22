const path = require('node:path');
const {applyPatch} = require('./patch-enriched-markdown.cjs');

// Expo's debug menu must tolerate ReactHost interface implementations such as TakuReactHost.
// The pinned patch affects debug metadata only; it does not unwrap or replace the runtime host.
const appRoot = path.resolve(__dirname, '..');
const prefix = path.join(appRoot, 'patches', 'expo-dev-menu+58.0.13');
const result = applyPatch(path.join(appRoot, 'node_modules', 'expo-dev-menu'), `${prefix}.json`, `${prefix}.patch`, process.argv.includes('--check'));
console.log(`Expo development menu patch: ${result}`);
