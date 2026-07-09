import { createDrawingTool, type DrawingTool } from './canvas';
import { connect, type Connection } from './net';
import type { GameEvent, GamePhase, RoomStateView } from './types';

// --- App state ---

let conn: Connection | null = null;
let myName = '';
let roomCode = '';
let lastState: RoomStateView | null = null;
let myVote: string | null = null; // promptId I voted for (highlighting only; server is authoritative)

// Drawing phase
let drawTool: DrawingTool | null = null;
let drawingSubmitted = false;
let drawDeadline: number | null = null; // local-clock timestamp
let drawTimer: number | undefined;

// Bracket phase
let myMatchVote: { matchId: string; drawingId: string } | null = null;

// --- DOM helpers ---

function el<T extends HTMLElement>(id: string): T {
  const node = document.getElementById(id);
  if (!node) throw new Error(`Missing element #${id}`);
  return node as T;
}

const screens = ['home', 'lobby', 'submit', 'vote', 'draw', 'bracket', 'results'] as const;
type Screen = (typeof screens)[number];

function showScreen(name: Screen): void {
  for (const s of screens) {
    el(`screen-${s}`).classList.toggle('hidden', s !== name);
  }
  // Canvas and matchup screens need elbow room; everything else stays compact
  const wide = name === 'draw' || name === 'bracket';
  document.querySelector('.card')?.classList.toggle('wide', wide);
}

function setError(message: string): void {
  const box = el('home-error');
  box.textContent = message;
  box.classList.toggle('hidden', message === '');
}

const ERROR_MESSAGES: Record<string, string> = {
  ROOM_NOT_FOUND: 'No room with that code.',
  GAME_IN_PROGRESS: 'That game has already started.',
  INVALID_NAME: 'Please enter a name (up to 32 characters).',
  NAME_TAKEN: 'That name is already taken in this room.',
  NOT_ENOUGH_PLAYERS: 'Not enough players to start yet.',
  INVALID_PROMPT: 'Prompts must be 1–140 characters.',
  ALREADY_SUBMITTED: 'You already submitted a prompt.',
  ALREADY_VOTED: 'You already voted — votes are final!',
  INVALID_VOTE: 'That prompt is not on the ballot.',
  INVALID_DRAWING: 'That drawing could not be submitted.',
  SELF_VOTE: "You can't vote for your own drawing!",
  MATCH_CLOSED: 'This matchup has already closed.',
};

// --- Rendering ---

function me(state: RoomStateView) {
  return state.players.find((p) => p.name === myName);
}

function renderPlayerList(listId: string, state: RoomStateView, showDone: boolean): void {
  const list = el(listId);
  list.innerHTML = '';
  for (const player of state.players) {
    const li = document.createElement('li');
    li.textContent = player.name;
    if (player.host) {
      const badge = document.createElement('span');
      badge.className = 'badge';
      badge.textContent = 'host';
      li.appendChild(badge);
    }
    if (showDone && player.done) {
      const check = document.createElement('span');
      check.className = 'check';
      check.textContent = '✓';
      li.appendChild(check);
    }
    if (player.name === myName) li.classList.add('me');
    list.appendChild(li);
  }
}

function renderLobby(state: RoomStateView): void {
  el('lobby-code').textContent = state.roomCode;
  el('lobby-count').textContent =
    `${state.players.length} player${state.players.length === 1 ? '' : 's'}`;
  renderPlayerList('lobby-players', state, false);

  const iAmHost = me(state)?.host ?? false;
  const startBtn = el<HTMLButtonElement>('btn-start');
  const waitMsg = el('lobby-wait');
  startBtn.classList.toggle('hidden', !iAmHost);
  waitMsg.classList.toggle('hidden', iAmHost);
  if (iAmHost) {
    const enough = state.players.length >= state.minPlayersToStart;
    startBtn.disabled = !enough;
    startBtn.textContent = enough
      ? 'Start game'
      : `Need ${state.minPlayersToStart - state.players.length} more player(s)`;
  }
}

