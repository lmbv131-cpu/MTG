// Scenes added by the catalog after the app package (Luis 21:42: "every row without a simulation gets one if it can").
// The catalog server injects this file into sim.html (grabacion/extra.js). Cards are placed straight on the board,
// so no deck is needed. Uses the globals of the app's sim scripts (kwCast, kwCombat, kwUpkeep, act, cid, passAll...).
globalThis.KW = globalThis.KW || {};
// Luis 19/10 01:20: the decks of the simulated game
try { Object.assign(DECK, { Luis: 'Multiverse Reforged', Malaguita: 'World Shaper', Calero: 'Limit Break', Albertito: 'Scions & Spellcraft', Pablo: 'Eldrazi Unbound', Elena: 'Squirreled Away' }); } catch (e) { /* older sim.html */ }
// Luis 19/10 01:20: the table video started on the "who starts" roulette. In the simulated game the table skips it.
(async () => {
  const nap = (ms) => new Promise((r) => setTimeout(r, ms));
  for (let i = 0; i < 3000; i++) {
    try { if (globalThis.T && T.mesa && await T.mesa.evaluate(() => { if (typeof runIntro !== 'function') return false; window.runIntro = function () {}; return true; })) return; } catch (e) { /* page still loading */ }
    await nap(200);
  }
})();
globalThis.SC = globalThis.SC || {};
// This file runs before the app's sim scripts load: look their helpers up only when a scene plays
const pickRow = (re) => (p) => globalThis.pickRow(re)(p), digit = (n) => (p) => globalThis.digit(n)(p);
// library search: pick n cards and confirm (the last button of that panel is "No buscar nada")
const searchPick = (re, n = 1) => async (p) => { // real clicks: the cards ignore synthetic ones
  for (let k = 0; k < n; k++) {
    const cs = await p.$$('#gpanel .gs-c'); if (!cs.length) break;
    const at = re ? await p.evaluate((src) => [...document.querySelectorAll('#gpanel .gs-c')].findIndex((x) => new RegExp(src, 'i').test(x.textContent + ' ' + ((x.querySelector('img') || {}).alt || ''))), re.source) : -1;
    await cs[at >= 0 ? at : 0].click().catch(() => {}); await wait(1000);
  }
  await p.evaluate(() => { const b = [...document.querySelectorAll('#gpanel button')].find((x) => x.offsetParent && !x.disabled && /^(Listo|Aceptar|Hecho)/i.test(x.textContent.trim())); if (b) b.click(); });
  await wait(900);
};
// Luis 00:09: as fast as possible. The catalog server calls FAST() before each scene (the app's helpers are loaded by then):
// the new board is used as soon as the game shows it, instead of a fixed 3.5 s wait
globalThis.FAST = () => {
  if (!globalThis.setBoard || globalThis.setBoard.__fast) return;
  const n = (v) => (v ? String(v).split(';').filter(Boolean).length : 0);
  const fast = async (o) => {
    // Luis 01:39: each player has ONLY what the play needs, on the table and in hand: players the scene doesn't set get nothing
    o = { ...o }; for (let i = 1; i < 6; i++) { if (i > 1 && o['p' + i + 'battlefield'] == null) o['p' + i + 'battlefield'] = ''; if (o['p' + i + 'hand'] == null) o['p' + i + 'hand'] = ''; }
    // the board the game must show: card counts per player (battlefield and hand) and the phase
    const txt = board(o), get = (k) => { const m = txt.match(new RegExp('^' + k + '=(.*)$', 'm')); return m ? n(m[1]) : 0; };
    const want = Object.entries(PID).map(([name, i]) => [name, get('p' + i + 'battlefield'), get('p' + i + 'hand')]), ph = o.phase || 'UPKEEP';
    const dx = globalThis.DEVX; globalThis.DEVX = null; // extra devState options of one scene (companion)
    await send('Luis', { type: 'devState', text: txt, ...(dx || {}) });
    const t = Date.now(); await wait(700);
    while (Date.now() - t < 4000) {
      const x = await st('Luis').catch(() => null);
      const ok = x && x.state.phaseKey === ph && want.every(([name, bf, h]) => { const p = x.state.players.find((q) => q.name === name); return !p || (p.battlefield.length === bf && (p.handCount != null ? p.handCount : (p.hand || []).length) === h); });
      if (ok) break; await wait(150);
    }
    await wait(400);
  };
  fast.__fast = true; globalThis.setBoard = fast;
  // A player with nothing left to do passes by himself and has no question open: the scene runner then waited 20 s
  // for one (Luis 01:17: "Jugar una tierra" lasted 25 s). Stop waiting after about 3 s without any question.
  const act0 = globalThis.act;
  globalThis.act = (who, first, steps = [], opt = {}) => { if (opt.until) return act0(who, first, steps, opt); let idle = 0; return act0(who, first, steps, { ...opt, until: async () => ++idle >= 5 }); };
};
const L3 = 'Plains;Plains;Plains;Island;Island;Island;Swamp;Swamp;Swamp;Mountain;Mountain;Mountain;Forest;Forest;Forest;Sol Ring';
const R2 = { causa: 'Luis', otro: 'Malaguita' }, R3 = { causa: 'Luis', tu: 'Pablo', otro: 'Malaguita' };
const K = (id, card, o = {}) => { KW[id] = () => kwCast({ id, card, ...o }); };
const C = (id, attBf, attackers, defBf, blocks, more = {}) => { KW[id] = () => kwCombat({ id, attBf, attackers, defBf, blocks, ...more }); };
const U = (id, bf, o = {}) => { KW[id] = () => kwUpkeep({ id, bf, ...o }); };
const run = (k) => { const i = k.indexOf('.'); return (k.slice(0, i) === 'SC' ? SC : KW)[k.slice(i + 1)](); };
const A = (id, sc) => { KW[id] = () => run(sc); }; // same play as an existing scene
const mine = async (n) => ({ card: await cid('Luis', 'battlefield', n) });
const theirs = async (n = 'Faeburrow Elder') => ({ card: await cid('Pablo', 'battlefield', n, 'Luis') });
const tgt = (...fs) => async () => { const out = []; for (const f of fs) out.push(f === 'P' ? { picks: [{ player: 1 }] } : { picks: [await f()] }); return out; };
const ELDER = () => theirs('Faeburrow Elder');
const laterT = (f) => async () => { globalThis.LSTEPS = [{ picks: [await f()] }]; globalThis.LWHO = 'Luis'; }; // target of a trigger
const cast2 = (zone, card, steps = [], who = 'Luis') => async () => { await wait(1500); const tr = await act(who, { type: 'card', id: await cid(who, zone, card) }, typeof steps === 'function' ? await steps() : steps, { pace: 1300 }); log('kw2', card, JSON.stringify(tr)); await passAll(); };
const pre1 = (card, steps = []) => async () => { const tr = await act('Luis', { type: 'card', id: await cid('Luis', 'hand', card) }, typeof steps === 'function' ? await steps() : steps, { pace: 1300 }); log('pre', card, JSON.stringify(tr)); await passAll(); await wait(800); };
const atMain = (ph) => async (s) => s.state.activePlayerId === 0 && new RegExp(ph).test(s.state.phaseKey) && !s.state.stack.length && await prio('Luis');
const toAttack = (names) => async () => { // attack Pablo with names, no blocks, back to main 2
  await wait(1200); await send('Luis', { type: 'ok' }); await wait(1200);
  for (let i = 0; i < 30; i++) { const s = await st('Luis'); if (/Attack/.test(s.input || '')) break; await answerOthers([]); await wait(500); }
  const m = (await all()).find((p) => p.name === 'Luis');
  for (const n of names) { const c = m.battlefield.find((x) => x.name === n || x.oracleName === n); if (c) { await send('Luis', { type: 'attackAssign', cards: [c.id], player: PID.Pablo }); await wait(1200); } }
  await wait(700); await send('Luis', { type: 'ok' }); await wait(1200);
  await passPhase(atMain('MAIN2'));
};
const toCombat = async () => { // go to combat, attack with nothing, back to main 2 (beginning-of-combat triggers)
  await wait(1200); await send('Luis', { type: 'ok' }); await wait(1500);
  for (let i = 0; i < 30; i++) { const s = await st('Luis'); if (/Attack/.test(s.input || '') || /MAIN2/.test(s.state.phaseKey)) break; await answerOthers([]); await wait(500); }
  const s = await st('Luis'); if (/Attack/.test(s.input || '')) { await send('Luis', { type: 'ok' }); await wait(1200); }
  await passPhase(atMain('MAIN2'));
};

