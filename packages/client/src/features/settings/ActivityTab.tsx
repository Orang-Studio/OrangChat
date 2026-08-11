import { useEffect, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import type { UpdateProfileInput } from "@orangchat/shared";
import { Button } from "../../components/ui/Button";
import { desktop, type GameOverride } from "../../lib/desktop";
import { authStoreActions, useAuthStore } from "../../stores/auth";
import { updateProfile } from "../auth/api";
import { SectionTitle, Toggle } from "./controls";
import { t } from "../../lib/i18n";

function GameOverrides({ enabled }: { enabled: boolean }) {
  const listProcesses = desktop?.listGameProcesses;
  const getOverrides = desktop?.getGameOverrides;
  const setOverrides = desktop?.setGameOverrides;
  const [expanded, setExpanded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [processes, setProcesses] = useState<string[]>([]);
  const [overrides, setLocalOverrides] = useState<GameOverride[]>([]);
  const [selectedProcess, setSelectedProcess] = useState("");
  const [gameName, setGameName] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!getOverrides) return;
    let cancelled = false;
    void getOverrides()
      .then((saved) => {
        if (!cancelled) setLocalOverrides(saved);
      })
      .catch(() => {
        if (!cancelled) setError("Could not load your allowed games.");
      });
    return () => {
      cancelled = true;
    };
  }, [getOverrides]);

  if (!listProcesses || !getOverrides || !setOverrides) {
    return (
      <p className="text-xs text-ink-muted">
        {t("activityTab.customGameDetectionIsAvailableIn")}
      </p>
    );
  }

  const openPicker = async () => {
    setExpanded(true);
    setError(null);
    if (!enabled) return;
    setLoading(true);
    try {
      const running = await listProcesses();
      setProcesses(running);
      setSelectedProcess((current) => (running.includes(current) ? current : (running[0] ?? "")));
    } catch {
      setError("Could not read the running process list.");
    } finally {
      setLoading(false);
    }
  };

  const save = async (next: GameOverride[]) => {
    setError(null);
    try {
      setLocalOverrides(await setOverrides(next));
    } catch {
      setError("Could not save your allowed games.");
    }
  };

  const addOverride = async () => {
    const name = gameName.trim();
    if (!selectedProcess || !name) return;
    const next = overrides.filter((item) => item.process !== selectedProcess);
    await save([...next, { process: selectedProcess, name }]);
    setGameName("");
  };

  return (
    <div className="space-y-3 rounded-lg border border-border p-3">
      <div className="flex items-center justify-between gap-3">
        <div>
          <p className="text-sm font-medium">{t("activityTab.gameNotDetected")}</p>
          <p className="text-xs text-ink-muted">
            {t("activityTab.allowARunningProcessAndChoose")}
          </p>
        </div>
        <Button
          type="button"
          variant="secondary"
          size="sm"
          disabled={!enabled}
          loading={loading}
          onClick={() => void openPicker()}
        >
          {expanded ? "Refresh" : "Choose"}
        </Button>
      </div>

      {!enabled && (
        <p className="text-xs text-ink-muted">
          {t("activityTab.turnActivitySharingOnToInspect")}
        </p>
      )}

      {expanded && enabled && (
        <div className="space-y-3 border-t border-border pt-3">
          {processes.length > 0 ? (
            <>
              <label className="block text-sm font-medium text-ink-secondary">
                {t("activityTab.runningProcess")}
                <select
                  value={selectedProcess}
                  onChange={(event) => setSelectedProcess(event.target.value)}
                  className="mt-1.5 w-full rounded-lg border border-border bg-surface-1 px-3 py-2 text-sm"
                >
                  {processes.map((processName) => (
                    <option key={processName} value={processName}>
                      {processName}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block text-sm font-medium text-ink-secondary">
                {t("activityTab.gameName")}
                <input
                  value={gameName}
                  maxLength={128}
                  placeholder={t("activityTab.whatShouldFriendsSee")}
                  onChange={(event) => setGameName(event.target.value)}
                  className="mt-1.5 h-10 w-full rounded-lg border border-border bg-surface-1 px-3 text-sm text-ink placeholder:text-ink-muted"
                />
              </label>
              <Button
                type="button"
                size="sm"
                disabled={!selectedProcess || !gameName.trim()}
                onClick={() => void addOverride()}
              >
                {t("activityTab.addGame")}
              </Button>
            </>
          ) : (
            !loading && <p className="text-xs text-ink-muted">{t("activityTab.noRunningProcessesWereFound")}</p>
          )}
        </div>
      )}

      {overrides.length > 0 && (
        <div className="space-y-2 border-t border-border pt-3">
          {overrides.map((override) => (
            <div key={override.process} className="flex items-center justify-between gap-3 text-sm">
              <span className="min-w-0">
                <span className="block truncate font-medium">{override.name}</span>
                <span className="block truncate text-xs text-ink-muted">{override.process}</span>
              </span>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() =>
                  void save(overrides.filter((item) => item.process !== override.process))
                }
              >
                {t("common.remove")}
              </Button>
            </div>
          ))}
        </div>
      )}

      {error && (
        <p role="alert" className="text-xs text-danger">
          {error}
        </p>
      )}
    </div>
  );
}

export function ActivityTab() {
  const user = useAuthStore((s) => s.user);

  const mutation = useMutation({
    mutationFn: (input: UpdateProfileInput) => updateProfile(input),
    onSuccess: (updated) => authStoreActions.setUser(updated),
  });

  if (!user) return null;

  return (
    <div className="space-y-6">
      <div className="space-y-3">
        <SectionTitle>{t("activityTab.activity")}</SectionTitle>
        <p className="text-sm leading-relaxed text-ink-secondary">
          {t("activityTab.theDesktopAppDetectsTheGames")}
        </p>
        <Toggle
          checked={user.gameActivity}
          onChange={(gameActivity) => mutation.mutate({ gameActivity })}
          label={t("activityTab.displayTheGameYourePlaying")}
          hint={t("activityTab.theDesktopAppChecksRunningProcess")}
        />
        <GameOverrides enabled={user.gameActivity} />
      </div>

      {mutation.isError && (
        <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
          {mutation.error.message}
        </p>
      )}
    </div>
  );
}