function renderSubmit(state: RoomStateView): void {
  const submitted = me(state)?.done ?? false;
  el('submit-form').classList.toggle('hidden', submitted);
  el('submit-waiting').classList.toggle('hidden', !submitted);
  const doneCount = state.players.filter((p) => p.done).length;
  el('submit-progress').textContent = `${doneCount} of ${state.players.length} prompts in`;
  renderPlayerList('submit-players', state, true);
}

function renderVote(state: RoomStateView): void {
  const voted = me(state)?.done ?? false;
  el('vote-hint').textContent = voted
    ? 'Vote cast! Watching the tallies roll in…'
    : 'Vote for your favorite — everyone will draw the winner. Voting for your own is fair game.';

  const list = el('vote-prompts');
  list.innerHTML = '';
  const totalVotes = (state.prompts ?? []).reduce((sum, p) => sum + p.votes, 0);

  for (const prompt of state.prompts ?? []) {
    const card = document.createElement('button');
    card.className = 'prompt-card';
    card.disabled = voted;
    if (prompt.id === myVote) card.classList.add('my-vote');

    const text = document.createElement('span');
    text.className = 'prompt-text';
    text.textContent = prompt.text;

    const tally = document.createElement('span');
    tally.className = 'tally';
    tally.textContent = String(prompt.votes);

    const bar = document.createElement('span');
    bar.className = 'tally-bar';
    bar.style.width = totalVotes === 0 ? '0%' : `${(prompt.votes / totalVotes) * 100}%`;

    card.append(bar, text, tally);
    card.addEventListener('click', () => {
      myVote = prompt.id;
      conn?.send(`/app/room/${roomCode}/vote`, { promptId: prompt.id });
    });
    list.appendChild(card);
  }

  const doneCount = state.players.filter((p) => p.done).length;
  el('vote-progress').textContent = `${doneCount} of ${state.players.length} votes in`;
}

// --- Drawing phase ---

function enterDrawingPhase(state: RoomStateView): void {
  drawingSubmitted = false;
  el('draw-prompt').textContent = state.winningPrompt ?? '';
  drawTool?.destroy();
  drawTool = createDrawingTool(el<HTMLCanvasElement>('draw-canvas'));
  // Reset toolbar to defaults
  selectSwatch(el('draw-colors').querySelector<HTMLElement>('[data-color="#18181b"]'));
  selectBrush(el('draw-sizes').querySelector<HTMLElement>('[data-size="5"]'));
}

function submitDrawing(): void {
  if (drawingSubmitted || !drawTool) return;
  drawingSubmitted = true;
  conn?.send(`/app/room/${roomCode}/drawing`, { imageData: drawTool.snapshot() });
  syncDrawScreen();
}

function stopDrawTimer(): void {
  clearInterval(drawTimer);
  drawTimer = undefined;
  drawDeadline = null;
}

function updateDrawTimer(): void {
  if (drawDeadline === null) return;
  const remaining = Math.max(0, drawDeadline - Date.now());
  const seconds = Math.ceil(remaining / 1000);
  el('draw-timer').textContent = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
  el('draw-timer').classList.toggle('urgent', seconds <= 10);
  if (remaining <= 0) {
    stopDrawTimer();
    // Auto-submit whatever is on the canvas, even if untouched
    submitDrawing();
  }
}

function syncDrawScreen(): void {
  el('draw-workspace').classList.toggle('hidden', drawingSubmitted);
  el('draw-waiting').classList.toggle('hidden', !drawingSubmitted);
}

function renderDraw(state: RoomStateView, phaseChanged: boolean): void {
  if (phaseChanged) enterDrawingPhase(state);

  // Refresh the local countdown from remaining-at-broadcast (skew-proof)
  if (state.phaseRemainingMillis !== undefined && !drawingSubmitted) {
    drawDeadline = Date.now() + state.phaseRemainingMillis;
    if (drawTimer === undefined) {
      drawTimer = window.setInterval(updateDrawTimer, 250);
      updateDrawTimer();
    }
  }

  // Trust the server if it already counted us as done (e.g. duplicate tab)
  if (me(state)?.done) drawingSubmitted = true;
  syncDrawScreen();

  const doneCount = state.players.filter((p) => p.done).length;
  el('draw-progress').textContent = `${doneCount} of ${state.players.length} drawings in`;
  renderPlayerList('draw-players', state, true);
}