// ---------------------------------------------------------------- actions of the catalog
// Day and night: Luis casts a daybound card (it becomes day), Pablo casts nothing on his turn, so the next turn starts at night
SC.noche = async () => {
  await scene({ active: 'p0', p0battlefield: L3, p0hand: "Tovolar's Huntmaster", p1battlefield: 'Forest;Forest;Island' }, 'Luis', 'MAIN1');
  const tr = await act('Luis', { type: 'card', id: await cid('Luis', 'hand', "Tovolar's Huntmaster") }, [], { pace: 1300 }); log('noche', JSON.stringify(tr)); await passAll();
  await passPhase(async (s) => s.state.activePlayerId === 1);
  begin(['noche'], R3);
  await passPhase(async (s) => s.state.activePlayerId === 2 && /MAIN1/.test(s.state.phaseKey) && !s.state.stack.length);
  await wait(1500); await end(4000);
};
SC.modos = () => kwCast({ id: 'modos', card: 'Return of the Wildspeaker', bf: 'Craw Wurm' });
SC.tipo = () => kwCast({ id: 'tipo', card: 'Distant Melody', bf: 'Jaspera Sentinel;Llanowar Elves' });
SC.opcionales = () => kwCast({ id: 'opcionales', card: 'Slinn Voda, the Rising Deep', roles: R3, steps: [{ ui: pickRow(/Estímulo|Kicker|Pagar/) }] });
// Jugar una tierra (Luis 15:18 + 15:34): a land can affect someone, so the scene plays a Mountain that makes
// Valakut deal 3 damage to Pablo (the "te afecta" tablet)
SC.tierra = () => kwCast({ id: 'tierra', card: 'Mountain', roles: R3, defBf: '',
  lands: 'Valakut, the Molten Pinnacle;Mountain;Mountain;Mountain;Mountain;Mountain', later: [{ ok: true }, { picks: [{ player: 1 }] }], tail: 5000 });
const GATES = 'Azorius Guildgate;Boros Guildgate;Dimir Guildgate;Golgari Guildgate;Gruul Guildgate;Izzet Guildgate;Orzhov Guildgate;Rakdos Guildgate;Selesnya Guildgate';
const mazeEnd = (id) => kwCast({ id, card: "Maze's End", zone: 'battlefield', hand: '', roles: R3, defBf: '',
  lands: GATES + ";Maze's End", lib: 'Forest;Simic Guildgate;Island;Plains', steps: [{ ui: pickRow(/Puerta|Gate|Busca|Search|biblioteca|library/) }, { ui: searchPick(/Simic|Simbiosis/) }], later: [{ ui: searchPick(/Simic|Simbiosis/) }], tail: 6000 });
