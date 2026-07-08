import { connect, type Connection } from './net';
import type { GameEvent, RoomStateView } from './types';

// --- App state ---

let conn: Connection | null = null;
let myName = '';
let roomCode = '';
let lastState: RoomStateView | null = null;

// --- DOM helpers ---

function el<T extends HTMLElement>(id: string): T {
  const node = document.getElementById(id);
  if (!node) throw new Error(`Missing element #${id}`);
  return node as T;
}

const screens = ['home', 'lobby', 'game'] as const;
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
};

// --- Rendering ---

function renderLobby(state: RoomStateView): void {
  el('lobby-code').textContent = state.roomCode;
  el('lobby-count').textContent =
    `${state.players.length} player${state.players.length === 1 ? '' : 's'}`;

  const list = el('lobby-players');
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
    if (player.name === myName) li.classList.add('me');
    list.appendChild(li);
  }

  const iAmHost = state.players.some((p) => p.host && p.name === myName);
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

function renderState(state: RoomStateView): void {
  lastState = state;
  if (state.phase === 'LOBBY') {
    renderLobby(state);
    showScreen('lobby');
  } else {
    // Placeholder until the real phase UIs land (prompts, canvas, bracket)
    el('game-phase').textContent = state.phase.replace(/_/g, ' ');
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
      alert(message);
    }
  } else if (event.type === 'JOIN_OK' && event.state) {
    renderState(event.state);
  }
}

function leaveToHome(message: string): void {
  conn?.disconnect();
  conn = null;
  lastState = null;
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
}

wireUp();
showScreen('home');
