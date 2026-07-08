import { connect, type Connection } from './net';
import type { GameEvent, GamePhase, RoomStateView } from './types';

// --- App state ---

let conn: Connection | null = null;
let myName = '';
let roomCode = '';
let lastState: RoomStateView | null = null;
let myVote: string | null = null; // promptId I voted for (highlighting only; server is authoritative)

// --- DOM helpers ---

function el<T extends HTMLElement>(id: string): T {
  const node = document.getElementById(id);
  if (!node) throw new Error(`Missing element #${id}`);
  return node as T;
}

const screens = ['home', 'lobby', 'submit', 'vote', 'game'] as const;
type Screen = (typeof screens)[number];

function showScreen(name: Screen): void {
  for (const s of screens) {
    el(`screen-${s}`).classList.toggle('hidden', s !== name);
  }
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

function renderState(state: RoomStateView): void {
  const previousPhase: GamePhase | null = lastState?.phase ?? null;
  lastState = state;

  if (previousPhase !== state.phase && state.phase === 'PROMPT_VOTING') {
    myVote = null; // fresh ballot
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
    default:
      // DRAWING and beyond: placeholder until those phases land
      el('game-phase').textContent = state.phase.replace(/_/g, ' ');
      el('game-prompt').textContent = state.winningPrompt ?? '';
      el('game-prompt-wrap').classList.toggle('hidden', !state.winningPrompt);
      showScreen('game');
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
  setError(message);
  showScreen('home');
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
}

wireUp();
showScreen('home');