// Derrotas y victorias (Luis 15:20): one scene per way the game ends, by the Magic rules
SC['a_fin-vida'] = () => kwCast({ id: 'fin-vida', card: 'Lightning Bolt', roles: R3, defBf: '', extra: { p1life: 3 }, steps: tgt('P'), tail: 16000 });
SC['a_fin-veneno'] = () => kwCombat({ id: 'fin-veneno', roles: R3, attBf: 'Blighted Agent', attackers: ['Blighted Agent'], defBf: '', blocks: [], extra: { p1counters: 'POISON=9' }, post: async () => { await wait(4000); } });
SC['a_fin-comandante'] = () => kwCombat({ id: 'fin-comandante', roles: R3, attBf: 'Ghalta, Primal Hunger|IsCommander|Counters:P1P1=9', attackers: ['Ghalta, Primal Hunger'], defBf: '', blocks: [], post: async () => { await wait(4000); } });
SC['a_fin-biblioteca'] = () => kwCast({ id: 'fin-biblioteca', card: 'Sign in Blood', roles: R3, defBf: '', extra: { p1library: 'Forest' }, steps: tgt('P'), tail: 8000 });
SC['a_fin-efecto'] = () => mazeEnd('fin-efecto');
SC.adivinar = () => kwCast({ id: 'adivinar', card: 'Preordain', lib: 'Island;Forest;Craw Wurm;Plains;Swamp;Mountain' });

// ---------------------------------------------------------------- one scene per action row (Luis 23:42: play ONLY what the row is about)
const CAV = () => theirs('Cavalier of Thorns'), DEF2 = 'Faeburrow Elder;Cavalier of Thorns';
const AC = (ids, card, o = {}) => { for (const id of ids) SC['a_' + id] = () => kwCast({ id, card, roles: R3, defBf: DEF2, ...o }); };
AC(['lanzar', 'objetivo', 'resolver', 'exiliar'], 'Swords to Plowshares', { steps: tgt(CAV) });
AC(['ganar-vida'], 'Healing Salve', { roles: R2, steps: [{ ui: null }, { picks: [{ player: 0 }] }] });
AC(['danio-criatura'], 'Lightning Bolt', { steps: tgt(CAV) });
AC(['danio-jugador'], 'Lightning Bolt', { steps: tgt('P') });
AC(['girar'], 'Icy Manipulator', { zone: 'battlefield', bf: 'Icy Manipulator', hand: '', steps: tgt(ELDER) });
AC(['contadores'], 'Battlegrowth', { roles: R2, bf: 'Jaspera Sentinel', steps: tgt(() => mine('Jaspera Sentinel')) });
AC(['ficha'], 'Raise the Alarm', { roles: R2 });
AC(['sino'], 'Solemn Simulacrum', { roles: R2, lib: 'Plains;Forest;Island;Plains;Swamp;Mountain', later: [{ ok: true }, { ui: searchPick() }] });
AC(['entra', 'disparador', 'robar'], 'Mulldrifter', { roles: R2, lib: 'Plains;Island;Forest;Plains' });
AC(['sacrificar', 'descartar'], 'Archon of Cruelty', { pHand: 'Forest;Island;Cultivate', pre: async () => { globalThis.LSTEPS = [{ picks: [{ player: 1 }] }]; globalThis.LWHO = 'Luis'; } });
AC(['destruir-todo', 'sobrevive'], 'Shatter the Sky', { bf: 'Jaspera Sentinel;Darksteel Myr' });
AC(['danio-area'], 'Blasphemous Act', { bf: 'Jaspera Sentinel' });
AC(['a-la-mano'], 'Cyclonic Rift', { steps: async () => [{ ui: null }, { picks: [await CAV()] }] });
AC(['reanimar'], 'Reanimate', { extra: { p1graveyard: 'Mulldrifter;Shriekmaw' }, steps: async () => [{ picks: [{ card: await cid('Pablo', 'graveyard', 'Mulldrifter', 'Luis') }] }] });
AC(['buscar', 'mostrar', 'barajar'], 'Cultivate', { roles: R2, lib: 'Plains;Island;Forest;Plains;Swamp;Forest;Mountain;Island;Forest;Plains;Island', later: [{ ui: searchPick(null, 2) }] });
AC(['contadores-menos', 'x'], "Black Sun's Zenith", { steps: [{ ui: digit(1) }] });
AC(['control'], 'Control Magic', { steps: tgt(CAV) });
AC(['monarca', 'ordenar'], 'Palace Jailer', { pre: async () => { globalThis.LSTEPS = [{ ui: null }, { picks: [await CAV()] }]; globalThis.LWHO = 'Luis'; } });
AC(['pagar-vida'], 'Toxic Deluge', { steps: [{ ui: digit(3) }] });
AC(['habilidad'], "Elspeth, Sun's Champion", { roles: R2, zone: 'battlefield', bf: "Elspeth, Sun's Champion|Counters:LOYALTY=4", hand: '' });
AC(['moler'], 'Island', { bf: 'Hedron Crab', hand: 'Island' });
AC(['muere', 'perder-vida'], 'Shriekmaw', { bf: 'Blood Artist', steps: async () => [{ reply: { indices: [0] } }], pre: async () => { globalThis.LSTEPS = [{ picks: [await ELDER()] }]; globalThis.LWHO = 'Luis'; } });
// Pablo answers Luis's spell with a counterspell
for (const id of ['responder', 'contrarrestar']) SC['a_' + id] = async () => {
  await scene({ active: 'p0', p0battlefield: L3, p0hand: 'Swords to Plowshares', p1battlefield: 'Forest;Forest;Island;Island;Island;Faeburrow Elder;Cavalier of Thorns', p1hand: 'Counterspell' }, 'Luis', 'MAIN1');
  const cav = await cid('Pablo', 'battlefield', 'Cavalier of Thorns', 'Luis');
  await act('Luis', { type: 'card', id: await cid('Luis', 'hand', 'Swords to Plowshares') }, [{ picks: [{ card: cav }] }], { pace: 1300 });
  for (let i = 0; i < 20; i++) { const x = await st('Pablo'); if (canPass(x)) break; await wait(400); }
  const sid = (await st('Pablo')).state.stack[0].id;
  begin([id], { causa: 'Pablo', tu: 'Luis', otro: 'Malaguita' });
  await act('Pablo', { type: 'card', id: await cid('Pablo', 'hand', 'Counterspell') }, [{ picks: [{ stack: sid }] }], { pace: 1300 });
  await passAll(); await end(2500);
};