// --- Bracket phase ---

function drawingCard(state: RoomStateView, drawing: { id: string; artist: string; imageData: string }): HTMLElement {
  const m = state.matchup;
  if (!m) throw new Error('no matchup');
  const mine = drawing.artist === myName;
  const votedThis = myMatchVote?.matchId === m.matchId && myMatchVote.drawingId === drawing.id;
  const iVoted = me(state)?.done ?? false;

  const card = document.createElement('button');
  card.className = 'matchup-card';
  if (mine) card.classList.add('mine');
  if (votedThis) card.classList.add('my-vote');
  card.disabled = m.revealed || iVoted || mine;

  const img = document.createElement('img');
  img.src = drawing.imageData;
  img.alt = `Drawing by ${drawing.artist}`;
  card.appendChild(img);

  const caption = document.createElement('span');
  caption.className = 'matchup-caption';
  caption.textContent = mine ? `${drawing.artist} (you)` : drawing.artist;
  card.appendChild(caption);

  if (m.revealed) {
    const votes = drawing.id === m.a.id ? m.votesA : m.votesB;
    const badge = document.createElement('span');
    badge.className = 'tally matchup-tally';
    badge.textContent = `${votes} vote${votes === 1 ? '' : 's'}`;
    card.appendChild(badge);
    if (m.winnerId === drawing.id) card.classList.add('winner');
    else card.classList.add('loser');
  }

  if (!m.revealed && !iVoted && !mine) {
    card.addEventListener('click', () => {
      myMatchVote = { matchId: m.matchId, drawingId: drawing.id };
      conn?.send(`/app/room/${roomCode}/matchvote`, { drawingId: drawing.id });
    });
  }
  return card;
}

function renderBracketSummary(listId: string, state: RoomStateView): void {
  const box = el(listId);
  box.innerHTML = '';
  for (const [i, round] of (state.bracket ?? []).entries()) {
    const title = document.createElement('div');
    title.className = 'round-title';
    title.textContent = `Round ${i + 1}`;
    box.appendChild(title);
    for (const match of round.matches) {
      const row = document.createElement('div');
      row.className = 'round-row';
      row.textContent = match.winnerArtist
        ? `${match.aArtist} vs ${match.bArtist} — ${match.winnerArtist} wins`
        : `${match.aArtist} vs ${match.bArtist}`;
      box.appendChild(row);
    }
    if (round.byeArtist) {
      const row = document.createElement('div');
      row.className = 'round-row bye';
      row.textContent = `${round.byeArtist} draws a bye and advances`;
      box.appendChild(row);
    }
  }
}

function renderBracket(state: RoomStateView): void {
  const m = state.matchup;
  if (!m) return;

  el('bracket-round').textContent = `Round ${m.round} — Match ${m.matchIndex} of ${m.matchCount}`;
  el('bracket-prompt').textContent = state.winningPrompt ?? '';

  const arena = el('bracket-arena');
  arena.innerHTML = '';
  arena.appendChild(drawingCard(state, m.a));
  const vs = document.createElement('div');
  vs.className = 'vs';
  vs.textContent = 'VS';
  arena.appendChild(vs);
  arena.appendChild(drawingCard(state, m.b));

  const status = el('bracket-status');
  if (m.revealed) {
    const winner = m.winnerId === m.a.id ? m.a : m.b;
    status.textContent = m.tieBroken
      ? `Dead heat! Coin flip says ${winner.artist} advances. Next match in a moment…`
      : `${winner.artist} advances! Next match in a moment…`;
  } else {
    const mine = m.a.artist === myName || m.b.artist === myName;
    const base = me(state)?.done
      ? 'Vote locked in. Tallies stay hidden until everyone has voted…'
      : mine
        ? 'Your drawing is up! Vote for your favorite — just not your own.'
        : 'Vote for your favorite!';
    status.textContent = `${base} (${m.votesIn} of ${state.players.length} votes in)`;
  }

  renderBracketSummary('bracket-summary', state);
}

