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
  /** Time left in the current timed phase, measured at broadcast; count down locally. */
  phaseRemainingMillis?: number;
  /** Present only during BRACKET_VOTING. */
  matchup?: MatchupView;
  /** Bracket overview (no images), present once the bracket is seeded. */
  bracket?: BracketRoundView[];
  /** Present during RESULTS; absent when no drawing survived. */
  champion?: DrawingView;
}

export interface DrawingView {
  id: string;
  artist: string;
  imageData: string;
}

export interface MatchupView {
  matchId: string;
  round: number;
  matchIndex: number;
  matchCount: number;
  a: DrawingView;
  b: DrawingView;
  votesIn: number;
  revealed: boolean;
  /** Only present once revealed — tallies are hidden while voting is open. */
  votesA?: number;
  votesB?: number;
  winnerId?: string;
  tieBroken?: boolean;
}

export interface BracketMatchSummary {
  aArtist: string;
  bArtist: string;
  winnerArtist?: string;
}

export interface BracketRoundView {
  matches: BracketMatchSummary[];
  byeArtist?: string;
}

export interface GameEvent {
  type: 'STATE' | 'JOIN_OK' | 'SYSTEM' | 'ERROR';
  code?: string;
  state?: RoomStateView;
}