// ---------------------------------------------------------------- the same play as an existing scene
A('counter', 'SC.counter');
A('monarch', 'SC.jailer');
A('exile', 'SC.swords');
A('mill', 'SC.crab');
A('reveal', 'SC.cultivate');
A('poison', 'SC.combat');
A('time counter', 'KW.suspend');
A('scry', 'SC.adivinar');

// ---------------------------------------------------------------- removal and interaction
K('sacrifice', 'Village Rites', { bf: 'Jaspera Sentinel' });
K('destroy', 'Murder', { roles: R3, steps: tgt(ELDER) });
K('discard', 'Mind Rot', { roles: R3, pHand: 'Forest;Island;Cultivate', steps: tgt('P') });
K('regenerate', 'Drudge Skeletons', { zone: 'battlefield', bf: 'Drudge Skeletons', hand: '' });
K('tap and untap', 'Icy Manipulator', { zone: 'battlefield', bf: 'Icy Manipulator', roles: R3, steps: tgt(ELDER) });
K('copy', 'Clone', { roles: R3, defBf: 'Faeburrow Elder;Cavalier of Thorns' });
K('double', 'Raise the Alarm', { bf: 'Doubling Season' });
K('mana value', 'Culling Ritual', { lands: L3.replace(';Sol Ring', ''), roles: R3, defBf: 'Faeburrow Elder;Llanowar Elves;Cavalier of Thorns' });
KW.meld = async () => { // both halves already on the battlefield: they meld at the beginning of combat
  await scene({ active: 'p0', p0battlefield: L3 + ';Midnight Scavengers;Graf Rats', p1battlefield: 'Forest;Forest;Island' }, 'Luis', 'MAIN1');
  begin(['meld'], R2); await toCombat(); await end(2500);
};
K('transform', 'Kessig Prowler', { zone: 'battlefield', bf: 'Kessig Prowler' });
K('fight', 'Prey Upon', { bf: 'Craw Wurm', roles: R3, steps: tgt(() => mine('Craw Wurm'), ELDER) });
K('goad', 'Jeering Homunculus', { roles: R3, pre: laterT(ELDER) });

// ---------------------------------------------------------------- voting
K('vote', "Council's Judgment", { roles: R3 });
K('will of the council', "Council's Judgment", { roles: R3 });
K("council's dilemma", 'Messenger Jays');
K('secret council', 'Elrond of the White Council', { roles: R3 });
K('join forces', 'Minds Aglow', { roles: R3, later: [{ reply: (q) => ({ indices: [Math.min(2, q.labels.length - 1)] }) }] });
K('tempting offer', 'Tempt with Discovery', { lib: 'Forest;Island;Plains;Swamp;Mountain;Forest', later: [{ ui: searchPick() }, { ui: searchPick() }, { ui: searchPick() }, { ui: searchPick() }], roles: R3 });

// ---------------------------------------------------------------- game mechanics, emblems, luck
K('initiative', 'Seasoned Dungeoneer');
K('dungeon', 'Nadaar, Selfless Paladin');
K('venture into the dungeon', 'Nadaar, Selfless Paladin');
K("city's blessing", 'Twilight Prophet');
K('the ring tempts you', 'Uruk-hai Berserker');
K('time travel', 'Wibbly-wobbly, Timey-wimey', { bf: 'Lotus Bloom|Counters:TIME=3' });
K('villainous choice', 'This Is How It Ends', { roles: R3, steps: tgt(ELDER) });
K('emblem', 'Liliana of the Dark Realms', { zone: 'battlefield', bf: 'Liliana of the Dark Realms|Counters:LOYALTY=6', steps: [{ ui: pickRow(/[−-] ?6/) }] });
K('flip a coin', 'Stitch in Time');
K('devour', 'Mycoloth', { bf: 'Llanowar Elves;Elvish Mystic' }); // devour 2: the elves are eaten as Mycoloth enters (untested on the PC yet)
SC.a_moneda = () => KW['flip a coin'](); // the same coin scene as an action row (Luis 2026-10-23 04:38)
K('roll a die', 'Djinni Windseer');
K('max speed', 'Endrider Catalyzer', { zone: 'battlefield', bf: 'Endrider Catalyzer', extra: { p0speed: '4' } });

// ---------------------------------------------------------------- counters on creatures
K('monstrosity', 'Ember Swallower', { zone: 'battlefield', bf: 'Ember Swallower', roles: R3 });
K('bolster', 'Elite Scaleguard', { bf: 'Jaspera Sentinel' });
K('support', 'Gladehart Cavalry', { bf: 'Jaspera Sentinel;Tower Gargoyle' });
K('adapt', 'Incubation Druid', { zone: 'battlefield', bf: 'Incubation Druid' });
K('experience', 'Craw Wurm', { bf: 'Kalemne, Disciple of Iroas' });
K('shield', 'Boon of Safety', { bf: 'Jaspera Sentinel', steps: tgt(() => mine('Jaspera Sentinel')) });
K('stun counter', 'Cryogenic Stasis', { roles: R3, steps: tgt(ELDER) });
K('finality counter', 'Soulcoil Viper', { zone: 'battlefield', bf: 'Soulcoil Viper', gy: 'Craw Wurm' });
K('proliferate', "Tezzeret's Gambit", { bf: 'Jaspera Sentinel|Counters:P1P1=2;Lotus Bloom|Counters:TIME=3' });
K('endure', 'Fortress Kin-Guard');
K('exhaust', 'Skystreak Engineer', { zone: 'battlefield', bf: 'Skystreak Engineer' });
K('valiant', 'Monstrous Rage', { bf: 'Heartfire Hero', steps: tgt(() => mine('Heartfire Hero')) });
K('role', 'Monstrous Rage', { bf: 'Jaspera Sentinel', steps: tgt(() => mine('Jaspera Sentinel')) });