function renderResults(state: RoomStateView): void {
  const hasChampion = !!state.champion;
  el('results-champion-wrap').classList.toggle('hidden', !hasChampion);
  el('results-none').classList.toggle('hidden', hasChampion);
  if (state.champion) {
    el<HTMLImageElement>('results-image').src = state.champion.imageData;
    el('results-artist').textContent = state.champion.artist;
    el('results-prompt').textContent = state.winningPrompt ?? '';
  }

  const iAmHost = me(state)?.host ?? false;
  el('btn-again').classList.toggle('hidden', !iAmHost);
  el('results-wait').classList.toggle('hidden', iAmHost);
}

function renderState(state: RoomStateView): void {
  const previousPhase: GamePhase | null = lastState?.phase ?? null;
  const phaseChanged = previousPhase !== state.phase;
  lastState = state;

  if (phaseChanged && state.phase === 'PROMPT_VOTING') {
    myVote = null; // fresh ballot
  }
  if (phaseChanged && previousPhase === 'DRAWING') {
    stopDrawTimer();
  }
  if (phaseChanged && state.phase === 'LOBBY') {
    // "Play again" reset: clear all per-game local state
    myVote = null;
    myMatchVote = null;
    drawingSubmitted = false;
  }

  switch (state.phase) {
    case 'LOBBY':
      renderLobby(state);
      showScreen('lobby');
      break;
    case 'PROMPT_SUBMISSION':
      renderSubmit(state);
      showScreen('submit');
      break;
    case 'PROMPT_VOTING':
      renderVote(state);
      showScreen('vote');
      break;
    case 'DRAWING':
      renderDraw(state, phaseChanged);
      showScreen('draw');
      break;
    case 'BRACKET_VOTING':
      renderBracket(state);
      showScreen('bracket');
      break;
    case 'RESULTS':
      renderResults(state);
      showScreen('results');
      break;
  }
}

// --- Event handling ---

function onRoomEvent(event: GameEvent): void {
  if (event.type === 'STATE' && event.state) {
    renderState(event.state);
  } else if (event.type === 'SYSTEM' && event.code === 'ROOM_EXPIRED') {
    leaveToHome('This room has expired.');
  }
}

function onPersonalEvent(event: GameEvent): void {
  if (event.type === 'ERROR') {
    const message = ERROR_MESSAGES[event.code ?? ''] ?? `Error: ${event.code}`;
    if (lastState === null) {
      // Join was rejected — bail back to the home screen
      conn?.disconnect();
      conn = null;
      setError(message);
      showScreen('home');
    } else {
      if (event.code === 'INVALID_VOTE' || event.code === 'ALREADY_VOTED') myVote = null;
      if (event.code === 'SELF_VOTE' || event.code === 'MATCH_CLOSED') myMatchVote = null;
      showToast(message);
    }
  } else if (event.type === 'JOIN_OK' && event.state) {
    renderState(event.state);
  }
}

let toastTimer: number | undefined;
function showToast(message: string): void {
  const toast = el('toast');
  toast.textContent = message;
  toast.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => toast.classList.add('hidden'), 3000);
}

function leaveToHome(message: string): void {
  conn?.disconnect();
  conn = null;
  lastState = null;
  myVote = null;
  stopDrawTimer();
  drawTool?.destroy();
  drawTool = null;
  drawingSubmitted = false;
  setError(message);
  showScreen('home');
}

// --- Drawing toolbar ---

const SWATCHES = ['#18181b', '#dc2626', '#f59e0b', '#facc15', '#16a34a',
                  '#2563eb', '#7c3aed', '#ec4899', '#92400e', '#ffffff'];
