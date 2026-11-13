// Web Audio API feedback for driver HUD (Zero external audio dependencies, 100% offline)
class SoundManager {
  private ctx: AudioContext | null = null;

  private getContext(): AudioContext | null {
    if (typeof window === 'undefined') return null;
    const AudioCtx =
      window.AudioContext ||
      (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AudioCtx) return null;

    if (!this.ctx) {
      try {
        this.ctx = new AudioCtx();
      } catch {
        return null;
      }
    }

    if (this.ctx.state === 'suspended') {
      this.ctx.resume().catch(() => {});
    }

    return this.ctx;
  }

  private playTone(
    freq: number,
    startOffsetSec: number,
    durationSec: number,
    type: OscillatorType = 'sine',
    peakGain = 0.22
  ) {
    const ctx = this.getContext();
    if (!ctx) return;

    try {
      const now = ctx.currentTime + startOffsetSec;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();

      osc.type = type;
      osc.frequency.setValueAtTime(freq, now);

      gain.gain.setValueAtTime(0.0001, now);
      gain.gain.exponentialRampToValueAtTime(peakGain, now + 0.015);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + durationSec);

      osc.connect(gain);
      gain.connect(ctx.destination);

      osc.start(now);
      osc.stop(now + durationSec + 0.02);
    } catch {
      // Ignore audio hardware/policy errors safely
    }
  }

  public playStart() {
    this.playTone(523.25, 0.0, 0.11, 'triangle', 0.24); // C5
    this.playTone(783.99, 0.09, 0.16, 'triangle', 0.26); // G5
  }

  public playStop() {
    this.playTone(493.88, 0.0, 0.11, 'sine', 0.2); // B4
    this.playTone(329.63, 0.1, 0.18, 'sine', 0.2); // E4
  }

  public playAccepted() {
    this.playTone(523.25, 0.0, 0.09, 'triangle', 0.26); // C5
    this.playTone(659.25, 0.08, 0.09, 'triangle', 0.26); // E5
    this.playTone(783.99, 0.16, 0.18, 'triangle', 0.28); // G5
  }

  public playRejected() {
    this.playTone(260, 0.0, 0.12, 'sawtooth', 0.14);
    this.playTone(195, 0.13, 0.18, 'sawtooth', 0.14);
  }
}

export const soundManager = new SoundManager();