// ---------------------------------------------------------------- player counters and tokens
K('energy', "Sage of Shaila's Claim");
K('rad counters', 'Contaminated Drink', { steps: [{ ui: digit(2) }] });
K('lore counter', 'History of Benalia');
K('oil counter', 'Axiom Engraver');
K('treasure', 'Strike It Rich');
K('food', 'Bake into a Pie', { roles: R3, steps: tgt(ELDER) });
K('clue', 'Thraben Inspector');
K('investigate', 'Thraben Inspector');
K('blood', 'Voldaren Epicure', { roles: R3 });
K('gold', 'Gild', { roles: R3, steps: tgt(ELDER) });
K('powerstone', 'Argothian Opportunist');
K('map', 'Spyglass Siren');
K('junk', 'Break Down', { roles: R3, defBf: "Faeburrow Elder;Commander's Sphere", steps: tgt(() => theirs("Commander's Sphere")) });
K('incubator', 'Eyes of Gitaxias');
K('incubate', 'Eyes of Gitaxias');

// ---------------------------------------------------------------- face down, library, graveyard
K('cloak', 'Cryptic Coat', { lib: 'Plains;Craw Wurm;Island;Forest' });
K('manifest', 'Write into Being', { lib: 'Plains;Craw Wurm;Island;Forest' });
K('manifest dread', 'Unnatural Summons', { lib: 'Plains;Craw Wurm;Island;Forest' });
K('collect evidence', 'Surveillance Monitor', { gy: 'Craw Wurm;Island' });
K('suspect', 'Repeat Offender', { zone: 'battlefield', bf: 'Repeat Offender' });
K('forage', 'Treetop Sentries', { gy: 'Island;Forest;Plains' });
K('behold', 'Caustic Exhale', { bf: 'Shivan Dragon', roles: R3, steps: async () => [{ ui: null }, { picks: [await ELDER()] }] });
K('learn', 'Professor of Symbology', { hand: 'Professor of Symbology;Forest' });
K('detain', 'Lyev Skyknight', { roles: R3, steps: tgt(ELDER) });
K('clash', 'Lash Out', { roles: R3, steps: tgt(ELDER) });
K('surveil', 'Consider', { lib: 'Island;Forest;Craw Wurm' });
K('explore', 'Merfolk Branchwalker', { lib: 'Plains;Craw Wurm;Island;Forest' });
K('connive', "Raffine's Informant", { hand: "Raffine's Informant;Forest" });
K('amass', 'Lazotep Reaver');
K('populate', "Coursers' Accord");
K('discover', 'Geological Appraiser', { lib: 'Plains;Forest;Island;Jaspera Sentinel;Plains;Swamp' });
K('imprint', 'Chrome Mox', { hand: 'Chrome Mox;Lightning Bolt' });
K('channel', 'Otawara, Soaring City', { roles: R3, steps: async () => [{ ui: pickRow(/Canaliz|Channel/) }, { picks: [await ELDER()] }] });
K('grandeur', 'Tarox Bladewing', { zone: 'battlefield', bf: 'Tarox Bladewing', hand: 'Tarox Bladewing', steps: [{ ui: pickRow(/Grandeza|Grandeur|Descart/) }] });

