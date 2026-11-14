// The event stream the kiosk keeps open (contract C8).
// The kiosk is a static page: it cannot be called, so it holds one long-lived GET to us and we write events down
// it – «Far ringer», «the caller gave up», «the parent pressed Ikke nå». Server-sent-events framing, read on the
// kiosk with fetch (EventSource cannot send the device-key header).
'use strict';

function createHub({ keepAliveMs = 20000 } = {}) {
  const clients = new Set();

  function attach(req, res) {
    res.status(200);
    res.set({
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      'X-Accel-Buffering': 'no'             // tells nginx (SWAG) not to buffer this answer
    });
    res.flushHeaders();
    res.write('data: {"type":"hello"}\n\n');
    // A comment line now and then keeps proxies from closing an idle stream, and lets the kiosk notice a dead one.
    const keepAlive = setInterval(() => { try { res.write(': ka\n\n'); } catch (e) {} }, keepAliveMs);
    if (keepAlive.unref) keepAlive.unref();
    const client = { res, keepAlive };
    clients.add(client);
    const drop = () => { clearInterval(keepAlive); clients.delete(client); };
    req.on('close', drop);
    res.on('close', drop);
    res.on('error', drop);
  }

  /** Write one event to every connected kiosk. Returns how many it was written to. */
  function send(event) {
    const frame = 'data: ' + JSON.stringify(event) + '\n\n';
    let n = 0;
    for (const c of clients) { try { c.res.write(frame); n++; } catch (e) {} }
    return n;
  }

  const online = () => clients.size > 0;

  function close() {
    for (const c of clients) { clearInterval(c.keepAlive); try { c.res.end(); } catch (e) {} }
    clients.clear();
  }

  return { attach, send, online, close };
}

module.exports = { createHub };
