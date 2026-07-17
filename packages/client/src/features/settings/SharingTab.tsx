import { useEffect, useRef, useState } from "react";
import { Camera, Mic, Video } from "lucide-react";
import { Button } from "../../components/ui/Button";
import { setPref, usePrefs } from "../../lib/prefs";
import { SectionTitle, SelectField, Toggle } from "./controls";

type PermissionState = "granted" | "denied" | "prompt" | "unknown";

/**
 * Device labels are blank until permission is granted once - that's the browser
 * hiding them, not a bug, so the UI asks for access before listing.
 */
function useDevices(permission: PermissionState) {
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([]);

  useEffect(() => {
    if (!navigator.mediaDevices?.enumerateDevices) return;
    const refresh = () => {
      navigator.mediaDevices
        .enumerateDevices()
        .then(setDevices)
        .catch(() => setDevices([]));
    };
    refresh();
    navigator.mediaDevices.addEventListener("devicechange", refresh);
    return () => navigator.mediaDevices.removeEventListener("devicechange", refresh);
  }, [permission]);

  return devices;
}

function optionsFor(devices: MediaDeviceInfo[], kind: MediaDeviceKind, fallback: string) {
  const matching = devices.filter((d) => d.kind === kind && d.deviceId);
  return [
    { value: "default", label: fallback },
    ...matching.map((d, i) => ({
      value: d.deviceId,
      label: d.label || `${fallback} ${i + 1}`,
    })),
  ];
}

/** Live input level, so "is this the right mic?" is answerable without a call. */
function MicLevelMeter({ deviceId }: { deviceId: string }) {
  const [level, setLevel] = useState(0);
  const [testing, setTesting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cleanup = useRef<(() => void) | null>(null);

  useEffect(() => () => cleanup.current?.(), []);

  const stop = () => {
    cleanup.current?.();
    cleanup.current = null;
    setTesting(false);
    setLevel(0);
  };

  const start = async () => {
    setError(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: deviceId && deviceId !== "default" ? { deviceId: { exact: deviceId } } : true,
      });
      const ctx = new AudioContext();
      const source = ctx.createMediaStreamSource(stream);
      const analyser = ctx.createAnalyser();
      analyser.fftSize = 512;
      source.connect(analyser);
      const data = new Uint8Array(analyser.frequencyBinCount);
      let raf = 0;

      const tick = () => {
        analyser.getByteTimeDomainData(data);
        // Peak deviation from the 128 midpoint, normalized to 0..1.
        let peak = 0;
        for (const v of data) peak = Math.max(peak, Math.abs(v - 128));
        setLevel(Math.min(1, peak / 96));
        raf = requestAnimationFrame(tick);
      };
      tick();
      setTesting(true);

      cleanup.current = () => {
        cancelAnimationFrame(raf);
        stream.getTracks().forEach((t) => t.stop());
        void ctx.close();
      };
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not open the microphone");
    }
  };

  return (
    <div className="space-y-2">
      <div className="h-2 w-full overflow-hidden rounded-full bg-surface-4">
        <div
          className="h-full rounded-full bg-primary transition-[width] duration-75"
          style={{ width: `${Math.round(level * 100)}%` }}
        />
      </div>
      <Button
        type="button"
        variant="secondary"
        size="sm"
        onClick={() => (testing ? stop() : void start())}
      >
        <Mic aria-hidden className="size-4" />
        {testing ? "Stop test" : "Test microphone"}
      </Button>
      {error && <p className="text-xs text-danger">{error}</p>}
    </div>
  );
}

