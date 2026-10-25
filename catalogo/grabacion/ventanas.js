// Window scenes for the catalog's "Tomar captura" (Luis 04:57): each W[k] sets up the board, reaches window k
// and calls shot(screen) so the catalog server takes the picture. Runs inside sim.html (or host.mjs for tests).
globalThis.W = globalThis.W || {};
// Slow machine (Raspberry Pi, Luis 2026-10-24 18:43: captures taken before the window was ready): the fixed pauses of
// these scenes last longer, and shot() waits for the screen itself (stack drawn, animations finished, pictures loaded)
const LENTO = globalThis.LENTO || ((navigator.hardwareConcurrency || 8) <= 4 || /aarch64|arm/i.test(navigator.userAgent) ? 2.5 : 1);
const wait0 = globalThis.wait;
const wait = (ms) => wait0(ms * LENTO);
const pageOf = (screen) => (screen === 'mesa' ? T.mesa : P[screen]);
// Wait until a screen is still: no animation running, every picture loaded, and on the table the whole stack drawn
const ready = async (screen, test) => {
  const pg = pageOf(screen); if (!pg) return;
  const lim = { timeout: 20000 * LENTO };
  if (screen === 'mesa') {
    const s = await st('Luis').catch(() => null), n = s && s.state && s.state.stack ? s.state.stack.length : 0;
    if (n) await pg.waitForFunction((n) => document.querySelectorAll('#pila [data-stack]:not(.out)').length >= n, n, lim).catch(() => {});
  }
  if (test) await pg.waitForFunction(test, null, lim).catch(() => {});
  await pg.waitForFunction(() => !(window.FX && (FX.busy || FX.defeating)) && [...document.images].every((i) => !i.offsetParent || i.complete), null, lim).catch(() => {});
};
globalThis.wready = ready;
const tidy = (n) => P[n].evaluate((keepZoom) => { document.querySelectorAll('.overlay,.g-quit' + (keepZoom ? '' : ',.g-ov.gz')).forEach((o) => o.remove()); const sk = document.getElementById('gskip'); if (sk && !sk.classList.contains('hidden')) { const c = [...sk.querySelectorAll('button')].find((b) => /Cancelar/.test(b.textContent)); if (c) c.click(); } const me = document.querySelector('#grail .gr.me:not(.on)'); if (me) me.click(); }, !!globalThis.KEEPZOOM).catch(() => {});
globalThis.tidy = tidy;
const shot = async (screen, name, test) => { await wait(1200); await ready(screen, test); await wait(300); if (!globalThis.KEEPZOOM && P[screen]) await P[screen].evaluate(() => document.querySelectorAll('.g-ov.gz').forEach((o) => o.remove())).catch(() => {}); mark('shot', { screen, name }); await shotTaken(name); };
// The catalog server says when it has the picture (globalThis.__shotOk[name], Luis 2026-10-25: faster photos): the scene
// goes on at once instead of always waiting 1.8 s (4.5 s on the Pi). An older server never says: then the old wait
const shotTaken = async (name) => { const t = Date.now() + (globalThis.SHOTWAIT || 1800) * LENTO; while (Date.now() < t) { if (globalThis.__shotOk && __shotOk[name]) { delete __shotOk[name]; return; } await wait0(60); } };
globalThis.shot = shot;
// Click the first visible button whose text contains t (in a tablet)
const tap = (n, t) => P[n].evaluate((t) => { const b = [...document.querySelectorAll('button')].find((x) => x.offsetParent && x.textContent.trim().includes(t)); if (b) b.click(); return !!b; }, t);
const log = async (n, what) => { const s = await st(n).catch(() => null); mark('log', { what, n, input: s && s.input, phase: s && s.state && s.state.phaseKey, stack: s && s.state && s.state.stack && s.state.stack.length, life: s && s.state && s.state.players && s.state.players.map((p) => p.life).join(',') }); };
globalThis.wlog = log;
const tapSel = (n, sel) => P[n].evaluate((sel) => { const b = [...document.querySelectorAll(sel)].find((x) => x.offsetParent); if (b) b.click(); return !!b; }, sel);
const waitIn = async (n, test, ms = 15000) => { const t = Date.now() + ms; while (Date.now() < t) { if (await test(await st(n))) return true; await wait(400); } return false; };
// Let the others pass until n has priority (with the stack still full)
// Let the others pass until n has to declare blockers
const toBlock = async (n) => { for (let i = 0; i < 50; i++) { const s = await st(n); if (s && s.input === 'InputBlock') return true; for (const o of Object.keys(P)) { if (o === n) continue; const x = await st(o); if (canPass(x)) { await send(o, { type: 'ok' }); await wait(250); } } await wait(400); } return false; };
const prioOf = async (n) => { for (let i = 0; i < 40; i++) { if (canPass(await st(n))) return true; for (const o of Object.keys(P)) { if (o === n) continue; const x = await st(o); if (canPass(x)) { await send(o, { type: 'ok' }); await wait(250); } } await wait(400); } return false; };
const LAN = 'Plains;Plains;Plains;Island;Island;Island;Swamp;Swamp;Swamp;Mountain;Mountain;Mountain;Forest;Forest;Forest;Command Tower;Sol Ring';
const HAND = 'Swords to Plowshares;Mulldrifter;Cultivate;Counterspell;Forest';
// Start a spell and stop at its first panel (no answers given)
const startCast = async (n, name, zone = 'hand') => { await clrQ(n); await send(n, { type: 'card', id: await cid(n, zone, name) }); await wait(2500); };
const cancelAll = async (n) => { for (let i = 0; i < 4; i++) { const s = await st(n); if (s && s.buttons && s.buttons.cancelEnabled) { await send(n, { type: 'cancel' }); await wait(800); } else break; } await P[n].evaluate(() => document.querySelectorAll('.overlay,.g-quit').forEach((o) => o.remove())); };
// Back to the room with everyone seated: the lobby shots
const toLobby = async () => { await send('Luis', { type: 'endMatch' }); await wait(4000); await P.Luis.waitForSelector('#lobby:not(.hidden)', { timeout: 20000 }).catch(() => {}); await wait(2000); };

