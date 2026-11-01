export class ActorRateLimit {
  private windows = new Map<string, { until: number; used: number }>();
  constructor(private requests = 8, private milliseconds = 60_000, private actors = 1024) {}
  consume(actor: string, now = Date.now()): boolean {
    for (const [key, value] of this.windows) if (value.until <= now) this.windows.delete(key);
    const window = this.windows.get(actor);
    if (window) { if (window.used >= this.requests) return false; window.used++; return true; }
    if (this.windows.size >= this.actors) return false;
    this.windows.set(actor, { until: now+this.milliseconds, used: 1 }); return true;
  }
}

export const actorRateLimit = new ActorRateLimit();