// ---------------------------------------------------------------- ability words
K('landfall', 'Forest', { bf: 'Tireless Tracker' });
K('morbid', 'Village Rites', { bf: 'Jaspera Sentinel', hand: 'Village Rites;Festerhide Boar', after: cast2('hand', 'Festerhide Boar') });
K('revolt', 'Village Rites', { bf: 'Jaspera Sentinel', hand: 'Village Rites;Fatal Push', roles: R3, after: cast2('hand', 'Fatal Push', tgt(() => theirs('Cavalier of Thorns'))), defBf: 'Faeburrow Elder;Cavalier of Thorns' });
K('void', 'Village Rites', { bf: 'Jaspera Sentinel', hand: 'Village Rites;Plasma Bolt', roles: R3, after: cast2('hand', 'Plasma Bolt', tgt('P')) });
K('metalcraft', 'Galvanic Blast', { bf: 'Arcane Signet;Thought Vessel;Azorius Signet', roles: R3, steps: tgt('P') });
K('threshold', 'Werebear', { gy: 'Island;Forest;Plains;Swamp;Mountain;Island;Forest' });
K('delirium', 'Moldgraf Scavenger', { gy: 'Forest;Lightning Bolt;Jaspera Sentinel;Arcane Signet' });
K('constellation', 'Pacifism', { bf: 'Eidolon of Blossoms', roles: R3, steps: tgt(ELDER) });
K('eerie', 'Pacifism', { bf: 'Entity Tracker', roles: R3, steps: tgt(ELDER) });
K('heroic', 'Gods Willing', { bf: 'Favored Hoplite', steps: tgt(() => mine('Favored Hoplite')) });
K('ferocious', 'Savage Punch', { bf: 'Craw Wurm', roles: R3, steps: tgt(() => mine('Craw Wurm'), ELDER) });
K('formidable', 'Atarka Beastbreaker', { zone: 'battlefield', bf: 'Atarka Beastbreaker;Craw Wurm' });
K('spell mastery', 'Fiery Impulse', { gy: 'Lightning Bolt;Murder', roles: R3, steps: tgt(() => theirs('Cavalier of Thorns')), defBf: 'Faeburrow Elder;Cavalier of Thorns' });
K('converge', 'Crystalline Crawler', { lands: 'Plains;Island;Swamp;Mountain;Forest' });
K('domain', 'Tribal Flames', { roles: R3, steps: tgt(() => theirs('Cavalier of Thorns')), defBf: 'Faeburrow Elder;Cavalier of Thorns' });
K('hellbent', 'Cackling Flames', { roles: R3, steps: tgt('P') });
K('magecraft', 'Opt', { bf: 'Archmage Emeritus', lib: 'Island;Forest;Plains' });
K('alliance', 'Jaspera Sentinel', { bf: 'Prosperous Innkeeper' });
K('rally', 'Ondu Cleric', { bf: 'Chasm Guide' });
K('paradox', 'Think Twice', { zone: 'graveyard', gy: 'Think Twice', hand: '', bf: 'The Thirteenth Doctor' });
K('celebration', 'Grand Ball Guest', { hand: 'Jaspera Sentinel;Grand Ball Guest', pre: pre1('Jaspera Sentinel') });
K('descend', 'Basking Capybara', { gy: 'Forest;Arcane Signet;Jaspera Sentinel;Island' });
K('undergrowth', 'Golgari Raiders', { gy: 'Jaspera Sentinel;Tower Gargoyle;Craw Wurm' });
K('enrage', 'Shock', { bf: 'Ripjaw Raptor', steps: tgt(() => mine('Ripjaw Raptor')) });
K('addendum', "Sphinx's Insight");
K('adamant', 'Searing Barrage', { roles: R3, steps: tgt(() => theirs('Cavalier of Thorns')), defBf: 'Faeburrow Elder;Cavalier of Thorns', pre: async () => { const me = (await all()).find((p) => p.name === 'Luis'); globalThis.PAYCLICK = me.battlefield.filter((c) => c.name === 'Mountain').map((c) => c.id); } });
K('coven', 'Might of the Old Ways', { bf: 'Jaspera Sentinel;Tower Gargoyle;Craw Wurm', steps: tgt(() => mine('Jaspera Sentinel')) });
K('flurry', 'Opt', { bf: 'Devoted Duelist', hand: 'Opt;Shock', lib: 'Island;Forest;Plains', roles: R3, after: cast2('hand', 'Shock', tgt('P')) });
K('cohort', 'Ondu War Cleric', { zone: 'battlefield', bf: 'Ondu War Cleric;Ondu Cleric' });
K('fateful hour', 'Gavony Ironwright', { bf: 'Jaspera Sentinel', extra: { p0life: '4' } });
K('radiance', 'Wojek Siren', { bf: 'Jaspera Sentinel;Llanowar Elves', steps: tgt(() => mine('Jaspera Sentinel')) });
K('chroma', 'Outrage Shaman', { roles: R3, bf: 'Ember Swallower', steps: tgt(CAV), defBf: DEF2 });
K('sweep', 'Plow Through Reito', { bf: 'Jaspera Sentinel', steps: tgt(() => mine('Jaspera Sentinel')) });
K('corrupted', 'Anoint with Affliction', { roles: R3, defBf: 'Faeburrow Elder;Cavalier of Thorns', extra: { p1counters: 'POISON=3' }, steps: tgt(() => theirs('Cavalier of Thorns')) });
K('parley', 'Selvala, Explorer Returned', { zone: 'battlefield', bf: 'Selvala, Explorer Returned', roles: R3 });
K('lieutenant', 'Thunderfoot Baloth', { bf: 'Jaspera Sentinel|IsCommander' });
K('eminence', 'Voldaren Epicure', { bf: 'Edgar Markov|IsCommander', roles: R3 });
K('imprint', 'Chrome Mox', { hand: 'Chrome Mox;Lightning Bolt' });
U('inspired', 'Oreskos Sun Guide|Tapped');
U('kinship', 'Wandering Graybeard', { lib: 'Hill Giant;Island;Forest' });
// combat
C('bands with other', 'Mountain Stronghold;Squee, Goblin Nabob;Krenko, Tin Street Kingpin', ['Squee, Goblin Nabob', 'Krenko, Tin Street Kingpin'], 'Faeburrow Elder', [['Faeburrow Elder', 'Squee, Goblin Nabob']]);
C('exert', 'Ahn-Crop Crasher', ['Ahn-Crop Crasher'], 'Faeburrow Elder', [], { pre: async () => { globalThis.LSTEPS = [{ reply: { indices: [0] } }, { picks: [await ELDER()] }]; globalThis.LWHO = 'Luis'; }, post: async () => { globalThis.LSTEPS = null; } });
C('battalion', 'Boros Elite;Jaspera Sentinel;Tower Gargoyle', ['Boros Elite', 'Jaspera Sentinel', 'Tower Gargoyle'], '', []);
C('pack tactics', 'Gnoll Hunter;Craw Wurm', ['Gnoll Hunter', 'Craw Wurm'], '', []);
C('raid', 'Jaspera Sentinel', ['Jaspera Sentinel'], '', [], { attHand: 'Mardu Heart-Piercer', pre: async () => { globalThis.LSTEPS = [{ picks: [{ player: 1 }] }]; globalThis.LWHO = 'Luis'; }, post: async () => { await passPhase(atMain('MAIN2')); await cast2('hand', 'Mardu Heart-Piercer', tgt('P'))(); } });
// survival counts the main phases of a real turn: start in Elena's turn and play Luis's next turn
KW.survival = async () => {
  await scene({ active: 'p5', p0battlefield: L3 + ';Cautious Survivor', p1battlefield: 'Forest;Forest;Island' }, 'Elena', 'MAIN2');
  await passPhase(atMain('MAIN1'));
  begin(['survival'], R3);
  await wait(1200); await send('Luis', { type: 'ok' }); await wait(1200);
  for (let i = 0; i < 30; i++) { const s = await st('Luis'); if (/Attack/.test(s.input || '')) break; await answerOthers([]); await wait(500); }
  const c = (await all()).find((p) => p.name === 'Luis').battlefield.find((x) => x.name === 'Cautious Survivor' || x.oracleName === 'Cautious Survivor');
  await send('Luis', { type: 'attackAssign', cards: [c.id], player: PID.Pablo }); await wait(1200);
  await send('Luis', { type: 'ok' }); await wait(1200);
  await passPhase(atMain('MAIN2')); await passAll(); await end(4000);
};
C('bloodrush', 'Jaspera Sentinel', ['Jaspera Sentinel'], 'Faeburrow Elder', [['Faeburrow Elder', 'Jaspera Sentinel']], { attHand: 'Slaughterhorn', afterBlocks: cast2('hand', 'Slaughterhorn', async () => [{ ui: pickRow(/Bloodrush|sangr|mpetu/) }, { picks: [await mine('Jaspera Sentinel')] }]) });


