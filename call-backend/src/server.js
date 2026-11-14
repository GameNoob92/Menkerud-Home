// Menkerud Home call backend – wiring only. The routes live in app.js; the pieces they use are built here
// from the environment and handed in, so every piece can be tested on its own.
'use strict';
require('dotenv/config'); // loads call-backend/.env – or the file named by DOTENV_CONFIG_PATH (the dev copy uses that)
const { loadConfig } = require('./config');
const fs = require('fs');
const path = require('path');
const { createApp } = require('./app');
const { createDevices } = require('./devices');
const livekit = require('./livekit');
const discord = require('./discord');

const log = (...a) => console.log(new Date().toISOString(), ...a);
const config = loadConfig();

// Everything that must survive a rebuild lives in the data folder (mounted from outside the synced project folder).
fs.mkdirSync(config.dataDir, { recursive: true });
const devices = createDevices({ file: path.join(config.dataDir, 'devices.json'), log });

const app = createApp({ config, livekit, discord, devices, log });
app.listen(config.port, () => log('call backend on :' + config.port, '| public', config.publicUrl, '| ring', config.ringSec + 's', '| livekit', livekit.configured(), '| discord', discord.configured()));
