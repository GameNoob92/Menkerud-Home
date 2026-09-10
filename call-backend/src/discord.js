// Discord DM via the bot's HTTP API (no gateway connection needed to send a DM).
// Reuses the family's existing bot token. The bot must share a server with the parent
// and the parent must allow DMs from server members.
const API = 'https://discord.com/api/v10';
const TOKEN = process.env.DISCORD_BOT_TOKEN || '';

function headers() {
  return { Authorization: 'Bot ' + TOKEN, 'Content-Type': 'application/json' };
}

// Open (or reuse) the DM channel with a user and post a message. Returns ids for later edits.
async function dm(userId, content) {
  if (!TOKEN) throw new Error('Discord bot token not configured');
  const chRes = await fetch(API + '/users/@me/channels', {
    method: 'POST', headers: headers(), body: JSON.stringify({ recipient_id: userId })
  });
  const ch = await chRes.json().catch(() => ({}));
  if (!ch.id) throw new Error('could not open DM channel (' + chRes.status + ')');
  const msgRes = await fetch(API + '/channels/' + ch.id + '/messages', {
    method: 'POST', headers: headers(), body: JSON.stringify({ content })
  });
  const msg = await msgRes.json().catch(() => ({}));
  if (!msg.id) throw new Error('could not send DM (' + msgRes.status + ')');
  return { channelId: ch.id, messageId: msg.id };
}

// Best-effort edit for the call lifecycle (answered / cancelled / missed / ended).
async function edit(ref, content) {
  if (!ref || !ref.channelId || !ref.messageId) return;
  try {
    await fetch(API + '/channels/' + ref.channelId + '/messages/' + ref.messageId, {
      method: 'PATCH', headers: headers(), body: JSON.stringify({ content })
    });
  } catch (e) { /* non-fatal */ }
}

module.exports = { dm, edit, configured: () => !!TOKEN };
