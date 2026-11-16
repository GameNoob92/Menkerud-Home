// Reads the environment once into one frozen object. Every other module takes this object instead of
// touching process.env, so tests can build their own configuration.
'use strict';
const path = require('path');

function loadConfig(env = process.env) {
  const dataDir = env.DATA_DIR || './data';
  return Object.freeze({
    port: Number(env.PORT) || 3000,                 // public API (SWAG proxies call.noobventure.com to it)
    adminPort: Number(env.ADMIN_PORT) || 3001,      // local pairing page; never proxied
    publicUrl: (env.CALL_PUBLIC_URL || 'https://call.noobventure.com').replace(/\/+$/, ''),
    ringSec: Number(env.CALL_RING_TIMEOUT_SECONDS) || 60,
    tokenTtlSec: Number(env.CALL_TOKEN_TTL_SECONDS) || 600,
    maxCallSec: Number(env.CALL_MAX_SECONDS) || 7200,
    deviceKey: env.DEVICE_KEY || '',
    dev: env.DEV === '1',
    dataDir,
    adminPassword: env.ADMIN_PASSWORD || '',
    adminUser: env.ADMIN_USER || '',                // empty = any user name goes with the password
    pushTransport: env.PUSH_TRANSPORT || '',
    fcmServiceAccount: env.FCM_SERVICE_ACCOUNT || path.join(dataDir, 'fcm-service-account.json'),
    // Only these targets exist; Discord ids come from the environment, never from a request.
    parents: Object.freeze({
      mor: Object.freeze({ discordUserId: env.DISCORD_MOR_USER_ID || '', label: 'Mor', identity: 'parent-mor' }),
      far: Object.freeze({ discordUserId: env.DISCORD_FAR_USER_ID || '', label: 'Far', identity: 'parent-far' })
    })
  });
}

module.exports = { loadConfig };
