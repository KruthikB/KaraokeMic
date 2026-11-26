import type { KaraokeStatus, LevelEvent, RouteEvent } from "../../modules/karaoke-audio/src/KaraokeAudio.types";
import type { VocalSettings } from "./session";
import { appFileUrl } from "../pwa/paths";
import { classifyWebOutputs } from "./webRoute";
import { impulseSample, spaceShape } from "./webSpace";

type LevelListener = (event: LevelEvent) => void;
type RouteListener = (event: RouteEvent) => void;
type StateListener = (event: { running: boolean; muted: boolean }) => void;
type ErrorListener = (event: { message: string }) => void;

type WakeLockSentinelLike = { release: () => Promise<void> };
type WakeLockLike = { request: (type: "screen") => Promise<WakeLockSentinelLike> };

function browserWakeLock(): WakeLockLike | null {
  if (typeof navigator === "undefined") {
    return null;
  }
  const candidate = navigator as Navigator & { wakeLock?: WakeLockLike };
  return candidate.wakeLock ?? null;
}

function messageFrom(error: unknown): string {
  if (error instanceof DOMException && error.name === "NotAllowedError") {
    return "Chrome blocked the microphone. Allow the mic for this site. Chrome only does that on HTTPS or localhost.";
  }
  if (error instanceof DOMException && error.name === "NotFoundError") {
    return "No microphone was found.";
  }
  if (error instanceof Error && error.message.trim().length > 0) {
    return error.message;
  }
  return "The microphone did not start.";
}

/**
 * Live mic in Chrome. The graph is the built-in mic, a high-pass, a noise
 * gate, echo, a generated-impulse reverb, then the speaker Chrome is using.
 */
class WebMicEngine {
  private context: AudioContext | null = null;
  private stream: MediaStream | null = null;
  private source: MediaStreamAudioSourceNode | null = null;
  private highpass: BiquadFilterNode | null = null;
  private worklet: AudioWorkletNode | null = null;
  private delay: DelayNode | null = null;
  private delayFeedback: GainNode | null = null;
  private echoWet: GainNode | null = null;
  private reverbWet: GainNode | null = null;
  private convolver: ConvolverNode | null = null;
  private master: GainNode | null = null;
  private analyser: AnalyserNode | null = null;
  private meterTimer: ReturnType<typeof setInterval> | null = null;
  private wakeLock: WakeLockSentinelLike | null = null;
  private wakeListenerAttached = false;
  private primed: MediaStream | null = null;
  private running = false;
  private muted = false;
  private gate = true;
  private gain = 0.85;
  private reverb = 0.06;
  private echo = 0;
  private space: VocalSettings["space"] = "clean";
  private routeKind: KaraokeStatus["route"] = "unknown";
  private routeName = "";
  private sampleRate = 0;

  private readonly levels = new Set<LevelListener>();
  private readonly routes = new Set<RouteListener>();
  private readonly states = new Set<StateListener>();
  private readonly errors = new Set<ErrorListener>();

  addLevelListener(listener: LevelListener): { remove: () => void } {
    this.levels.add(listener);
    return { remove: () => this.levels.delete(listener) };
  }

  addRouteListener(listener: RouteListener): { remove: () => void } {
    this.routes.add(listener);
    return { remove: () => this.routes.delete(listener) };
  }

  addStateListener(listener: StateListener): { remove: () => void } {
    this.states.add(listener);
    return { remove: () => this.states.delete(listener) };
  }

  addErrorListener(listener: ErrorListener): { remove: () => void } {
    this.errors.add(listener);
    return { remove: () => this.errors.delete(listener) };
  }

  status(): KaraokeStatus {
    return {
      running: this.running,
      muted: this.muted,
      route: this.routeKind,
      routeName: this.routeName,
      sampleRate: this.sampleRate,
      lowLatency: this.running,
    };
  }

  remember(settings: VocalSettings): void {
    this.gain = settings.gain;
    this.reverb = settings.reverb;
    this.echo = settings.echo;
    this.gate = settings.gate;
    this.space = settings.space;
    this.applyGraph();
  }

  async ensurePermission(gate: boolean): Promise<boolean> {
    this.gate = gate;
    try {
      this.releasePrimed();
      this.primed = await this.openStream(gate);
      return true;
    } catch (error) {
      this.emitError(messageFrom(error));
      return false;
    }
  }

