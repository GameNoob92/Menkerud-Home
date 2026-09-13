// Unit tests: the Beskjeder proxy maps Discord's message list to what the kiosk needs and reacts with ✅.
process.env.DISCORD_BOT_TOKEN = 'Bot.test';
const assert = require('assert');
const calls = [];
global.fetch = async (url, opts) => {
  calls.push({ url, opts: opts || {} });
  if (/\/channels\/555\/messages\?limit=10$/.test(url)) return { ok: true, status: 200, json: async () => [
    { id: '901', channel_id: '555', content: 'Hei <:pizza:1>', timestamp: '2026-09-13T10:00:00.000Z', author: { id: '111', username: 'mor', global_name: 'Mor', bot: false, avatar: 'x', discriminator: '0' }, attachments: [{ id: 'a1', url: 'https://cdn/secret.png' }], embeds: [], mentions: [] },
    { id: '900', channel_id: '555', content: '', author: { id: '5', username: 'hook', bot: true }, webhook_id: '77', attachments: [] }
  ] };
  if (/\/channels\/404\/messages/.test(url)) return { ok: false, status: 403, json: async () => ({ message: 'Missing Access' }) };
  if (/\/reactions\/%E2%9C%85\/@me$/.test(url) && opts.method === 'PUT') return { ok: true, status: 204, json: async () => ({}) };
  return { ok: false, status: 500, json: async () => ({}) };
};
const discord = require('../src/discord');

(async () => {
  const list = await discord.channelMessages('555', '10');
  assert.strictEqual(calls[0].opts.headers.Authorization, 'Bot Bot.test', 'the bot token is sent by the server');
  assert.strictEqual(list.length, 2, 'both messages passed on');
  assert.deepStrictEqual(Object.keys(list[0]).sort(), ['attachments', 'author', 'channel_id', 'content', 'id', 'timestamp', 'webhook_id'], 'only the fields the kiosk shows');
  assert.strictEqual(list[0].author.global_name, 'Mor', 'author kept');
  assert.strictEqual(list[0].attachments.length, 1, 'attachment count kept');
  assert.strictEqual(list[0].attachments[0].url, undefined, 'attachment urls not passed on');
  assert.strictEqual(list[1].author.bot, true, 'bot flag kept so the kiosk can skip it');
  assert.strictEqual(list[1].webhook_id, '77', 'webhook id kept');
  console.log('ok - channel messages mapped for the kiosk');

  await assert.rejects(discord.channelMessages('404', 10), /403/, 'Discord errors surface with the status');
  console.log('ok - Discord error surfaces with its status');

  await discord.react('555', '901', '✅');
  const r = calls[calls.length - 1];
  assert.ok(/\/channels\/555\/messages\/901\/reactions\/%E2%9C%85\/@me$/.test(r.url) && r.opts.method === 'PUT', 'reaction PUT: ' + r.url);
  console.log('ok - reaction sent as PUT with the encoded emoji');

  console.log('ALL GOOD');
})().catch(e => { console.error('FAIL', e); process.exit(1); });
