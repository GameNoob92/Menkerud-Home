// The dev copy must be impossible to start on the production settings, and its example file must stay harmless:
// the .env next to docker-compose.yml on the dev PC holds the real Discord token and would message the parents.
'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const dotenv = require('dotenv');
const { tmpFile } = require('./helpers');

const root = path.join(__dirname, '..');
const start = env => spawnSync(process.execPath, [path.join(root, 'scripts', 'dev-server.js')], { cwd: root, env: Object.assign({}, process.env, env), encoding: 'utf8', timeout: 20000 });

const ex = dotenv.parse(fs.readFileSync(path.join(root, '.env.dev.example')));
assert.strictEqual(ex.DISCORD_BOT_TOKEN, '');
assert.strictEqual(ex.PUSH_TRANSPORT, 'file');
assert.strictEqual(ex.LIVEKIT_API_KEY, '');
assert.strictEqual(ex.LIVEKIT_API_SECRET, '');
assert.deepStrictEqual([ex.PORT, ex.ADMIN_PORT, ex.CALL_PUBLIC_URL, ex.DATA_DIR], ['3100', '3101', 'http://127.0.0.1:3100', '../.dev/callapi-data']);
assert.ok(ex.DEVICE_KEY && ex.ADMIN_PASSWORD, 'the dev copy has a device key and a pairing-page password');
console.log('ok - the example for the dev copy cannot message anyone, writes pushes to a file, and carries no key');

const missing = start({ DOTENV_CONFIG_PATH: tmpFile('nope.env') });
assert.strictEqual(missing.status, 1);
assert.match(missing.stderr, /\.env\.dev\.example/);
console.log('ok - without its own settings file the dev copy does not start (and says what to copy)');

const prod = start({ DOTENV_CONFIG_PATH: '.env' });
assert.strictEqual(prod.status, 1);
assert.match(prod.stderr, /production/);
console.log('ok - the dev copy refuses the production .env');

console.log('ALL GOOD');
