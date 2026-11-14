// Starts the DEV COPY of the call backend – never the real one.
// It reads <repo>/.dev/callapi.env (git-ignored; copy .env.dev.example there) and will not start without it. On the
// dev PC the .env next to docker-compose.yml is the production one, with the real Discord token: a test call through
// it would message the parents. Going through this script, that file cannot be picked up by accident.
// The same as, from call-backend/:   DOTENV_CONFIG_PATH=../.dev/callapi.env node src/server.js
'use strict';
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const envFile = path.resolve(root, process.env.DOTENV_CONFIG_PATH || '../.dev/callapi.env');
if (envFile === path.join(root, '.env')) {
  console.error('Refusing to start the dev copy with the production settings (' + envFile + ').');
  process.exit(1);
}
if (!fs.existsSync(envFile)) {
  console.error('No ' + envFile + '\nCopy call-backend/.env.dev.example there and fill in the LiveKit key.');
  process.exit(1);
}
delete process.env.DISCORD_BOT_TOKEN;      // only the dev file may name one (dotenv never replaces what is already set)
process.chdir(root);                       // DATA_DIR in the dev file is relative to call-backend/
process.env.DOTENV_CONFIG_PATH = envFile;
require('../src/server');