// ---------------------------------------------------------------- Luis 15:34: rows once marked "imposible" that a real game does have
// Partner: one commander already on the table, the other is cast from the command zone
K('partner', 'Thrasios, Triton Hero', { zone: 'command', hand: '', bf: 'Tymna the Weaver|IsCommander', extra: { p0command: 'Thrasios, Triton Hero|IsCommander' } });
// Partner with: Pir enters and its trigger lets a player put Toothy from the library into the hand
K('partner with', 'Pir, Imaginative Rascal', { lib: 'Island;Forest;Plains;Toothy, Imaginary Friend', later: [{ picks: [{ player: 0 }] }, { ui: searchPick(/Toothy|Dientes/) }, { ui: searchPick(/Toothy|Dientes/) }], tail: 5000 });
// Choose a Background: the commander is on the table, the Background is cast from the command zone
K('choose a background', 'Raised by Giants', { zone: 'command', hand: '', bf: 'Wilson, Refined Grizzly|IsCommander', extra: { p0command: 'Raised by Giants|IsCommander' } });
// Doctor's companion: the Doctor is on the table, the companion is cast from the command zone
K("doctor's companion", 'Yasmin Khan', { zone: 'command', hand: '', bf: 'The Thirteenth Doctor|IsCommander', extra: { p0command: 'Yasmin Khan|IsCommander' } });
// Reflect: each opponent may pay {0}; whoever does gets a token copy
K('reflect', 'Mirrored Lotus', { roles: R3, defBf: '', tail: 6000 });
// Miracle: Luis's first draw of the turn is Thunderous Wrath, revealed and cast for {R} at Pablo (KWO: the app's KW.miracle loads later)
globalThis.KWO = globalThis.KWO || {}; KWO.miracle = async () => { // stops as soon as the 5 damage lands, not a whole round later
  await scene({ active: 'p5', p0battlefield: L3 + ';Jaspera Sentinel', p1battlefield: 'Forest;Forest;Forest;Island;Island;Mountain;Mountain;Plains;Swamp', p0library: 'Thunderous Wrath;Island;Forest' }, 'Elena', 'MAIN2');
  await passPhase(async (s) => s.state.activePlayerId === 0);
  begin(['miracle'], R3);
  LSTEPS = [{ reply: { index: 0 } }, { picks: [{ player: 1 }] }, { picks: [{ player: 1 }] }]; LWHO = 'Luis';
  await passPhase(async (s) => { const p = s.state.players.find((q) => q.name === 'Pablo'); return (p && p.life <= 35) || !/UPKEEP|DRAW/.test(s.state.phaseKey); });
  LSTEPS = null; await end(5000);
};
// ---------------------------------------------------------------- the app's keyword scenes, without the extra spells
globalThis.KWO = globalThis.KWO || {};
const O = (id, card, o = {}) => { KWO[id] = () => kwCast({ id, card, ...o }); };
O('haste', 'Raging Goblin', { roles: R3, after: toAttack(['Raging Goblin']) });
O('flashback', 'Think Twice', { zone: 'graveyard', gy: 'Think Twice', hand: '', lib: 'Plains;Island;Forest' });
O('crew', 'Peacewalker Colossus', { zone: 'battlefield', bf: 'Peacewalker Colossus;Tower Gargoyle', hand: '' });

