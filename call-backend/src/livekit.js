// LiveKit helpers: short-lived participant tokens and best-effort room cleanup.
// The API secret lives only here (server-side); the browser never sees it.
const { AccessToken, RoomServiceClient } = require('livekit-server-sdk');

const API_KEY = process.env.LIVEKIT_API_KEY || '';
const API_SECRET = process.env.LIVEKIT_API_SECRET || '';
const WS_URL = process.env.LIVEKIT_URL || 'wss://rtc.noobventure.com';
// HTTP(S) endpoint for the server SDK (room admin). Defaults to the WS URL with the scheme swapped.
const HTTP_URL = process.env.LIVEKIT_API_URL || WS_URL.replace(/^ws/, 'http');

// Create a JWT that can join exactly one room. ttl is in seconds.
async function joinToken({ room, identity, name, ttl }) {
  if (!API_KEY || !API_SECRET) throw new Error('LiveKit keys not configured');
  const at = new AccessToken(API_KEY, API_SECRET, { identity, name, ttl: (ttl || 600) });
  at.addGrant({ roomJoin: true, room, canPublish: true, canSubscribe: true });
  return await at.toJwt();
}

let svc = null;
function rooms() {
  if (!svc) svc = new RoomServiceClient(HTTP_URL, API_KEY, API_SECRET);
  return svc;
}
async function deleteRoom(room) {
  try { await rooms().deleteRoom(room); } catch (e) { /* room may already be gone */ }
}

module.exports = { joinToken, deleteRoom, WS_URL, configured: () => !!(API_KEY && API_SECRET) };