function CameraPreview({ deviceId }: { deviceId: string }) {
  const video = useRef<HTMLVideoElement>(null);
  const stream = useRef<MediaStream | null>(null);
  const [on, setOn] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const stop = () => {
    stream.current?.getTracks().forEach((t) => t.stop());
    stream.current = null;
    setOn(false);
  };

  useEffect(() => () => stop(), []);

  const start = async () => {
    setError(null);
    try {
      const next = await navigator.mediaDevices.getUserMedia({
        video: deviceId && deviceId !== "default" ? { deviceId: { exact: deviceId } } : true,
      });
      stream.current = next;
      if (video.current) {
        video.current.srcObject = next;
        await video.current.play().catch(() => {});
      }
      setOn(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not open the camera");
    }
  };

  return (
    <div className="space-y-2">
      <div className="aspect-video w-full overflow-hidden rounded-lg border border-border bg-surface-1">
        <video
          ref={video}
          muted
          playsInline
          className="size-full object-cover"
          style={{ display: on ? "block" : "none" }}
        />
        {!on && (
          <div className="flex size-full items-center justify-center text-ink-muted">
            <Camera aria-hidden className="size-6" />
          </div>
        )}
      </div>
      <Button
        type="button"
        variant="secondary"
        size="sm"
        onClick={() => (on ? stop() : void start())}
      >
        <Video aria-hidden className="size-4" />
        {on ? "Stop preview" : "Test camera"}
      </Button>
      {error && <p className="text-xs text-danger">{error}</p>}
    </div>
  );
}

export function SharingTab() {
  const prefs = usePrefs();
  const [permission, setPermission] = useState<PermissionState>("unknown");
  const devices = useDevices(permission);

  useEffect(() => {
    if (!navigator.permissions?.query) return;
    // Firefox rejects unknown permission names; a failure just means "unknown".
    navigator.permissions
      .query({ name: "microphone" as PermissionName })
      .then((s) => setPermission(s.state as PermissionState))
      .catch(() => setPermission("unknown"));
  }, []);

  const requestAccess = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: true });
      stream.getTracks().forEach((t) => t.stop());
      setPermission("granted");
    } catch {
      setPermission("denied");
    }
  };

  const labelsHidden = devices.some((d) => d.kind !== "audiooutput" && !d.label);
  const supported = Boolean(navigator.mediaDevices?.getUserMedia);

  if (!supported) {
    return (
      <p className="text-sm text-ink-muted">
        This browser doesn't expose camera or microphone controls.
      </p>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <SectionTitle>Permissions</SectionTitle>
        <p className="mb-3 text-sm text-ink-secondary">
          OrangChat only opens your camera and microphone during a call, and while you're
          testing them here.
        </p>
        {permission === "denied" ? (
          <p className="text-sm text-danger">
            Camera and microphone are blocked. Re-allow them for this site in your browser's
            address bar, then reload.
          </p>
        ) : labelsHidden || permission !== "granted" ? (
          <Button type="button" variant="secondary" size="sm" onClick={() => void requestAccess()}>
            Allow camera & microphone
          </Button>
        ) : (
          <p className="text-sm text-success">Camera and microphone access is granted.</p>
        )}
      </div>

      <div className="space-y-4 border-t border-border pt-5">
        <SectionTitle>Microphone</SectionTitle>
        <SelectField
          label="Input device"
          value={prefs.micDeviceId}
          onChange={(v) => setPref("micDeviceId", v)}
          options={optionsFor(devices, "audioinput", "System default")}
          hint="Applies the next time you join a call."
        />
        <MicLevelMeter deviceId={prefs.micDeviceId} />
      </div>

      <div className="space-y-4 border-t border-border pt-5">
        <SectionTitle>Camera</SectionTitle>
        <SelectField
          label="Video device"
          value={prefs.cameraDeviceId}
          onChange={(v) => setPref("cameraDeviceId", v)}
          options={optionsFor(devices, "videoinput", "System default")}
        />
        <CameraPreview deviceId={prefs.cameraDeviceId} />
      </div>

      <div className="space-y-4 border-t border-border pt-5">
        <SectionTitle>Output</SectionTitle>
        <SelectField
          label="Speaker"
          value={prefs.speakerDeviceId}
          onChange={(v) => setPref("speakerDeviceId", v)}
          options={optionsFor(devices, "audiooutput", "System default")}
          hint="Chrome and Edge only - other browsers always use the system default."
        />
      </div>

      <div className="space-y-3 border-t border-border pt-5">
        <SectionTitle>When joining a call</SectionTitle>
        <Toggle
          checked={prefs.joinMuted}
          onChange={(v) => setPref("joinMuted", v)}
          label="Join muted"
          hint="Start every call with your microphone off."
        />
        <Toggle
          checked={prefs.joinWithVideo}
          onChange={(v) => setPref("joinWithVideo", v)}
          label="Join with camera on"
          hint="Start voice channels with video already publishing."
        />
      </div>

      <p className="text-xs text-ink-muted">
        These choices are stored on this device only - they're never sent to the server.
      </p>
    </div>
  );
}
