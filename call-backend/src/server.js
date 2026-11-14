// Menkerud Home call backend – wiring only. The routes live in app.js; the pieces they use are built here
// from the environment and handed in, so every piece can be tested on its own.
'use strict';
require('dotenv/config'); // loads call-backend/.env – or the file named by DOTENV_CONFIG_PATH (the dev copy uses that)
const { loadConfig } = require('./config');
const { createApp } = require('./app');
const livekit = require('./livekit');
const discord = require('./discord');

const log = (...a) => console.log(new Date().toISOString(), ...a);
const config = loadConfig();

const app = createApp({ config, livekit, discord, log });
app.listen(config.port, () => log('call backend on :' + config.port, '| public', config.publicUrl, '| ring', config.ringSec + 's', '| livekit', livekit.configured(), '| discord', discord.configured()));
