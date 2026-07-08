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
  /** Completed the current phase's action (submitted / voted). */
  done: boolean;
}

export interface PromptView {
  id: string;
  text: string;
  votes: number;
}

export interface RoomStateView {
  roomCode: string;
  phase: GamePhase;
  players: PlayerView[];
  minPlayersToStart: number;
  /** Present only during PROMPT_VOTING. */
  prompts?: PromptView[];
  /** Present once the prompt vote has resolved. */
  winningPrompt?: string;
}

export interface GameEvent {
  type: 'STATE' | 'JOIN_OK' | 'SYSTEM' | 'ERROR';
  code?: string;
  state?: RoomStateView;
}
