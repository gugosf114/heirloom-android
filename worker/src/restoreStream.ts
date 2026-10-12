/** Inspect only event metadata. Never accumulate or rescan the growing photo. */
export class RestorationStreamObserver {
  private readonly decoder = new TextDecoder();
  private metadata = '';
  private inImage = false;
  private imageReplaced = false;
  private invalid = false;
  private finalSeen = false;
  private errorSeen = false;
  private imageHasContent = false;
  private escapePending = false;
  static readonly MAX_METADATA = 16 * 1024;

  get retainedCharacters(): number { return this.metadata.length; }
  get succeeded(): boolean { return this.finalSeen && !this.errorSeen; }

  push(chunk: Uint8Array): void {
    this.accept(this.decoder.decode(chunk, { stream: true }));
  }

  finish(): boolean {
    this.accept(this.decoder.decode());
    if (this.metadata || this.inImage || this.invalid) this.endLine();
    return this.succeeded;
  }

  private accept(text: string): void {
    let from = 0;
    while (from < text.length) {
      const newline = text.indexOf('\n', from);
      const end = newline < 0 ? text.length : newline;
      this.fragment(text.slice(from, end));
      if (newline < 0) return;
      this.endLine();
      from = newline + 1;
    }
  }

  private fragment(text: string): void {
    if (this.invalid) return;
    if (this.inImage) {
      // The pipeline uses base64 (no quotes/escapes), but handle JSON escapes too.
      let from = 0;
      if (this.escapePending && text.length) { from = 1; this.escapePending = false; }
      let quote = text.indexOf('"', from);
      while (quote >= 0) {
        let slashes = 0;
        for (let i = quote - 1; i >= from && text[i] === '\\'; i--) slashes++;
        if (slashes % 2 === 0) break;
        quote = text.indexOf('"', quote + 1);
      }
      if (quote < 0) {
        this.imageHasContent ||= text.length > 0;
        let slashes = 0;
        for (let i = text.length - 1; i >= from && text[i] === '\\'; i--) slashes++;
        this.escapePending = slashes % 2 === 1;
        return;
      }
      this.imageHasContent ||= quote > 0;
      this.inImage = false;
      this.imageReplaced = true;
      this.metadata += (this.imageHasContent ? 'image' : '') + '"';
      this.append(text.slice(quote + 1));
      return;
    }
    if (this.imageReplaced) { this.append(text); return; }

    // A network chunk may contain the start of the image. Search only the small
    // event header, then discard image characters as they pass through.
    const joined = this.metadata + text;
    const header = joined.slice(0, RestorationStreamObserver.MAX_METADATA);
    const marker = /"restored_url"\s*:\s*"/.exec(header);
    if (marker) {
      const valueStart = marker.index + marker[0].length;
      this.metadata = joined.slice(0, valueStart);
      this.inImage = true;
      this.fragment(joined.slice(valueStart));
    } else {
      this.metadata = '';
      this.append(joined);
    }
  }

  private append(text: string): void {
    if (this.metadata.length + text.length > RestorationStreamObserver.MAX_METADATA) {
      this.invalid = true;
      this.metadata = '';
    } else this.metadata += text;
  }

  private endLine(): void {
    if (this.invalid || this.inImage) this.errorSeen = true;
    else if (this.metadata.trim()) {
      try {
        const event = JSON.parse(this.metadata) as { kind?: string; result?: { restored_url?: unknown } };
        if (event.kind === 'error') this.errorSeen = true;
        if (event.kind === 'final') {
          if (typeof event.result?.restored_url === 'string' && event.result.restored_url.length > 0) this.finalSeen = true;
          else this.errorSeen = true;
        }
      } catch { this.errorSeen = true; }
    }
    this.metadata = '';
    this.inImage = false;
    this.imageReplaced = false;
    this.imageHasContent = false;
    this.escapePending = false;
    this.invalid = false;
  }
}

export function monitorRestorationStream(
  body: ReadableStream<Uint8Array>,
  onSuccess: () => Promise<void>,
  onFailure: () => Promise<void>,
  ctx: Pick<ExecutionContext, 'waitUntil'>,
): ReadableStream<Uint8Array> {
  const reader = body.getReader();
  const observer = new RestorationStreamObserver();
  let settled = false;
  const settle = (success: boolean): void => {
    if (settled) return;
    settled = true;
    ctx.waitUntil(success ? onSuccess() : onFailure());
  };
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      try {
        const next = await reader.read();
        if (next.done) { settle(observer.finish()); controller.close(); return; }
        observer.push(next.value);
        controller.enqueue(next.value);
      } catch (error) { settle(false); controller.error(error); }
    },
    async cancel(reason) { settle(observer.succeeded); await reader.cancel(reason); },
  });
}
