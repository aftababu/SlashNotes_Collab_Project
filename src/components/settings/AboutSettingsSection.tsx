import { useState, useEffect } from "react";
import { getVersion } from "@tauri-apps/api/app";
import { toast } from "sonner";
import { showUpdateToast } from "../../App";
import { Button } from "../ui";
import { RefreshCwIcon, SpinnerIcon } from "../icons";

export function AboutSettingsSection() {
  const [appVersion, setAppVersion] = useState<string>("");
  const [checkingUpdate, setCheckingUpdate] = useState(false);

  useEffect(() => {
    getVersion()
      .then(setAppVersion)
      .catch(() => {});
  }, []);

  const handleCheckForUpdates = async () => {
    setCheckingUpdate(true);
    const result = await showUpdateToast();
    setCheckingUpdate(false);
    if (result === "no-update") {
      toast.success("You're on the latest version!");
    } else if (result === "error") {
      toast.error("Could not check for updates. Try again later.");
    }
  };

  return (
    <div className="space-y-8 py-8">
      {/* Version */}
      <section className="pb-2">
        <h2 className="text-xl font-medium mb-0.5">Version</h2>
        <p className="text-sm text-text-muted mb-4">
          You are currently using SlashNote v{appVersion || "..."}
        </p>
        <Button
          onClick={handleCheckForUpdates}
          disabled={checkingUpdate}
          variant="outline"
          size="md"
          className="gap-1.25"
        >
          {checkingUpdate ? (
            <>
              <SpinnerIcon className="w-4.5 h-4.5 stroke-[1.5] animate-spin" />
              Checking...
            </>
          ) : (
            <>
              <RefreshCwIcon className="w-4.5 h-4.5 stroke-[1.5]" />
              Check for Updates
            </>
          )}
        </Button>
      </section>

      {/* Divider */}
      <div className="border-t border-border border-dashed" />

      {/* About Section */}
      <section className="pb-2">
        <h2 className="text-xl font-medium mb-1">About SlashNote</h2>
        <p className="text-sm text-text-muted mb-4">
          SlashNote is a minimalist markdown scratchpad for capturing quick
          thoughts, todos, and ideas. It's offline-first, keyboard-optimized,
          AI-compatible, and open source with no cloud, no accounts, and no
          subscriptions.
        </p>
      </section>
    </div>
  );
}