// ---- table (projected screen) ----
W.sala = async () => {
  await toLobby(); for (const n of Object.keys(P)) await send(n, { type: 'ready', on: true }); await wait(5000);
  // The hidden Simular game has no internet link of its own (Pi 2026-10-24: «Conectando…» instead of the code): after a
  // while, draw the home Wi-Fi code the table shows when there is no internet
  const qr = () => !!document.querySelector('#qrs .qrbox .code:not(.wait)');
  if (!(await T.mesa.waitForFunction(qr, null, { timeout: 15000 * LENTO }).then(() => true, () => false)))
    await T.mesa.evaluate(() => { if (typeof lobby !== 'undefined' && lobby) renderLobby({ ...lobby, tunnelStatus: 'error' }); }).catch(() => {});
  await shot('mesa', 'sala', qr);
};
W.mesa = async () => { await scene({ p0battlefield: LAN + ';Darksteel Angel', p0hand: HAND, p1hand: 'Kodama\'s Reach;Mulldrifter;Counterspell', p0graveyard: 'Brainstorm' }); await imgsReady(); await wait(3000); await shot('mesa', 'mesa'); };
W['mesa-pila'] = async () => {
  // Same steps as W.pila (Luis keeps priority and casts two spells), which works on the Pi; then Pablo tries to answer with
  // Counterspell, waiting for his own priority without making anyone pass (Pi 2026-10-24: only Lava Spike reached the stack).
  // Luis keeps a Counterspell in hand and his «pasar solo» is off: with nothing left to do his seat passed by itself
  // (Seat.autoPass) and the stack resolved before the photo.
  await scene({ p0battlefield: LAN + ';Darksteel Angel', p0hand: 'Lava Spike;Shock;Counterspell', p1battlefield: 'Island;Island;Island', p1hand: 'Counterspell' });
  await send('Luis', { type: 'autoPass', on: false }); await wait(300);
  await cast('Luis', 'Lava Spike', [{ picks: [{ player: 1 }] }]); await prioOf('Luis');
  await cast('Luis', 'Shock', [{ picks: [{ player: 2 }] }]);
  const ok2 = await waitIn('Luis', (s) => s && s.state && s.state.stack && s.state.stack.length >= 2, 20000 * LENTO); await log('Luis', 'mesa-pila stack2 ' + ok2);
  if (await waitIn('Pablo', (s) => canPass(s), 8000 * LENTO)) {
    const sid = (await st('Pablo')).state.stack[0].id; await cast('Pablo', 'Counterspell', [{ picks: [{ stack: sid }] }]);
    await waitIn('Luis', (s) => s && s.state && s.state.stack && s.state.stack.length >= 3, 10000 * LENTO);
  }
  await log('Luis', 'mesa-pila antes de la foto');
  await shot('mesa', 'mesa-pila', () => document.querySelectorAll('#pila [data-stack]:not(.out)').length >= 2); await log('Luis', 'mesa-pila foto');
  await send('Luis', { type: 'autoPass', on: true }); await passAll();
};
const toAttack = async (who) => { await send(who, { type: 'ok' }); await wait(1500); for (let i = 0; i < 20; i++) { const s = await st(who); if (/Attack/.test(s.input || '')) return true; for (const n of Object.keys(P)) { const x = await st(n); if (canPass(x) && n !== who) await send(n, { type: 'ok' }); } await wait(700); } return false; };
const finishCombat = async () => { for (let i = 0; i < 40; i++) { const s = await st('Luis'); if (/MAIN2|END/.test(s.state.phaseKey || '')) return; for (const n of Object.keys(P)) { const x = await st(n); if (x && /InputAttack|InputBlock/.test(x.input || '')) await send(n, { type: 'ok' }); else if (canPass(x)) await send(n, { type: 'ok' }); } await wait(500); } };
const pabloAttacks = async () => {
  await scene({ active: 'p1', p0battlefield: LAN + ';Darksteel Angel;Avenger of Zendikar', p1battlefield: 'Forest;Forest;Forest;Island;Island;Mountain;Command Tower;Ashling, the Limitless|IsCommander;Risen Reef;Faeburrow Elder;Blightbelly Rat' }, 'Pablo', 'MAIN1');
  const id = (n) => cid('Pablo', 'battlefield', n, 'Pablo');
  const A = await id('Ashling, the Limitless'), R = await id('Risen Reef'), B = await id('Blightbelly Rat'), F = await id('Faeburrow Elder');
  await toAttack('Pablo');
  await send('Pablo', { type: 'attackAssign', cards: [A, R], player: 0 }); await wait(900);
  await send('Pablo', { type: 'attackAssign', cards: [B], player: 0 }); await wait(900);
  await send('Pablo', { type: 'attackAssign', cards: [F], player: 3 }); await wait(1200);
  return { A, R, B, F };
};
W['mesa-combate'] = async () => { await pabloAttacks(); await send('Pablo', { type: 'ok' }); await waitIn('Luis', (s) => s.input === 'InputBlock'); await shot('mesa', 'mesa-combate'); await finishCombat(); };
const endGame = async () => {
  for (const pg of [T.mesa, P.Luis]) await pg.evaluate(() => { window.MTG_FOCUS_DEMO = 1; }).catch(() => {});
  await scene({ p0battlefield: LAN + ';Darksteel Angel', p0hand: 'Lava Spike', p1life: 3 });
  await cast('Luis', 'Lava Spike', [{ picks: [{ player: 1 }] }]); await passAll(); await settle();
  for (const n of ['Malaguita', 'Calero', 'Albertito', 'Elena']) { await send(n, { type: 'concede' }); await wait(2500); }
  await wait(4000);
};
W['mesa-fin'] = async () => { await endGame(); await T.mesa.waitForFunction(() => !!document.getElementById('winov') && !(window.FX && (FX.busy || FX.defeating)), null, { timeout: 60000 }).catch(() => {}); await wait(2500); await shot('mesa', 'mesa-fin');
  // the same ending on Luis's tablet: the «fin» picture too, so «Tomar todas las capturas» need not end a second game for it
  if (await P.Luis.waitForFunction(() => !!document.querySelector('.wt') && !(window.FX && (FX.busy || FX.defeating)), null, { timeout: 20000 * LENTO }).then(() => true, () => false)) await shot('Luis', 'fin');
};