  async start(settings: VocalSettings): Promise<void> {
    this.remember(settings);
    if (this.running && this.context) {
      this.applyGraph();
      return;
    }
    if (typeof window !== "undefined" && window.isSecureContext === false) {
      throw new Error("Chrome only opens the microphone on HTTPS or localhost.");
    }
    if (this.primed && this.primed.getAudioTracks()[0]?.getSettings().echoCancellation !== this.gate) {
      this.releasePrimed();
    }
    const stream = this.primed ?? (await this.openStream(this.gate));
    this.primed = null;
    const context = new AudioContext({ latencyHint: "interactive" });
    this.context = context;
    this.stream = stream;
    this.sampleRate = context.sampleRate;
    await this.buildGraph(context, stream);
    if (context.state !== "running") {
      await context.resume();
    }
    this.running = true;
    this.applyGraph();
    this.startMeter();
    await this.holdScreenOn();
    await this.publishRoute();
    this.emitState();
  }

  async stop(): Promise<void> {
    this.running = false;
    this.stopMeter();
    await this.releaseScreen();
    this.source?.disconnect();
    this.source = null;
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = null;
    this.releasePrimed();
    const context = this.context;
    this.context = null;
    this.highpass = null;
    this.worklet = null;
    this.delay = null;
    this.delayFeedback = null;
    this.echoWet = null;
    this.reverbWet = null;
    this.convolver = null;
    this.master = null;
    this.analyser = null;
    this.sampleRate = 0;
    if (context) {
      await context.close().catch(() => undefined);
    }
    this.muted = false;
    this.emitState();
  }

  setMuted(muted: boolean): void {
    this.muted = muted;
    this.applyGraph();
    this.emitState();
  }

  async setGate(enabled: boolean): Promise<void> {
    const changed = enabled !== this.gate;
    this.gate = enabled;
    this.worklet?.port.postMessage({ enabled });
    this.applyGraph();
    if (changed && this.running && this.context) {
      await this.replaceStream();
    }
  }

  private async buildGraph(context: AudioContext, stream: MediaStream): Promise<void> {
    const highpass = context.createBiquadFilter();
    highpass.type = "highpass";
    highpass.Q.value = 0.7;
    const analyser = context.createAnalyser();
    analyser.fftSize = 1024;
    analyser.smoothingTimeConstant = 0.65;
    const delay = context.createDelay(1);
    const delayFeedback = context.createGain();
    const echoWet = context.createGain();
    const convolver = context.createConvolver();
    const reverbWet = context.createGain();
    const dry = context.createGain();
    dry.gain.value = 1;
    const master = context.createGain();
    const compressor = context.createDynamicsCompressor();
    compressor.threshold.value = -8;
    compressor.knee.value = 10;
    compressor.ratio.value = 3;
    compressor.attack.value = 0.004;
    compressor.release.value = 0.12;

    const source = context.createMediaStreamSource(stream);
    source.connect(analyser);
    source.connect(highpass);

    let gated: AudioNode = highpass;
    if (context.audioWorklet) {
      try {
        await context.audioWorklet.addModule(appFileUrl("gate-processor.js", window.location.href));
        const worklet = new AudioWorkletNode(context, "stagemic-gate");
        worklet.port.postMessage({ enabled: this.gate });
        highpass.connect(worklet);
        gated = worklet;
        this.worklet = worklet;
      } catch {
        this.worklet = null;
      }
    }

    gated.connect(dry);
    dry.connect(master);
    gated.connect(delay);
    delay.connect(delayFeedback);
    delayFeedback.connect(delay);
    delay.connect(echoWet);
    echoWet.connect(master);
    gated.connect(convolver);
    convolver.connect(reverbWet);
    reverbWet.connect(master);
    master.connect(compressor);
    compressor.connect(context.destination);

    this.source = source;
    this.highpass = highpass;
    this.analyser = analyser;
    this.delay = delay;
    this.delayFeedback = delayFeedback;
    this.echoWet = echoWet;
    this.convolver = convolver;
    this.reverbWet = reverbWet;
    this.master = master;
    this.loadImpulse();
  }

  private applyGraph(): void {
    const context = this.context;
    if (!context || !this.master || !this.highpass || !this.delay || !this.delayFeedback || !this.echoWet || !this.reverbWet) {
      return;
    }
    const shape = spaceShape(this.space, this.gate);
    const now = context.currentTime;
    this.highpass.frequency.setTargetAtTime(shape.highpassHz, now, 0.02);
    this.delay.delayTime.setTargetAtTime(shape.delaySeconds, now, 0.02);
    this.delayFeedback.gain.setTargetAtTime(shape.echoFeedback, now, 0.02);
    this.echoWet.gain.setTargetAtTime(this.echo, now, 0.02);
    this.reverbWet.gain.setTargetAtTime(this.reverb, now, 0.02);
    this.master.gain.setTargetAtTime(this.muted ? 0 : this.gain, now, 0.015);
    this.worklet?.port.postMessage({ enabled: this.gate });
  }

