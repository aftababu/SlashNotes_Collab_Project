import { useCallback, memo } from "react";
import { toast } from "sonner";
import { useGit } from "../../context/GitContext";
import { Button, IconButton, Tooltip } from "../ui";
import {
  GitBranchIcon,
  GitBranchDeletedIcon,
  GitCommitIcon,
  RefreshCwIcon,
  SpinnerIcon,
  SettingsIcon,
} from "../icons";
import { cn } from "../../lib/utils";
import { mod, isMac } from "../../lib/platform";

interface FooterProps {
  onOpenSettings?: () => void;
}

export const Footer = memo(function Footer({ onOpenSettings }: FooterProps) {
  const {
    status,
    isLoading,
    isSyncing,
    isCommitting,
    gitAvailable,
    sync,
    initRepo,
    commit,
    lastError,
    clearError,
  } = useGit();

  const isRepo = status?.isRepo === true;
  const hasChanges = (status?.changedCount ?? 0) > 0;
  const behindCount = Math.max(status?.behindCount ?? 0, 0);
  const aheadCount = Math.max(status?.aheadCount ?? 0, 0);
  const syncCount = behindCount + aheadCount;
  const hasRemote = status?.hasRemote === true;
  const hasUpstream = status?.hasUpstream === true;
  const canSync = hasRemote && (!hasUpstream || syncCount > 0);

  const handleCommit = useCallback(async () => {
    if (isCommitting || !hasChanges) return;
    const now = new Date();
    const dateStr = now.toISOString().slice(0, 10);
    const timeStr = now.toTimeString().slice(0, 5);
    const msg = `Update: ${dateStr} ${timeStr}`;
    try {
      const success = await commit(msg);
      if (success) {
        toast.success(`Committed: ${msg}`);
      } else {
        toast.error("Failed to commit");
      }
    } catch {
      toast.error("Failed to commit");
    }
  }, [commit, isCommitting, hasChanges]);

  const handleSync = useCallback(async () => {
    if (isSyncing) return;
    const result = await sync();
    if (result.ok) {
      toast.success(result.message);
    } else {
      toast.error(result.error);
    }
  }, [sync, isSyncing]);

  const handleEnableGit = useCallback(async () => {
    const success = await initRepo();
    if (success) {
      toast.success("Git repository initialized");
    } else {
      toast.error("Failed to initialize Git");
    }
  }, [initRepo]);

  const syncTooltip = isSyncing
    ? "Syncing..."
    : !hasRemote
      ? "No remote repository connected"
      : !hasUpstream
        ? "Push and track upstream branch"
        : behindCount > 0 && aheadCount > 0
          ? `${behindCount} to pull, ${aheadCount} to push`
          : behindCount > 0
            ? `${behindCount} commit${behindCount === 1 ? "" : "s"} to pull`
            : aheadCount > 0
              ? `${aheadCount} commit${aheadCount === 1 ? "" : "s"} to push`
              : "Synced with remote";

  if (!gitAvailable) {
    return (
      <div className="absolute bottom-3 right-3">
        <IconButton
          onClick={onOpenSettings}
          title={`Settings (${mod}${isMac ? "" : "+"}, to toggle)`}
          className="rounded-lg bg-bg-secondary border border-border hover:bg-bg-muted backdrop-blur-sm w-8 h-8"
        >
          <SettingsIcon className="w-4 h-4 stroke-[1.5]" />
        </IconButton>
      </div>
    );
  }

  return (
    <div className="shrink-0 border-t border-border bg-bg px-3 py-1.5 flex items-center justify-between gap-2 h-9 text-xs">
      {/* Left section: Commit icon button + Branch & Changes status */}
      <div className="flex items-center gap-1.5 min-w-0 overflow-hidden">
        {isRepo ? (
          <>
            <Tooltip content={hasChanges ? "Commit changes (Auto-timestamped)" : "Working tree clean"}>
              <IconButton
                onClick={handleCommit}
                disabled={!hasChanges || isCommitting}
                aria-label="Commit"
                className="w-6 h-6 rounded hover:bg-bg-muted disabled:opacity-40 shrink-0"
              >
                {isCommitting ? (
                  <SpinnerIcon className="w-3.5 h-3.5 animate-spin text-accent" />
                ) : (
                  <GitCommitIcon className={cn("w-3.5 h-3.5 stroke-[2]", hasChanges ? "text-accent" : "text-text-muted")} />
                )}
              </IconButton>
            </Tooltip>

            {status?.currentBranch ? (
              <Tooltip content={`Branch: ${status.currentBranch}`}>
                <span className="text-text-muted flex items-center gap-1 min-w-0">
                  <GitBranchIcon className="w-3.5 h-3.5 stroke-[1.5] shrink-0" />
                  <span className="font-medium truncate max-w-20">{status.currentBranch}</span>
                </span>
              </Tooltip>
            ) : (
              <span className="text-text-muted/50 flex items-center gap-1">
                <GitBranchDeletedIcon className="w-3.5 h-3.5 opacity-50 shrink-0" />
                <span className="font-medium opacity-50">No Branch</span>
              </span>
            )}

            {hasChanges ? (
              <Tooltip content={`${status?.changedCount} modified file(s)`}>
                <span className="text-amber-500 font-medium truncate">
                  {status?.changedCount} change{status?.changedCount === 1 ? "" : "s"}
                </span>
              </Tooltip>
            ) : (
              <span className="text-text-muted/50 truncate">Clean</span>
            )}

            {lastError && (
              <Tooltip content={lastError}>
                <Button
                  onClick={clearError}
                  variant="link"
                  className="text-xs h-auto p-0 text-red-500 hover:text-red-600 hover:no-underline truncate"
                >
                  Error
                </Button>
              </Tooltip>
            )}
          </>
        ) : (
          <Tooltip content="Initialize Git repository">
            <Button
              onClick={handleEnableGit}
              variant="ghost"
              className="text-xs h-auto p-0 hover:bg-transparent text-accent font-medium"
            >
              Enable Git
            </Button>
          </Tooltip>
        )}

        {isLoading && !lastError && (
          <SpinnerIcon className="w-3 h-3 text-text-muted animate-spin shrink-0" />
        )}
      </div>

      {/* Right section: Sync / Push & Settings icon buttons */}
      <div className="flex items-center gap-1 shrink-0">
        {isRepo && hasRemote && (
          <Tooltip content={syncTooltip}>
            <IconButton
              onClick={handleSync}
              disabled={!canSync || isSyncing}
              aria-label="Sync"
              className="w-6 h-6 rounded hover:bg-bg-muted disabled:opacity-40 relative shrink-0"
            >
              {isSyncing ? (
                <SpinnerIcon className="w-3.5 h-3.5 animate-spin text-accent" />
              ) : (
                <span className="relative flex items-center justify-center">
                  <RefreshCwIcon className={cn("w-3.5 h-3.5 stroke-[2]", !canSync && "opacity-50")} />
                  {syncCount > 0 && (
                    <span className="absolute -top-1 -right-1 min-w-3 h-3 flex items-center justify-center rounded-full bg-accent text-text-inverse text-[8px] font-bold px-0.5">
                      {syncCount}
                    </span>
                  )}
                </span>
              )}
            </IconButton>
          </Tooltip>
        )}

        <Tooltip content={`Git Settings (${mod}${isMac ? "" : "+"}, to toggle)`}>
          <IconButton
            onClick={onOpenSettings}
            aria-label="Git Settings"
            className="w-6 h-6 rounded hover:bg-bg-muted shrink-0"
          >
            <SettingsIcon className="w-3.5 h-3.5 stroke-[1.5] text-text-muted" />
          </IconButton>
        </Tooltip>
      </div>
    </div>
  );
});
