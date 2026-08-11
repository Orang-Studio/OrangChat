import { desktop, type GamePresenceReport } from "../../lib/desktop";
import { socket } from "../../lib/socket";
import { useAuthStore } from "../../stores/auth";

let registered = false;


export function registerGamePresence(): void {
  if (registered) return;
  registered = true;

  const setEnabled = desktop?.setGamePresenceEnabled;
  const onDetected = desktop?.onGameDetected;
  if (!setEnabled && !onDetected) return;

  const pushEnabled = () => setEnabled?.(useAuthStore.getState().user?.gameActivity === true);

  let lastReport: GamePresenceReport = null;

  const emit = (report: GamePresenceReport) => {
    const user = useAuthStore.getState().user;
    if (!user || (!user.gameActivity && report !== null)) return;
    socket.emit("activity:game", report);
  };

  onDetected?.((report: GamePresenceReport) => {
    lastReport = report;
    emit(report);
  });

  socket.on("connect", () => {
    if (lastReport) emit(lastReport);
  });

  useAuthStore.subscribe((state, previous) => {
    if (
      state.user?.gameActivity === previous.user?.gameActivity &&
      !!state.user === !!previous.user
    ) {
      return;
    }
    pushEnabled();
  });
  pushEnabled();
}