  private loadImpulse(): void {
    const context = this.context;
    const convolver = this.convolver;
    if (!context || !convolver) {
      return;
    }
    const shape = spaceShape(this.space, this.gate);
    const length = Math.max(2, Math.floor(context.sampleRate * shape.seconds));
    const buffer = context.createBuffer(2, length, context.sampleRate);
    for (let channel = 0; channel < 2; channel += 1) {
      const data = buffer.getChannelData(channel);
      for (let index = 0; index < length; index += 1) {
        const noise = Math.random() * 2 - 1;
        data[index] = impulseSample(index, length, shape.decay, shape.room, noise);
      }
    }
    convolver.buffer = buffer;
  }

  setSpace(space: VocalSettings["space"]): void {
    this.space = space;
    this.loadImpulse();
    this.applyGraph();
  }

  private async replaceStream(): Promise<void> {
    const context = this.context;
    if (!context) {
      return;
    }
    const next = await this.openStream(this.gate);
    this.source?.disconnect();
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = next;
    const source = context.createMediaStreamSource(next);
    if (this.analyser) {
      source.connect(this.analyser);
    }
    if (this.highpass) {
      source.connect(this.highpass);
    }
    this.source = source;
    await this.publishRoute();
  }

  private async openStream(gate: boolean): Promise<MediaStream> {
    if (typeof navigator === "undefined" || !navigator.mediaDevices?.getUserMedia) {
      throw new Error("This browser cannot open the microphone.");
    }
    return navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: gate,
        noiseSuppression: gate,
        autoGainControl: false,
      },
      video: false,
    });
  }

  private startMeter(): void {
    this.stopMeter();
    const analyser = this.analyser;
    if (!analyser) {
      return;
    }
    const samples = new Float32Array(analyser.fftSize);
    this.meterTimer = setInterval(() => {
      analyser.getFloatTimeDomainData(samples);
      let sum = 0;
      let peak = 0;
      for (let index = 0; index < samples.length; index += 1) {
        const value = samples[index] ?? 0;
        const magnitude = Math.abs(value);
        sum += magnitude * magnitude;
        if (magnitude > peak) {
          peak = magnitude;
        }
      }
      const rms = Math.sqrt(sum / samples.length);
      for (const listener of this.levels) {
        listener({ rms, peak });
      }
    }, 66);
  }

  private stopMeter(): void {
    if (this.meterTimer) {
      clearInterval(this.meterTimer);
      this.meterTimer = null;
    }
  }

  private async publishRoute(): Promise<void> {
    let labels: string[] = [];
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      labels = devices.filter((device) => device.kind === "audiooutput").map((device) => device.label);
    } catch {
      labels = [];
    }
    const route = classifyWebOutputs(labels);
    this.routeKind = route.kind;
    this.routeName = route.name;
    for (const listener of this.routes) {
      listener({ kind: route.kind, name: route.name });
    }
  }

  private async holdScreenOn(): Promise<void> {
    const wakeLock = browserWakeLock();
    if (!wakeLock || typeof document === "undefined") {
      return;
    }
    try {
      this.wakeLock = await wakeLock.request("screen");
    } catch {
      this.wakeLock = null;
    }
    if (!this.wakeListenerAttached) {
      document.addEventListener("visibilitychange", this.onVisible);
      this.wakeListenerAttached = true;
    }
  }

  private readonly onVisible = (): void => {
    if (document.visibilityState === "visible" && this.running) {
      void this.holdScreenOn();
    }
  };

  private async releaseScreen(): Promise<void> {
    if (typeof document !== "undefined" && this.wakeListenerAttached) {
      document.removeEventListener("visibilitychange", this.onVisible);
      this.wakeListenerAttached = false;
    }
    const lock = this.wakeLock;
    this.wakeLock = null;
    if (lock) {
      await lock.release().catch(() => undefined);
    }
  }

  private releasePrimed(): void {
    this.primed?.getTracks().forEach((track) => track.stop());
    this.primed = null;
  }

  private emitState(): void {
    const event = { running: this.running, muted: this.muted };
    for (const listener of this.states) {
      listener(event);
    }
  }

  private emitError(message: string): void {
    for (const listener of this.errors) {
      listener({ message });
    }
  }
}

export const webMicEngine = new WebMicEngine();
