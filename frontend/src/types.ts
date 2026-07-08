// Mirrors the server-side model (GamePhase, GameEvent, RoomStateView)

export type GamePhase =
  | 'LOBBY'
  | 'PROMPT_SUBMISSION'
  | 'PROMPT_VOTING'
  | 'DRAWING'
  | 'BRACKET_VOTING'
  | 'RESULTS';

export interface PlayerView {
  name: string;
  host: boolean;
}

export interface RoomStateView {
  roomCode: string;
  phase: GamePhase;
  players: PlayerView[];
  minPlayersToStart: number;
}

export interface GameEvent {
  type: 'STATE' | 'JOIN_OK' | 'SYSTEM' | 'ERROR';
  code?: string;
  state?: RoomStateView;
}