const BRUSH_SIZES = [2, 5, 12];

function selectSwatch(target: HTMLElement | null): void {
  if (!target) return;
  el('draw-colors').querySelectorAll('.swatch').forEach((s) => s.classList.remove('selected'));
  target.classList.add('selected');
  drawTool?.setColor(target.dataset.color ?? '#18181b');
}

function selectBrush(target: HTMLElement | null): void {
  if (!target) return;
  el('draw-sizes').querySelectorAll('.brush').forEach((b) => b.classList.remove('selected'));
  target.classList.add('selected');
  drawTool?.setBrushSize(Number(target.dataset.size ?? 5));
}

function buildToolbar(): void {
  const colors = el('draw-colors');
  for (const color of SWATCHES) {
    const swatch = document.createElement('button');
    swatch.className = 'swatch';
    swatch.dataset.color = color;
    swatch.style.background = color;
    swatch.title = color === '#ffffff' ? 'Eraser (white)' : '';
    swatch.addEventListener('click', () => selectSwatch(swatch));
    colors.appendChild(swatch);
  }

  const sizes = el('draw-sizes');
  for (const size of BRUSH_SIZES) {
    const brush = document.createElement('button');
    brush.className = 'brush';
    brush.dataset.size = String(size);
    const dot = document.createElement('span');
    dot.className = 'brush-dot';
    dot.style.width = dot.style.height = `${Math.max(4, size)}px`;
    brush.appendChild(dot);
    brush.addEventListener('click', () => selectBrush(brush));
    sizes.appendChild(brush);
  }
}

// --- Actions ---

async function createRoom(): Promise<string> {
  const res = await fetch('/api/rooms', { method: 'POST' });
  if (!res.ok) throw new Error('Failed to create room');
  const body = (await res.json()) as { roomCode: string };
  return body.roomCode;
}

async function enterRoom(code: string, name: string): Promise<void> {
  setError('');
  roomCode = code.trim().toUpperCase();
  myName = name.trim();
  if (!roomCode) return setError('Enter a room code.');
  if (!myName) return setError('Enter your name.');

  try {
    conn = await connect(roomCode, {
      onRoomEvent,
      onPersonalEvent,
      onDisconnect: () => leaveToHome('Connection lost.'),
    });
    conn.send(`/app/room/${roomCode}/join`, { name: myName });
  } catch {
    setError('Could not reach the server.');
  }
}

function wireUp(): void {
  el('btn-create').addEventListener('click', async () => {
    const name = el<HTMLInputElement>('input-name').value;
    if (!name.trim()) return setError('Enter your name first.');
    try {
      const code = await createRoom();
      await enterRoom(code, name);
    } catch {
      setError('Could not create a room.');
    }
  });

  el('btn-join').addEventListener('click', () => {
    void enterRoom(el<HTMLInputElement>('input-code').value, el<HTMLInputElement>('input-name').value);
  });

  el<HTMLInputElement>('input-code').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') el('btn-join').click();
  });

  el('btn-start').addEventListener('click', () => {
    conn?.send(`/app/room/${roomCode}/start`);
  });

  el('btn-submit-prompt').addEventListener('click', () => {
    const input = el<HTMLInputElement>('input-prompt');
    const text = input.value.trim();
    if (!text) return;
    conn?.send(`/app/room/${roomCode}/prompt`, { text });
  });

  el<HTMLInputElement>('input-prompt').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') el('btn-submit-prompt').click();
  });

  el('btn-undo').addEventListener('click', () => drawTool?.undo());
  el('btn-clear').addEventListener('click', () => drawTool?.clear());
  el('btn-submit-drawing').addEventListener('click', () => {
    stopDrawTimer();
    submitDrawing();
  });

  el('btn-again').addEventListener('click', () => {
    conn?.send(`/app/room/${roomCode}/again`);
  });
}

buildToolbar();
wireUp();
showScreen('home');
