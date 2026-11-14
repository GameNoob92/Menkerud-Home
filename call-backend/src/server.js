// Menkerud Home call backend – wiring only. The routes live in app.js; the pieces they use are built here
// from the environment and handed in, so every piece can be tested on its own.
'use strict';
require('dotenv/config'); // loads call-backend/.env – or the file named by DOTENV_CONFIG_PATH (the dev copy uses that)
const fs = require('fs');
const path = require('path');
const { loadConfig } = require('./config');
const { createApp } = require('./app');
const { createAdminApp } = require('./admin');
const { createDevices } = require('./devices');
const { createHub } = require('./kiosk-events');
const { pushFromConfig } = require('./push');
const { createCalls } = require('./calls');
const livekit = require('./livekit');
const discord = require('./discord');

const log = (...a) => console.log(new Date().toISOString(), ...a);
const config = loadConfig();

// Everything that must survive a rebuild lives in the data folder (mounted from outside the synced project folder).
fs.mkdirSync(config.dataDir, { recursive: true });
const devices = createDevices({ file: path.join(config.dataDir, 'devices.json'), log });   // paired phones
const hub = createHub();                                                                   // the kiosk's event stream
const push = pushFromConfig(config, log);                                                  // wakes the parent app
const calls = createCalls({ config, livekit, devices, push, discord, hub, log });

// Cleaning up after calls that nobody reported as ended needs LiveKit's admin API; say at start whether it answers.
const health = { livekitAdmin: false };
if (livekit.configured()) {
  livekit.participantCount('startup-probe').then(n => {
    health.livekitAdmin = n !== null;
    if (n === null) log('WARNING: the LiveKit admin API does not answer – calls that are never hung up cannot be cleaned up until it does');
  });
}

const app = createApp({ config, livekit, discord, devices, hub, calls, health, log });
app.listen(config.port, () => log('call backend on :' + config.port, '| public', config.publicUrl, '| ring', config.ringSec + 's', '| livekit', livekit.configured(), '| discord', discord.configured(), '| push', push.name));

// The local pairing page, on its own port. That port is published on the host only – SWAG must never proxy it.
if (config.adminPassword) createAdminApp({ config, devices, log }).listen(config.adminPort, () => log('pairing page on :' + config.adminPort + ' (local network only)'));
else log('admin page disabled: set ADMIN_PASSWORD');
