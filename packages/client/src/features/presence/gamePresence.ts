import { desktop, type GamePresenceReport } from "../../lib/desktop";
import { socket } from "../../lib/socket";
import { useAuthStore } from "../../stores/auth";

let registered = false;

/** Connect the account's privacy setting to the desktop-only process watcher. */
export function registerGamePresence(): void {
  if (registered) return;
  registered = true;

  const setEnabled = desktop?.setGamePresenceEnabled;
  const onDetected = desktop?.onGameDetected;
  if (!setEnabled && !onDetected) return;

  const pushEnabled = () => setEnabled?.(useAuthStore.getState().user?.gameActivity === true);

  // The shell only speaks up when the detected game *changes*, so whatever it
  // said last is the whole truth about right now. Keeping it here is what lets
  // us answer a reconnect: the server drops game activity once the last desktop
  // socket goes, and without a replay the line would stay missing until the
  // player happened to quit into a different game.
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