// ---------------------------------------------------------------- which rows can be simulated, and why the others can't
globalThis.EXTRA_NO = {
  'can be your commander': 'Solo cuenta al armar el mazo: decide qué carta puede ser tu comandante.',
  'double agenda': 'Las conspiraciones solo existen en los drafts de Conspiracy, no en Commander', 'hidden agenda': 'Las conspiraciones solo existen en los drafts de Conspiracy, no en Commander',
};
// Companion: Keruga waits in the command zone (devState companion); Luis pays {3} to put it into his hand, then casts it
KW.companion = async () => { globalThis.DEVX = { companion: 'Keruga, the Macrosage' }; await kwCast({ id: 'companion', card: 'Keruga, the Macrosage', zone: 'command', hand: '', extra: { p0command: 'Kinnan, Bonder Prodigy|IsCommander' }, after: cast2('hand', 'Keruga, the Macrosage'), tail: 5000 }); };
// Infinite combo (Luis 16:51): Sanguine Bond + Exquisite Blood. Bolt one opponent and the loop drains everybody;
// it also checks that the game doesn't freeze while dozens of triggers resolve one after another
SC['a_fin-infinito'] = () => kwCast({ id: 'fin-infinito', card: 'Lightning Bolt', roles: R3, bf: L3 + ';Sanguine Bond;Exquisite Blood', defBf: '', extra: { p1life: 3, p2life: 3, p3life: 3, p4life: 3, p5life: 3 }, steps: tgt('P'), tail: 9000 }); // low life: the whole loop fits in one clip
// One scene per cause of defeat (Mejoras' derrota-* scenes, Luis 17:20: every Simular must record well)
SC['a_fin-ataque'] = () => kwCombat({ id: 'fin-ataque', roles: R3, attBf: 'Craw Wurm', attackers: ['Craw Wurm'], defBf: '', blocks: [], extra: { p1life: 4 }, post: async () => { await wait(4000); } });
SC['a_fin-perder-vida'] = () => kwCast({ id: 'fin-perder-vida', card: 'Sign in Blood', roles: R3, defBf: '', extra: { p1life: 2 }, steps: tgt('P'), tail: 8000 });
SC['a_fin-efecto-pierde'] = () => kwCast({ id: 'fin-efecto-pierde', card: 'Door to Nothingness', zone: 'battlefield', hand: '', bf: 'Door to Nothingness', roles: R3, defBf: '', steps: tgt('P'), tail: 8000 });
// Luis 19/10 07:48: the ways a card wins or loses (fincartas.js groups), played in the real game so their animations can be filmed
SC['a_fin-gana-biblioteca'] = () => kwCast({ id: 'fin-gana-biblioteca', card: "Thassa's Oracle", roles: R3, defBf: '', lib: 'Island', tail: 8000 });
SC['a_fin-propia'] = () => kwCast({ id: 'fin-propia', card: 'Door to Nothingness', zone: 'battlefield', hand: '', bf: 'Door to Nothingness', roles: { causa: 'Luis', tu: 'Luis', otro: 'Malaguita' }, defBf: '', steps: [{ picks: [{ player: 0 }] }], tail: 8000 });
SC['a_fin-no-pierdes'] = () => kwCast({ id: 'fin-no-pierdes', card: 'Lightning Bolt', roles: R3, defBf: 'Platinum Angel', extra: { p1life: 3 }, steps: tgt('P'), tail: 8000 });
SC['a_fin-cero-vidas'] = () => kwCast({ id: 'fin-cero-vidas', card: 'Lightning Bolt', roles: R3, defBf: 'Phyrexian Unlife', extra: { p1life: 2 }, steps: tgt('P'), tail: 8000 });
SC['a_fin-en-vez'] = () => kwCast({ id: 'fin-en-vez', card: 'Lightning Bolt', roles: R3, defBf: "Lich's Mirror", extra: { p1life: 3 }, steps: tgt('P'), tail: 9000 });
SC['a_fin-cuando-pierde'] = () => kwCast({ id: 'fin-cuando-pierde', card: 'Lightning Bolt', roles: R3, bf: 'Blood Tyrant', defBf: '', extra: { p1life: 3 }, steps: tgt('P'), tail: 9000 });
// Luis 21/10 (v2): the player the row names concedes. The row's roles say causa = Calero; the old scene made Pablo
// concede, so the "causa" video (Calero's tablet) had no frames. Calero concedes from his own tablet (flag + confirm).
const concedeOn = async (n) => {
  try { await P[n].click('.gb-quit', { timeout: 4000 }); await wait(1300); await P[n].evaluate(() => { const b = document.querySelector('.bt.red'); if (b) b.click(); }); } catch (e) { /* no button: the message below */ }
  await wait(1200);
  const x = await st(n).catch(() => null), me = x && x.state.players.find((q) => q.name === n);
  if (!me || !me.hasLost) await send(n, { type: 'concede' });
};
SC['a_rendirse'] = async () => {
  await scene({ active: 'p0', p0battlefield: L3, p1battlefield: 'Forest;Forest;Island', p3battlefield: 'Forest;Island;Mountain;Jungle Shrine;Horde of Notions', p0hand: '', p1hand: '', p3hand: '' }, 'Luis', 'MAIN1');
  begin(['rendirse'], { causa: 'Calero', tu: 'Luis', otro: 'Malaguita' }); await wait(1500);
  await concedeOn('Calero'); await end(8000);
};
// Luis 21/10 (v2): "Ganar la partida" made the other players concede (3 white flags). Now Luis really wins: every
// opponent has 4 life and Luis casts Flame Rift (4 damage to each player): they all fall at once and Luis, at 36, is
// the last one standing. Named a_fin-* so the catalog server (Auto.endsGame) knows the game ends and boots a new one;
// a_ganar is the same scene for a catalog that maps the row to it.
SC['a_fin-ganar'] = () => kwCast({ id: 'ganar', card: 'Flame Rift', roles: R3, defBf: '', extra: { p1life: 4, p2life: 4, p3life: 4, p4life: 4, p5life: 4 }, tail: 9000 });
SC.a_ganar = SC['a_fin-ganar'];
// Luis 21/10 (v2): veneno, comandante and danio-combate pointed at SC.combat, whose "danio-combate,comandante,veneno"
// begin only runs if, after Luis blocks, a player still has priority before the damage step. On the PC the players
// with nothing to do pass by themselves (FAST's act), the damage happens at once, the loop sees DAMAGE/MAIN2 and ends
// without that begin: no begin with the row's tag, so nothing was filmed ("La escena no grabó nada"); a failed run
// also left globalThis.__dmg = 1, which skips that begin for good. Each row gets its own combat, filmed from the start.
SC['a_danio-combate'] = () => kwCombat({ id: 'danio-combate', roles: R3, attBf: 'Craw Wurm;Grizzly Bears', attackers: ['Craw Wurm', 'Grizzly Bears'], defBf: 'Cavalier of Thorns', blocks: [['Cavalier of Thorns', 'Craw Wurm']], post: async () => { await wait(3000); } });
SC.a_comandante = () => kwCombat({ id: 'comandante', roles: R3, attBf: 'Ghalta, Primal Hunger|IsCommander', attackers: ['Ghalta, Primal Hunger'], defBf: '', blocks: [], post: async () => { await wait(3000); } });
SC.a_veneno = () => kwCombat({ id: 'veneno', roles: R3, attBf: 'Blighted Agent', attackers: ['Blighted Agent'], defBf: '', blocks: [], post: async () => { await wait(3000); } });
return Object.keys(KW).length + ' keywords';