// ---- tablet ----
// p152: pantallas sueltas (login.html / usuarios.html con ?sim=, sin servidor); al acabar vuelve a la partida
const solta = async (url, name) => { const back = await P.Luis.evaluate(() => location.href); await P.Luis.evaluate((u) => { location.href = u; }, url).catch(() => {}); await wait(3500); await shot('Luis', name); await P.Luis.evaluate((u) => { location.href = u; }, back).catch(() => {}); await wait(3000); await P.Luis.waitForFunction(() => typeof MTG !== 'undefined', null, { timeout: 30000 }).catch(() => {}); await wait(1500); };
W.login = () => solta('/login.html?sim=login', 'login');
W.usuarios = () => solta('/usuarios.html?sim=1', 'usuarios');
W.entrar = async () => { await toLobby(); await wait(4000); await shot('Luis', 'entrar'); };
W.mulligan = async () => {
  await toLobby();
  for (const n of Object.keys(P)) await send(n, { type: 'ready', on: true });
  await wait(1500); await send('Luis', { type: 'start' });
  await P.Luis.waitForSelector('.gm13-keep', { timeout: 120000 }); await wait(3000);
  await shot('Luis', 'mulligan');
  for (const n of Object.keys(P)) await P[n].click('.gm13-keep').catch(() => {});
  await waitIn('Luis', (s) => s && s.state && s.state.turn >= 1 && s.state.phaseKey, 40000);
};
W.turno = async () => { await scene({ p0battlefield: LAN + ';Darksteel Angel;Avenger of Zendikar', p0hand: HAND, p0graveyard: 'Brainstorm;Counterspell' }); await imgsReady(); await shot('Luis', 'turno'); };
W.carta = async () => {
  globalThis.KEEPZOOM = true;
  await scene({ p0battlefield: LAN + ';Darksteel Angel', p0hand: HAND }); await imgsReady();
  await P.Luis.evaluate(() => { const c = [...document.querySelectorAll('#ghand .gc, #ghand [data-id], .g-hand .gc')].find((x) => x.offsetParent) || [...document.querySelectorAll('.gc')].find((x) => x.offsetParent); c.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true })); });
  await P.Luis.waitForFunction(() => { const o = document.querySelector('.g-ov.gz'); return o && [...o.querySelectorAll('img')].length && [...o.querySelectorAll('img')].every((i) => i.complete && i.naturalWidth); }, null, { timeout: 15000 }).catch(() => {});
  await wait(1200); await shot('Luis', 'carta'); globalThis.KEEPZOOM = false;
  await P.Luis.evaluate(() => { document.querySelectorAll('.gc').forEach((c) => c.dispatchEvent(new PointerEvent('pointerleave', { bubbles: true }))); document.querySelectorAll('.g-ov.gz').forEach((o) => o.remove()); });
};
W.rival = async () => { await scene({ p0battlefield: LAN + ';Darksteel Angel', p0hand: HAND }); await P.Luis.evaluate(() => document.querySelector('#grail [data-seat="1"]').click()); await imgsReady(); await shot('Luis', 'rival'); };
W.responder = async () => {
  await scene({ active: 'p1', p0battlefield: LAN + ';Darksteel Angel', p0hand: 'Counterspell;Swords to Plowshares', p1hand: 'Lava Spike' }, 'Pablo', 'MAIN1');
  await cast('Pablo', 'Lava Spike', [{ picks: [{ player: 0 }] }]); await prioOf('Luis');
  await shot('Luis', 'responder'); await passAll();
};
W.aviso = async () => {
  await scene({ p0battlefield: LAN + ';Darksteel Angel', p0hand: 'Lava Spike' });
  await cast('Luis', 'Lava Spike', [{ picks: [{ player: 1 }] }]); await wait(800);
  await shot('Calero', 'aviso'); await passAll();
};
W.pila = async () => {
  await scene({ p0battlefield: LAN + ';Darksteel Angel', p0hand: 'Lava Spike;Shock;Counterspell' });
  await cast('Luis', 'Lava Spike', [{ picks: [{ player: 1 }] }]); await prioOf('Luis');
  const tr = await cast('Luis', 'Shock', [{ picks: [{ player: 2 }] }]); mark('log', { what: 'pila tr', tr: JSON.stringify(tr).slice(0, 500) });
  const ok2 = await waitIn('Luis', (s) => s && s.state && s.state.stack.length >= 2, 20000); await log('Luis', 'pila stack2 ' + ok2); await wait(1200);
  const ok3 = await waitIn('Luis', async () => tap('Luis', 'Ver pila'), 15000); mark('log', { what: 'verpila ' + ok3 });
  await P.Luis.waitForFunction(() => [...document.querySelectorAll('.overlay img, [class*=stk] img')].every((i) => i.complete && i.naturalWidth), null, { timeout: 15000 }).catch(() => {}); await wait(2500); await shot('Luis', 'pila'); await passAll();
};
W.objetivo = async () => { await scene({ p0battlefield: LAN, p0hand: 'Swords to Plowshares' }); await startCast('Luis', 'Swords to Plowshares'); await shot('Luis', 'objetivo'); await cancelAll('Luis'); };
W.atacar = async () => {
  await scene({ p0battlefield: LAN + ';Darksteel Angel;Avenger of Zendikar;Akroma, Angel of Fury' }); await toAttack('Luis');
  const D = await cid('Luis', 'battlefield', 'Darksteel Angel'); await send('Luis', { type: 'attackAssign', cards: [D], player: 1 }); await wait(1200);
  await shot('Luis', 'atacar'); await send('Luis', { type: 'ok' }); await finishCombat();
};
W.bloquear = async () => {
  const { R } = await pabloAttacks(); await send('Pablo', { type: 'ok' });
  await toBlock('Luis'); await wait(1500);
  await shot('Luis', 'bloquear'); await finishCombat();
};
// Window «danio» («Repartir el daño»): the damage screen of p167 (Mejoras, 2026-10-25).
// Luis 2026-10-25: the photo shows the new damage screen, one list with every attacker of the combat:
//  - Rampaging Hippo 5/6 trample, blocked by Darksteel Angel (4/4 flying, indestructible) and Avenger of Zendikar 5/5:
//    the row being asked (− / +, «letal: N», «indestructible, no muere»; trample: nothing to Luis before 4 + 5)
//  - Gifted Aetherborn 2/3 deathtouch, blocked by Grizzly Bears and Runeclaw Bear (deathtouch: 1 is lethal to each)
//  - Hill Giant 3/3, blocked by Siege Mastodon 3/5 alone
//  - Risen Reef 1/1, not blocked («sin bloquear · 1 daño»)
W.danio = async () => {
  await scene({ active: 'p1', p0battlefield: LAN + ';Darksteel Angel;Avenger of Zendikar;Grizzly Bears;Runeclaw Bear;Siege Mastodon',
    p1battlefield: 'Forest;Forest;Forest;Island;Island;Mountain;Command Tower;Rampaging Hippo;Gifted Aetherborn;Hill Giant;Risen Reef' }, 'Pablo', 'MAIN1');
  const id = (n) => cid('Pablo', 'battlefield', n, 'Pablo');
  const H = await id('Rampaging Hippo'), G = await id('Gifted Aetherborn'), HG = await id('Hill Giant'), R = await id('Risen Reef');
  await toAttack('Pablo'); await send('Pablo', { type: 'attackAssign', cards: [H, G, HG, R], player: 0 }); await wait(1200); await send('Pablo', { type: 'ok' });
  await log('Luis', 'danio tras atacar'); await toBlock('Luis'); await log('Luis', 'danio bloqueo');
  const blocks = [['Darksteel Angel', H], ['Avenger of Zendikar', H], ['Grizzly Bears', G], ['Runeclaw Bear', G], ['Siege Mastodon', HG]];
  for (const [b, a] of blocks) { await send('Luis', { type: 'blockAssign', blocker: await cid('Luis', 'battlefield', b), attacker: a }); await wait(700); }
  await log('Luis', 'danio bloqueado'); await send('Luis', { type: 'ok' });
  // Until Pablo's tablet shows the damage list (p167: .g5l); the others pass or confirm their blocks
  for (let i = 0; i < 40; i++) { if (await P.Pablo.evaluate(() => !!document.querySelector('.g5l .g5r.on'))) break; for (const n of Object.keys(P)) { const x = await st(n); if (n !== 'Pablo' && x && (/InputBlock/.test(x.input || '') || canPass(x))) await send(n, { type: 'ok' }); } await wait(600); }
  await log('Pablo', 'danio shot'); await shot('Pablo', 'danio', () => !!document.querySelector('.g5l .g5r.on'));
  // Confirm the split shown (lethal to each in order) so the combat can end
  await P.Pablo.click('#gpanel .gp-foot .g-gold').catch(() => {}); await wait(800); await finishCombat();
};
W.formas = async () => { await scene({ p0battlefield: LAN, p0hand: 'Mulldrifter' }); await startCast('Luis', 'Mulldrifter'); await shot('Luis', 'formas'); await cancelAll('Luis'); };
W.costes = async () => { await scene({ p0battlefield: LAN + ';Island;Island;Island', p0hand: 'Whispers of the Muse' }); await startCast('Luis', 'Whispers of the Muse'); await shot('Luis', 'costes'); await cancelAll('Luis'); };
W.x = async () => { await scene({ p0battlefield: LAN + ';Swamp;Swamp;Swamp', p0hand: 'Black Sun\'s Zenith' }); await startCast('Luis', 'Black Sun\'s Zenith'); await shot('Luis', 'x'); await cancelAll('Luis'); };
W.vidas = async () => { await scene({ p0battlefield: LAN, p0hand: 'Toxic Deluge' }); await startCast('Luis', 'Toxic Deluge'); await shot('Luis', 'vidas'); await cancelAll('Luis'); };
W.modos = async () => { await scene({ p0battlefield: LAN, p0hand: 'Return of the Wildspeaker' }); await startCast('Luis', 'Return of the Wildspeaker'); await shot('Luis', 'modos'); await cancelAll('Luis'); };
W.tipo = async () => { await scene({ p0battlefield: LAN, p0hand: 'Distant Melody' }); await cast('Luis', 'Distant Melody'); for (let i = 0; i < 20 && !(await getQ('Luis')); i++) { for (const n of Object.keys(P)) { const x = await st(n); if (canPass(x)) await send(n, { type: 'ok' }); } await wait(500); } await wait(1500); await shot('Luis', 'tipo'); await settle().catch(() => {}); };
W.nombrar = async () => { await scene({ p0battlefield: LAN, p0hand: 'Pithing Needle' }); await cast('Luis', 'Pithing Needle'); for (let i = 0; i < 20 && !(await getQ('Luis')); i++) { for (const n of Object.keys(P)) { const x = await st(n); if (canPass(x)) await send(n, { type: 'ok' }); } await wait(500); } await wait(1500); await shot('Luis', 'nombrar'); await settle().catch(() => {}); };
W.disparadores = async () => {
  await scene({ p0battlefield: LAN + ';Cream of the Crop', p0hand: 'Avenger of Zendikar' });
  await cast('Luis', 'Avenger of Zendikar');
  for (let i = 0; i < 30 && !(await getQ('Luis')); i++) { for (const n of Object.keys(P)) { const x = await st(n); if (canPass(x)) await send(n, { type: 'ok' }); } await wait(500); }
  await wait(1500); await shot('Luis', 'disparadores'); await settle().catch(() => {});
};
W.buscar = async () => { await scene({ p0battlefield: LAN, p0hand: 'Cultivate', p0library: 'Island;Forest;Plains;Swamp;Forest;Mountain;Island;Forest;Plains;Mountain' }); await cast('Luis', 'Cultivate'); for (let i = 0; i < 20 && !(await getQ('Luis')); i++) { for (const n of Object.keys(P)) { const x = await st(n); if (canPass(x)) await send(n, { type: 'ok' }); } await wait(500); } await wait(1500); await shot('Luis', 'buscar'); await settle().catch(() => {}); };
W.descartar = async () => { await scene({ p0battlefield: LAN, p0hand: 'Faithless Looting;Mulldrifter;Cultivate;Counterspell' }); await cast('Luis', 'Faithless Looting'); for (let i = 0; i < 20; i++) { if (await P.Luis.evaluate(() => !!document.querySelector('#gpanel button'))) { const s = await st('Luis'); if (/Select|Discard/i.test(s.input || '') || await getQ('Luis')) break; } for (const n of Object.keys(P)) { const x = await st(n); if (canPass(x)) await send(n, { type: 'ok' }); } await wait(500); } await wait(1500); await shot('Luis', 'descartar'); await settle().catch(() => {}); };
W.adivinar = async () => { await scene({ p0battlefield: LAN, p0hand: 'Preordain' }); await cast('Luis', 'Preordain'); for (let i = 0; i < 20 && !(await getQ('Luis')); i++) { for (const n of Object.keys(P)) { const x = await st(n); if (canPass(x)) await send(n, { type: 'ok' }); } await wait(500); } await wait(1500); await shot('Luis', 'adivinar'); };
W.sino = async () => { await scene({ p0battlefield: LAN + ';Cream of the Crop', p0hand: 'Darksteel Angel' }); await cast('Luis', 'Darksteel Angel'); for (let i = 0; i < 30; i++) { const s = await st('Luis'); if (await getQ('Luis') || /Confirm/.test(s.input || '')) break; for (const n of Object.keys(P)) { const x = await st(n); if (canPass(x)) await send(n, { type: 'ok' }); } await wait(500); } await wait(1500); await shot('Luis', 'sino'); };
W.saltar = async () => { await scene({ p0battlefield: LAN + ';Darksteel Angel', p0hand: HAND }); await P.Luis.evaluate(() => { const b = [...document.querySelectorAll('#gbar button')].find((x) => /⏩|Saltar/.test(x.getAttribute('aria-label') || x.title || x.textContent)); b.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true })); }); await wait(900); await shot('Luis', 'saltar'); await P.Luis.evaluate(() => document.dispatchEvent(new PointerEvent('pointerup', { bubbles: true }))); };
W.historial = async () => { await scene({ p0battlefield: LAN + ';Darksteel Angel', p0hand: 'Lava Spike;Swords to Plowshares' }); await cast('Luis', 'Lava Spike', [{ picks: [{ player: 1 }] }]); await passAll(); await tapSel('Luis', '.gb-hist'); await wait(2000); await shot('Luis', 'historial'); await tapSel('Luis', '.gb-hist'); };
W.rendirse = async () => { await scene({ p0battlefield: LAN + ';Darksteel Angel', p0hand: HAND }); await tapSel('Luis', '.gb-quit'); await shot('Luis', 'rendirse'); await P.Luis.evaluate(() => document.querySelectorAll('.g-quit').forEach((o) => o.remove())); };
// Luis 19/10: wait for the win screen itself, after every defeat scene has played
W.fin = async () => { await endGame(); await P.Luis.waitForFunction(() => !!document.querySelector('.wt') && !(window.FX && (FX.busy || FX.defeating)), null, { timeout: 60000 }).catch(() => {}); await wait(2500); await shot('Luis', 'fin'); };
// Answer whatever a scene left open (questions, overlays) so the next one starts clean
const dflt = (q) => q.kind === 'buttons' ? { index: 0 } : q.kind === 'list' ? { indices: Array.from({ length: Math.max(1, q.min || 0) }, (_, k) => k).slice(0, Math.max(1, q.max || 1)) } : q.kind === 'order' ? { indices: q.labels.map((_, k) => k) } : q.kind === 'topbottom' ? { top: q.labels.map((_, k) => k), bottom: [] } : q.kind === 'input' ? { text: String(q.initial || '0') } : {};
// A tablet whose question was answered behind its back keeps the panel: reload it
const reload = async (n) => { await P[n].evaluate(() => location.reload()).catch(() => {}); await wait(2500); await P[n].waitForFunction(() => typeof MTG !== 'undefined' && typeof last !== 'undefined' && !!last, null, { timeout: 30000 }).catch(() => {}); await P[n].evaluate(() => { if (!window.__hq) { window.__hq = 1; MTG.on('choice', (m) => { window.__q = m; }); } }); await wait(1500); };
const stale = (n) => P[n].evaluate(() => { const p = document.getElementById('gpanel'); return !!(p && p.offsetParent && /Te toca elegir|Elige una opci|Elige el objetivo|Elige un tipo|Elige uno/i.test(p.innerText)); });
globalThis.clean = async () => {
  for (const n of Object.keys(P)) if (!(await getQ(n)) && await stale(n)) await reload(n);
  for (let i = 0; i < 12; i++) {
    let any = false;
    for (const n of Object.keys(P)) {
      await tidy(n);
      const q = await getQ(n); if (q) { any = true; await send(n, { type: 'reply', id: q.id, value: dflt(q) }); await clrQ(n); await wait(700); await reload(n); }
      const s = await st(n); if (s && s.buttons && s.buttons.cancelEnabled && !/PassPriority|Attack|Block/.test(s.input || '')) { any = true; await send(n, { type: 'cancel' }); await wait(700); }
    }
    if (!any) break;
  }
  // In the room (no game: after «La sala» or «Entrar», which now follow each other without a new game) there is nothing to settle
  const sala = await P.Luis.evaluate(() => { const l = document.getElementById('lobby'); return !!(l && !l.classList.contains('hidden')); }).catch(() => false);
  if (!sala) await settle().catch(() => {});
};
for (const k of Object.keys(W)) { const f = W[k]; if (f.__w) continue; W[k] = async () => { await clean(); try { return await f(); } catch (e) { if (!/toPhase|settle/.test(e.message)) throw e; await clean(); return f(); } }; W[k].__w = 1; }
return 'ok';
