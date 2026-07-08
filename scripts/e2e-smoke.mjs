// End-to-end smoke test against a running server (default http://localhost:8080,
// override with PORT). Requires Node 22+ (native WebSocket). Run from repo root:
//
//   PORT=8085 node scripts/e2e-smoke.mjs
//
// Covers the lobby + Phase 1 + Phase 2 flow: join validation, host controls,
// prompt submission, live-tally voting, tie-break resolution, disconnects,
// and timer-bound drawing submission.
//
// The timer-expiry scenario needs a short drawing timer; start the server with
// GAME_DRAWING_SECONDS=4 to enable it (it is skipped otherwise).
import { Client } from '../frontend/node_modules/@stomp/stompjs/esm6/index.js';

const PORT = process.env.PORT ?? '8080';
const BASE = `http://localhost:${PORT}`;

const results = [];
function check(label, ok) {
  results.push([label, ok]);
  console.log(ok ? 'PASS' : 'FAIL', '-', label);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function connectPlayer(roomCode, name) {
  return new Promise((resolve, reject) => {
    const client = new Client({ brokerURL: `ws://localhost:${PORT}/ws`, reconnectDelay: 0 });
    const player = { name, client, states: [], personal: [] };
    client.onConnect = () => {
      client.subscribe(`/topic/room/${roomCode}`, (m) => {
        const e = JSON.parse(m.body);
        if (e.type === 'STATE') player.states.push(e.state);
      });
      client.subscribe('/user/queue/personal', (m) => {
        player.personal.push(JSON.parse(m.body));
      });
      resolve(player);
    };
    client.onStompError = reject;
    client.activate();
  });
}

async function join(roomCode, name) {
  const p = await connectPlayer(roomCode, name);
  p.client.publish({ destination: `/app/room/${roomCode}/join`, body: JSON.stringify({ name }) });
  await sleep(250);
  return p;
}

function send(player, roomCode, action, body) {
  player.client.publish({
    destination: `/app/room/${roomCode}/${action}`,
    body: body === undefined ? '' : JSON.stringify(body),
  });
}

function hasError(player, code) {
  return player.personal.some((e) => e.type === 'ERROR' && e.code === code);
}

async function createRoom() {
  const res = await fetch(`${BASE}/api/rooms`, { method: 'POST' });
  const { roomCode } = await res.json();
  return roomCode;
}

const TINY_PNG = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';

// --- Scenario 1: lobby rules + full Phase 1 flow with a clear majority ---
{
  const roomCode = await createRoom();
  console.log('\nscenario 1 — lobby + majority vote (room', roomCode + ')');

  const alice = await join(roomCode, 'Alice');
  const bob = await join(roomCode, 'Bob');

  const dup = await join(roomCode, 'bob');
  check('duplicate name rejected (NAME_TAKEN)', hasError(dup, 'NAME_TAKEN'));

  send(bob, roomCode, 'start');
  await sleep(250);
  check('non-host start rejected (NOT_HOST)', hasError(bob, 'NOT_HOST'));

  send(alice, roomCode, 'start');
  await sleep(250);
  check('start below minimum rejected (NOT_ENOUGH_PLAYERS)', hasError(alice, 'NOT_ENOUGH_PLAYERS'));

  const carol = await join(roomCode, 'Carol');
  send(alice, roomCode, 'start');
  await sleep(250);
  check('host start moves everyone to PROMPT_SUBMISSION',
    [alice, bob, carol].every((p) => p.states.at(-1)?.phase === 'PROMPT_SUBMISSION'));

  // Prompts
  send(alice, roomCode, 'prompt', { text: 'a haunted vending machine' });
  send(alice, roomCode, 'prompt', { text: 'second thoughts' });
  await sleep(250);
  check('second submission rejected (ALREADY_SUBMITTED)', hasError(alice, 'ALREADY_SUBMITTED'));
  check('done flag set for submitted player',
    bob.states.at(-1)?.players.find((p) => p.name === 'Alice')?.done === true);

  send(bob, roomCode, 'prompt', { text: 'octopus barista' });
  await sleep(250);
  check('still in PROMPT_SUBMISSION until everyone submits',
    alice.states.at(-1)?.phase === 'PROMPT_SUBMISSION');

  send(carol, roomCode, 'prompt', { text: 'the moon but angry' });
  await sleep(250);
  const ballotState = alice.states.at(-1);
  check('voting opens once all prompts are in', ballotState?.phase === 'PROMPT_VOTING');
  check('ballot contains all 3 prompts', ballotState?.prompts?.length === 3);

  // Live tally: two players vote the same prompt
  const target = ballotState.prompts[0];
  send(alice, roomCode, 'vote', { promptId: target.id });
  await sleep(250);
  const midVote = bob.states.at(-1);
  check('tally updates live after first vote',
    midVote?.phase === 'PROMPT_VOTING' &&
    midVote?.prompts?.find((p) => p.id === target.id)?.votes === 1);

  send(bob, roomCode, 'vote', { promptId: target.id });
  send(carol, roomCode, 'vote', { promptId: ballotState.prompts[1].id });
  await sleep(300);
  const final = carol.states.at(-1);
  check('vote closes into DRAWING with the majority prompt',
    final?.phase === 'DRAWING' && final?.winningPrompt === target.text);
  check('ballot tallies are absent outside voting phase', final?.prompts === undefined);
  check('drawing phase broadcasts a countdown',
    typeof final?.phaseRemainingMillis === 'number' && final.phaseRemainingMillis > 0);

  // Drawings
  send(alice, roomCode, 'drawing', { imageData: 'data:image/jpeg;base64,nope' });
  await sleep(250);
  check('non-PNG drawing rejected (INVALID_DRAWING)', hasError(alice, 'INVALID_DRAWING'));

  send(alice, roomCode, 'drawing', { imageData: TINY_PNG });
  send(bob, roomCode, 'drawing', { imageData: TINY_PNG });
  await sleep(250);
  check('still DRAWING until every drawing is in',
    alice.states.at(-1)?.phase === 'DRAWING' &&
    alice.states.at(-1)?.players.filter((p) => p.done).length === 2);

  send(carol, roomCode, 'drawing', { imageData: TINY_PNG });
  await sleep(300);
  check('all drawings in closes the phase into BRACKET_VOTING',
    [alice, bob, carol].every((p) => p.states.at(-1)?.phase === 'BRACKET_VOTING'));

  for (const p of [alice, bob, carol, dup]) p.client.deactivate();
}

// --- Scenario 2: tie-break + mid-phase disconnect ---
{
  const roomCode = await createRoom();
  console.log('\nscenario 2 — tie-break + disconnect (room', roomCode + ')');

  const alice = await join(roomCode, 'Alice');
  const bob = await join(roomCode, 'Bob');
  const carol = await join(roomCode, 'Carol');
  const dave = await join(roomCode, 'Dave');
  send(alice, roomCode, 'start');
  await sleep(250);

  send(alice, roomCode, 'prompt', { text: 'prompt A' });
  send(bob, roomCode, 'prompt', { text: 'prompt B' });
  send(carol, roomCode, 'prompt', { text: 'prompt C' });
  await sleep(250);

  // Dave bails without submitting → phase should advance for the rest
  dave.client.deactivate();
  await sleep(400);
  const ballotState = alice.states.at(-1);
  check('voting opens when the last holdout disconnects',
    ballotState?.phase === 'PROMPT_VOTING' && ballotState?.prompts?.length === 3);

  // Three-way tie → random winner among the three
  const [pa, pb, pc] = ballotState.prompts;
  send(alice, roomCode, 'vote', { promptId: pa.id });
  send(bob, roomCode, 'vote', { promptId: pb.id });
  send(carol, roomCode, 'vote', { promptId: pc.id });
  await sleep(300);
  const final = alice.states.at(-1);
  check('tie resolves randomly among tied prompts',
    final?.phase === 'DRAWING' &&
    ['prompt A', 'prompt B', 'prompt C'].includes(final?.winningPrompt));

  for (const p of [alice, bob, carol]) p.client.deactivate();
}

// --- Scenario 3: drawing deadline force-closes the phase (needs GAME_DRAWING_SECONDS=4) ---
if (process.env.GAME_DRAWING_SECONDS === '4') {
  const roomCode = await createRoom();
  console.log('\nscenario 3 — drawing timer expiry (room', roomCode + ')');

  const alice = await join(roomCode, 'Alice');
  const bob = await join(roomCode, 'Bob');
  const carol = await join(roomCode, 'Carol');
  send(alice, roomCode, 'start');
  await sleep(250);
  send(alice, roomCode, 'prompt', { text: 'prompt A' });
  send(bob, roomCode, 'prompt', { text: 'prompt B' });
  send(carol, roomCode, 'prompt', { text: 'prompt C' });
  await sleep(250);
  const ballot = alice.states.at(-1).prompts;
  for (const p of [alice, bob, carol]) send(p, roomCode, 'vote', { promptId: ballot[0].id });
  await sleep(300);
  check('reached DRAWING', alice.states.at(-1)?.phase === 'DRAWING');

  // Only two of three submit; Carol never does
  send(alice, roomCode, 'drawing', { imageData: TINY_PNG });
  send(bob, roomCode, 'drawing', { imageData: TINY_PNG });
  await sleep(500);
  check('phase stays open waiting for Carol', alice.states.at(-1)?.phase === 'DRAWING');

  // 4s timer + 3s server grace → closed by ~8s
  await sleep(8000);
  check('server force-closes the drawing phase at the deadline',
    [alice, bob, carol].every((p) => p.states.at(-1)?.phase === 'BRACKET_VOTING'));

  for (const p of [alice, bob, carol]) p.client.deactivate();
} else {
  console.log('\nscenario 3 skipped (set GAME_DRAWING_SECONDS=4 on the server to enable)');
}

const failed = results.filter(([, ok]) => !ok);
console.log(failed.length === 0 ? '\nALL PASS' : `\n${failed.length} FAILURE(S)`);
process.exit(failed.length === 0 ? 0 : 1);
