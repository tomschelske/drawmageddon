import { Client } from '@stomp/stompjs';
import type { GameEvent } from './types';

export interface Connection {
  send(destination: string, body?: unknown): void;
  disconnect(): void;
}

export interface Handlers {
  onRoomEvent(event: GameEvent): void;
  onPersonalEvent(event: GameEvent): void;
  onDisconnect(): void;
}

/** Connect, subscribe to the room topic + personal queue, then hand back a sender. */
export function connect(roomCode: string, handlers: Handlers): Promise<Connection> {
  return new Promise((resolve, reject) => {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const client = new Client({
      brokerURL: `${proto}://${location.host}/ws`,
      reconnectDelay: 0, // reconnect handling is an explicit non-goal for v1
    });

    let connected = false;

    client.onConnect = () => {
      connected = true;
      client.subscribe(`/topic/room/${roomCode}`, (msg) => {
        handlers.onRoomEvent(JSON.parse(msg.body) as GameEvent);
      });
      client.subscribe('/user/queue/personal', (msg) => {
        handlers.onPersonalEvent(JSON.parse(msg.body) as GameEvent);
      });
      resolve({
        send(destination, body) {
          client.publish({ destination, body: body === undefined ? '' : JSON.stringify(body) });
        },
        disconnect() {
          client.deactivate();
        },
      });
    };

    client.onWebSocketClose = () => {
      if (!connected) {
        reject(new Error('Could not connect to server'));
      } else {
        handlers.onDisconnect();
      }
    };

    client.activate();
  });
}
