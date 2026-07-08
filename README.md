# Drawmageddon (Prompt & Draw)

A real-time multiplayer bracket drawing party game. Players submit prompts, vote on one for everyone to draw, draw it simultaneously against a timer, then vote through a single-elimination bracket to crown the winning drawing.

Full spec: `prompt-and-draw-project-doc.docx`

## Stack

- **Backend:** Java 21, Spring Boot, WebSocket/STOMP (server-authoritative game state machine)
- **Frontend:** TypeScript + HTML5 Canvas, bundled with esbuild into Spring's static resources

## Development

```bash
# Frontend (from frontend/): install once, then build or watch
npm install
npm run build        # bundle to src/main/resources/static/js/app.js
npm run watch        # rebuild on change
npm run typecheck    # tsc --noEmit

# Backend (from repo root)
mvn spring-boot:run  # serves the app on http://localhost:8080
```

## Game loop

Lobby → prompt submission → prompt voting (live tally) → drawing (timer-bound) → bracket voting (hidden tally, no self-votes) → results.

## Status

- [x] Scaffold: lobby with room codes, join/host flow, game phase state machine
- [ ] Phase 1: prompt submission + live-tally prompt voting with tie-break
- [ ] Phase 2: canvas drawing tool (brush, color, undo, clear) + timed submission
- [ ] Phase 3: bracket seeding, byes, hidden-until-reveal voting
- [ ] Phase 4: bracket visualization + results polish
- [ ] Phase 5: deploy + document
