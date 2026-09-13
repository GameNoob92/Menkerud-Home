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

// Beskjeder: the kiosk cannot call these itself – Discord answers 403 {"code": 40333} to any bot token that
// arrives with a browser User-Agent – so it asks us. Only the fields the kiosk shows are passed on.
async function channelMessages(channelId, limit) {
  if (!TOKEN) throw new Error('Discord bot token not configured');
  const n = Math.min(Math.max(Number(limit) || 10, 1), 50);
  const r = await fetch(API + '/channels/' + channelId + '/messages?limit=' + n, { headers: { Authorization: 'Bot ' + TOKEN } });
  const list = await r.json().catch(() => null);
  if (!r.ok || !Array.isArray(list)) throw Object.assign(new Error('Discord svarte ' + r.status), { status: r.status });
  return list.map(m => ({
    id: m.id, channel_id: m.channel_id, content: m.content, timestamp: m.timestamp, webhook_id: m.webhook_id,
    author: m.author ? { id: m.author.id, username: m.author.username, global_name: m.author.global_name, bot: !!m.author.bot } : null,
    attachments: (m.attachments || []).map(a => ({ id: a.id }))
  }));
}

// «Hørt!» on the kiosk → a reaction on the parent's message (the bot needs Read Message History + Add Reactions there).
async function react(channelId, messageId, emoji) {
  if (!TOKEN) throw new Error('Discord bot token not configured');
  const r = await fetch(API + '/channels/' + channelId + '/messages/' + messageId + '/reactions/' + encodeURIComponent(emoji || '✅') + '/@me', {
    method: 'PUT', headers: { Authorization: 'Bot ' + TOKEN }, body: ''
  });
  if (!r.ok) throw Object.assign(new Error('Discord svarte ' + r.status), { status: r.status });
}

module.exports = { dm, edit, channelMessages, react, configured: () => !!TOKEN };
