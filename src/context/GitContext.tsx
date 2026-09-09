import {
  createContext,
  useContext,
  useState,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  type ReactNode,
} from "react";
import { listen } from "@tauri-apps/api/event";
import { toast } from "sonner";
import * as gitService from "../services/git";
import * as notesService from "../services/notes";
import type { GitStatus } from "../services/git";
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogCancel,
  AlertDialogAction,
} from "../components/ui";
import { useNotesData } from "./NotesContext";

interface GitContextValue {
  // State
  status: GitStatus | null;
  isLoading: boolean;
  isCommitting: boolean;
  isPushing: boolean;
  isPulling: boolean;
  isSyncing: boolean;
  isAddingRemote: boolean;
  gitAvailable: boolean;
  gitEnabled: boolean;
  isUpdatingGitEnabled: boolean;
  lastError: string | null;

  // Actions
  setGitEnabled: (enabled: boolean) => Promise<boolean>;
  refreshStatus: () => Promise<void>;
  initRepo: () => Promise<boolean>;
  commit: (message: string) => Promise<boolean>;
  push: () => Promise<boolean>;
  pull: () => Promise<string | false>;
  sync: (force?: boolean) => Promise<{ ok: true; message: string } | { ok: false; error: string }>;
  addRemote: (url: string) => Promise<boolean>;
  setRemoteUrl: (url: string) => Promise<boolean>;
  removeRemote: () => Promise<boolean>;
  pushWithUpstream: () => Promise<boolean>;
  clearError: () => void;
}

const GitContext = createContext<GitContextValue | null>(null);

export function GitProvider({ children }: { children: ReactNode }) {
  const { notesFolder } = useNotesData();
  const [status, setStatus] = useState<GitStatus | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isCommitting, setIsCommitting] = useState(false);
  const [isPushing, setIsPushing] = useState(false);
  const [isPulling, setIsPulling] = useState(false);
  const [isSyncing, setIsSyncing] = useState(false);
  const [isAddingRemote, setIsAddingRemote] = useState(false);
  const [gitAvailable, setGitAvailable] = useState(false);
  const [gitEnabled, setGitEnabledState] = useState(false);
  const [isUpdatingGitEnabled, setIsUpdatingGitEnabled] = useState(false);
  const [lastError, setLastError] = useState<string | null>(null);

  // Use refs to avoid dependency cycles
  const hasLoadedRef = useRef(false);
  const refreshInFlightRef = useRef(false);
  const refreshRequestIdRef = useRef(0);
  const settingsReadRequestIdRef = useRef(0);
  const gitToggleRequestIdRef = useRef(0);
  const notesFolderRef = useRef(notesFolder);
  notesFolderRef.current = notesFolder;
  const gitEnabledRef = useRef(gitEnabled);
  gitEnabledRef.current = gitEnabled;

  const refreshStatus = useCallback(async () => {
    if (!notesFolder) return;

    // Prevent concurrent refreshes from piling up
    if (refreshInFlightRef.current) return;
    refreshInFlightRef.current = true;
    const requestId = ++refreshRequestIdRef.current;
    const folderAtStart = notesFolder;
    const isStale = () =>
      requestId !== refreshRequestIdRef.current ||
      notesFolderRef.current !== folderAtStart;

    // Only show loading spinner on the very first load
    const isInitialLoad = !hasLoadedRef.current;
    if (isInitialLoad) {
      setIsLoading(true);
    }

    try {
      const rawStatus: any = await gitService.getGitStatus();
      const newStatus: GitStatus = {
        isRepo: Boolean(rawStatus?.isRepo ?? rawStatus?.is_repo),
        hasRemote: Boolean(rawStatus?.hasRemote ?? rawStatus?.has_remote),
        hasUpstream: Boolean(rawStatus?.hasUpstream ?? rawStatus?.has_upstream),
        currentBranch: rawStatus?.currentBranch ?? rawStatus?.current_branch ?? null,
        remoteUrl: rawStatus?.remoteUrl ?? rawStatus?.remote_url ?? null,
        changedCount: rawStatus?.changedCount ?? rawStatus?.changed_count ?? 0,
        aheadCount: rawStatus?.aheadCount ?? rawStatus?.ahead_count ?? 0,
        behindCount: rawStatus?.behindCount ?? rawStatus?.behind_count ?? 0,
        error: rawStatus?.error ?? null,
      };
      console.log("[Git] getGitStatus normalized result for folder:", folderAtStart, newStatus);

      if (isStale()) return;

      hasLoadedRef.current = true;
      setStatus(newStatus);

      if (newStatus.isRepo) {
        if (!gitEnabledRef.current) {
          console.log("[Git] Auto-enabling Git state for repository in:", folderAtStart);
          setGitEnabledState(true);
          notesService.updateGitEnabled(true, folderAtStart).catch(() => {});
        }
      }

      if (newStatus.error) {
        setLastError(newStatus.error);
      }
    } catch (err) {
      if (isStale()) return;

      setLastError(err instanceof Error ? err.message : "Failed to get git status");
    } finally {
      refreshInFlightRef.current = false;
      if (isInitialLoad) {
        setIsLoading(false);
      }
    }
  }, [notesFolder]);

  const setGitEnabled = useCallback(
    async (enabled: boolean) => {
      if (!notesFolder) return false;
      if (enabled === gitEnabled) return true;

      const requestId = ++gitToggleRequestIdRef.current;
      settingsReadRequestIdRef.current += 1;
      const folderAtStart = notesFolder;
      const isStale = () =>
        requestId !== gitToggleRequestIdRef.current ||
        notesFolderRef.current !== folderAtStart;
      const previous = gitEnabled;
      setGitEnabledState(enabled);
      setIsUpdatingGitEnabled(true);

      try {
        if (isStale()) return true;

        await notesService.updateGitEnabled(enabled, folderAtStart);

        if (isStale()) return true;

        console.log(`[Git] Git integration ${enabled ? "enabled" : "disabled"} for workspace:`, folderAtStart);
        return true;
      } catch (err) {
        if (isStale()) return true;

        const errMsg = err instanceof Error ? err.message : "Failed to update git setting";
        console.error("[Git] Failed to update Git integration setting:", errMsg, err);
        setGitEnabledState(previous);
        setLastError(errMsg);
        return false;
      } finally {
        if (requestId === gitToggleRequestIdRef.current) {
          setIsUpdatingGitEnabled(false);
        }
      }
    },
    [notesFolder, gitEnabled],
  );

  const initRepo = useCallback(async () => {
    console.log("[Git] Initializing Git repository in:", notesFolderRef.current);
    try {
      await gitService.initGitRepo();
      console.log("[Git] Git repository initialized successfully");
      await setGitEnabled(true);
      await refreshStatus();
      return true;
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : "Failed to initialize git";
      console.error("[Git] Failed to initialize Git repository:", errMsg, err);
      setLastError(errMsg);
      return false;
    }
  }, [refreshStatus, setGitEnabled]);

  const commit = useCallback(async (message: string) => {
    setIsCommitting(true);
    console.log("[Git] Committing changes with message:", message);
    try {
      const result = await gitService.gitCommit(message);
      if (result.error) {
        console.error("[Git] Failed to commit changes:", result.error);
        setLastError(result.error);
        return false;
      }
      console.log("[Git] Successfully committed changes:", result.message || message);
      await refreshStatus();
      return true;
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : "Failed to commit";
      console.error("[Git] Failed to commit changes:", errMsg, err);
      setLastError(errMsg);
      return false;
    } finally {
      setIsCommitting(false);
    }
  }, [refreshStatus]);

  const push = useCallback(async () => {
    setIsPushing(true);
    console.log("[Git] Pushing commits to remote...");
    try {
      const result = await gitService.gitPush();
      if (result.error) {
        console.error("[Git] Failed to push commits:", result.error);
        setLastError(result.error);
        return false;
      }
      console.log("[Git] Successfully pushed commits to remote:", result.message || "Pushed");
      await refreshStatus();
      return true;
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : "Failed to push";
      console.error("[Git] Failed to push commits:", errMsg, err);
      setLastError(errMsg);
      return false;
    } finally {
      setIsPushing(false);
    }
  }, [refreshStatus]);

  const pull = useCallback(async (): Promise<string | false> => {
    setIsPulling(true);
    console.log("[Git] Pulling latest changes from remote...");
    try {
      const result = await gitService.gitPull();
      if (result.error) {
        console.error("[Git] Failed to pull changes:", result.error);
        setLastError(result.error);
        return false;
      }
      const msg = result.message || "Pulled latest changes";
      console.log("[Git] Successfully pulled changes:", msg);
      await refreshStatus();
      return msg;
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : "Failed to pull";
      console.error("[Git] Failed to pull changes:", errMsg, err);
      setLastError(errMsg);
      return false;
    } finally {
      setIsPulling(false);
    }
  }, [refreshStatus]);

  const [vaultMismatchModal, setVaultMismatchModal] = useState<{
    remoteVaultId: string;
    localVaultId: string;
  } | null>(null);

  const sync = useCallback(async (force: boolean = false): Promise<{ ok: true; message: string } | { ok: false; error: string }> => {
    setIsSyncing(true);
    console.log(`[Git] Starting Dual-Branch sync (force=${force})...`);
    try {
      // Step 1: Layer 1 Signature Verification
      if (status?.hasRemote && !force) {
        const vaultCheck = await gitService.checkVaultSignature();
        if (!vaultCheck.matches && vaultCheck.remoteVaultId) {
          console.warn("[Git] Vault signature mismatch detected:", vaultCheck);
          setVaultMismatchModal({
            remoteVaultId: vaultCheck.remoteVaultId,
            localVaultId: vaultCheck.localVaultId,
          });
          return { ok: false, error: "Remote vault signature mismatch" };
        }
      }

      // Step 2: Pull from remote
      const pullResult = await gitService.gitPull();
      if (pullResult.error && !force) {
        console.error("[Git] Sync failed during pull step:", pullResult.error);
        setLastError(pullResult.error);
        return { ok: false, error: pullResult.error };
      }
      const didPull = pullResult.message !== "Already up to date";

      // Step 3: Dual-Branch push (Pushes to 'vault-backup' append branch + 'main' branch)
      const pushResult = await gitService.pushDualBranch(force);
      if (pushResult.error) {
        console.error("[Git] Sync failed during dual-branch push step:", pushResult.error);
        setLastError(pushResult.error);
        await refreshStatus();
        const errStr = didPull ? `Pulled changes, but push failed: ${pushResult.error}` : pushResult.error;
        return { ok: false, error: errStr };
      }

      await refreshStatus();

      let msg = "Already up to date";
      if (didPull && pushResult.success) msg = "Synced — pulled & pushed to main and backup";
      else if (didPull) msg = "Pulled latest changes";
      else if (pushResult.success) msg = pushResult.message || "Pushed to remote & backup";

      console.log("[Git] Dual-branch sync completed successfully:", msg);
      return { ok: true, message: msg };
    } catch (err) {
      const error = err instanceof Error ? err.message : "Failed to sync";
      console.error("[Git] Sync encountered error:", error, err);
      setLastError(error);
      return { ok: false, error };
    } finally {
      setIsSyncing(false);
    }
  }, [refreshStatus, status?.hasRemote]);

  const addRemote = useCallback(async (url: string) => {
    setIsAddingRemote(true);
    console.log("[Git] Connecting/Adding remote URL:", url);
    try {
      let result = await gitService.addRemote(url);
      if (result.error && result.error.toLowerCase().includes("already exists")) {
        console.log("[Git] Remote origin already exists, updating URL via setRemoteUrl...");
        result = await gitService.setRemoteUrl(url);
      }
      if (result.error) {
        console.error("[Git] Failed to add/set remote URL:", result.error);
        setLastError(result.error);
        return false;
      }
      console.log("[Git] Successfully connected remote URL:", url);
      await setGitEnabled(true);
      await refreshStatus();
      return true;
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : "Failed to add remote";
      if (errMsg.toLowerCase().includes("already exists")) {
        try {
          console.log("[Git] Caught exception 'already exists', falling back to setRemoteUrl...");
          const setResult = await gitService.setRemoteUrl(url);
          if (!setResult.error) {
            console.log("[Git] Successfully connected remote URL via fallback:", url);
            await setGitEnabled(true);
            await refreshStatus();
            return true;
          }
        } catch (fallbackErr) {
          console.error("[Git] Fallback setRemoteUrl failed:", fallbackErr);
        }
      }
      console.error("[Git] Failed to add remote URL:", errMsg, err);
      setLastError(errMsg);
      return false;
    } finally {
      setIsAddingRemote(false);
    }
  }, [refreshStatus, setGitEnabled]);

  const setRemoteUrl = useCallback(async (url: string) => {
    setIsAddingRemote(true);
    console.log("[Git] Updating remote URL to:", url);
    try {
      let result = await gitService.setRemoteUrl(url);
      if (result.error && result.error.toLowerCase().includes("no such remote")) {
        console.log("[Git] Remote origin does not exist, adding remote via addRemote...");
        result = await gitService.addRemote(url);
      }
      if (result.error) {
        console.error("[Git] Failed to update remote URL:", result.error);
        setLastError(result.error);
        return false;
      }
      console.log("[Git] Successfully updated remote URL to:", url);
      await setGitEnabled(true);
      await refreshStatus();
      return true;
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : "Failed to update remote";
      console.error("[Git] Failed to update remote URL:", errMsg, err);
      setLastError(errMsg);
      return false;
    } finally {
      setIsAddingRemote(false);
    }
  }, [refreshStatus, setGitEnabled]);

  const removeRemote = useCallback(async () => {
    setIsAddingRemote(true);
    console.log("[Git] Removing remote URL...");
    try {
      const result = await gitService.removeRemote();
      if (result.error) {
        console.error("[Git] Failed to remove remote URL:", result.error);
        setLastError(result.error);
        return false;
      }
      console.log("[Git] Successfully removed remote URL");
      await refreshStatus();
      return true;
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : "Failed to remove remote";
      console.error("[Git] Failed to remove remote URL:", errMsg, err);
      setLastError(errMsg);
      return false;
    } finally {
      setIsAddingRemote(false);
    }
  }, [refreshStatus]);

  const pushWithUpstream = useCallback(async () => {
    setIsPushing(true);
    console.log("[Git] Pushing commits and setting upstream branch...");
    try {
      const result = await gitService.pushWithUpstream();
      if (result.error) {
        console.error("[Git] Failed to push with upstream:", result.error);
        setLastError(result.error);
        return false;
      }
      console.log("[Git] Successfully pushed to remote with upstream branch");
      await refreshStatus();
      return true;
    } catch (err) {
      const errMsg = err instanceof Error ? err.message : "Failed to push with upstream";
      console.error("[Git] Failed to push with upstream:", errMsg, err);
      setLastError(errMsg);
      return false;
    } finally {
      setIsPushing(false);
    }
  }, [refreshStatus]);

  const clearError = useCallback(() => {
    setLastError(null);
  }, []);

  // Check git availability on mount
  useEffect(() => {
    gitService.isGitAvailable().then(setGitAvailable);
  }, []);

  // Load per-folder git visibility setting & auto-detect git repository
  useEffect(() => {
    if (!notesFolder) {
      settingsReadRequestIdRef.current += 1;
      setGitEnabledState(false);
      setIsUpdatingGitEnabled(false);
      return;
    }

    let cancelled = false;
    const requestId = ++settingsReadRequestIdRef.current;

    (async () => {
      try {
        // Step 1: Auto-detect by checking if workspace is a git repository
        const gitStatus = await gitService.getGitStatus();
        if (cancelled || requestId !== settingsReadRequestIdRef.current) return;

        if (gitStatus.isRepo) {
          console.log("[Git] Detected existing Git repository on startup in:", notesFolder);
          setGitEnabledState(true);
          setStatus(gitStatus);
          setIsLoading(false);
          hasLoadedRef.current = true;
          return;
        }

        // Step 2: Not a git repo, check user settings
        const settings = await notesService.getSettings();
        if (cancelled || requestId !== settingsReadRequestIdRef.current) return;

        if (settings.gitEnabled === true || settings.gitEnabled === false) {
          setGitEnabledState(settings.gitEnabled);
        } else {
          setGitEnabledState(false);
        }
      } catch (err) {
        if (cancelled || requestId !== settingsReadRequestIdRef.current) return;
        setGitEnabledState(false);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [notesFolder]);

  // Clear git-specific UI state when disabled
  useEffect(() => {
    if (gitEnabled) return;

    refreshRequestIdRef.current += 1;
    hasLoadedRef.current = false;
    refreshInFlightRef.current = false;
    setStatus(null);
    setIsLoading(false);
    setLastError(null);
  }, [gitEnabled]);

  // Keep stable refs so listeners/timers don't need to re-register
  const refreshStatusRef = useRef(refreshStatus);
  refreshStatusRef.current = refreshStatus;
  const isSyncingRef = useRef(false);
  isSyncingRef.current = isSyncing;

  // Refresh status when folder changes
  useEffect(() => {
    if (notesFolder && gitAvailable && gitEnabled) {
      refreshStatus();
    }
  }, [notesFolder, gitAvailable, gitEnabled, refreshStatus]);

  // Poll remote for changes periodically (every 60s) when a remote is configured
  // Fetch is separated from status to keep status checks fast and offline-friendly
  // Uses recursive setTimeout to prevent overlapping runs on slow networks
  useEffect(() => {
    if (!notesFolder || !gitAvailable || !gitEnabled || !status?.hasRemote) {
      return;
    }

    let cancelled = false;
    let timer: number;

    const poll = async () => {
      if (!isSyncingRef.current) {
        await gitService.gitFetch().catch(() => {});
        await refreshStatusRef.current();
      }
      if (!cancelled) {
        timer = window.setTimeout(poll, 60_000);
      }
    };

    timer = window.setTimeout(poll, 60_000);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [notesFolder, gitAvailable, gitEnabled, status?.hasRemote]);

  // Refresh status on file changes (debounced via existing file watcher)
  // Uses a ref so the listener is registered only once
  useEffect(() => {
    let isCancelled = false;
    let unlisten: (() => void) | undefined;
    let debounceTimer: number | undefined;

    listen("file-change", () => {
      if (isCancelled) return;
      if (!gitEnabledRef.current) return;

      // Debounce git status refresh to avoid excessive calls
      if (debounceTimer) {
        clearTimeout(debounceTimer);
      }
      debounceTimer = window.setTimeout(() => {
        refreshStatusRef.current();
      }, 1000);
    }).then((fn) => {
      if (isCancelled) {
        fn();
      } else {
        unlisten = fn;
      }
    });

    return () => {
      isCancelled = true;
      if (unlisten) unlisten();
      if (debounceTimer) clearTimeout(debounceTimer);
    };
  }, []);

  const value = useMemo<GitContextValue>(
    () => ({
      status,
      isLoading,
      isCommitting,
      isPushing,
      isPulling,
      isSyncing,
      isAddingRemote,
      gitAvailable,
      gitEnabled,
      isUpdatingGitEnabled,
      lastError,
      setGitEnabled,
      refreshStatus,
      initRepo,
      commit,
      push,
      pull,
      sync,
      addRemote,
      setRemoteUrl,
      removeRemote,
      pushWithUpstream,
      clearError,
    }),
    [
      status,
      isLoading,
      isCommitting,
      isPushing,
      isPulling,
      isSyncing,
      isAddingRemote,
      gitAvailable,
      gitEnabled,
      isUpdatingGitEnabled,
      lastError,
      setGitEnabled,
      refreshStatus,
      initRepo,
      commit,
      push,
      pull,
      sync,
      addRemote,
      setRemoteUrl,
      removeRemote,
      pushWithUpstream,
      clearError,
    ]
  );

  return (
    <GitContext.Provider value={value}>
      {children}
      {vaultMismatchModal && (
        <AlertDialog open={true} onOpenChange={(open) => !open && setVaultMismatchModal(null)}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Warning: Remote Vault Mismatch</AlertDialogTitle>
              <AlertDialogDescription>
                The remote repository appears to contain a different vault signature (Remote ID:{" "}
                <code className="text-accent font-mono bg-bg-secondary px-1 py-0.5 rounded">{vaultMismatchModal.remoteVaultId}</code>).
              </AlertDialogDescription>
            </AlertDialogHeader>
            <div className="p-3 bg-amber-500/10 border border-amber-500/20 rounded-md my-2">
              <p className="text-xs text-amber-500 font-medium">
                Dual-Branch Protection: Historical commits remain safely preserved on the <code className="font-mono">vault-backup</code> branch.
              </p>
            </div>
            <AlertDialogFooter>
              <AlertDialogCancel onClick={() => setVaultMismatchModal(null)}>
                Cancel
              </AlertDialogCancel>
              <AlertDialogAction
                className="bg-red-600 hover:bg-red-700 text-white"
                onClick={async () => {
                  setVaultMismatchModal(null);
                  const res = await sync(true);
                  if (res.ok) toast.success(res.message);
                  else toast.error(res.error);
                }}
              >
                Push & Overwrite Remote
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      )}
    </GitContext.Provider>
  );
}

const defaultGitContext: GitContextValue = {
  status: null,
  isLoading: false,
  isSyncing: false,
  isCommitting: false,
  isPushing: false,
  isPulling: false,
  isAddingRemote: false,
  gitAvailable: false,
  gitEnabled: false,
  isUpdatingGitEnabled: false,
  setGitEnabled: async () => false,
  sync: async () => ({ ok: false, error: "Git not available" }),
  initRepo: async () => false,
  commit: async () => false,
  push: async () => false,
  pull: async () => false,
  addRemote: async () => false,
  setRemoteUrl: async () => false,
  removeRemote: async () => false,
  pushWithUpstream: async () => false,
  refreshStatus: async () => {},
  lastError: null,
  clearError: () => {},
};

export function useGit() {
  const context = useContext(GitContext);
  if (!context) {
    console.warn("[Git] useGit called outside GitProvider. Returning fallback context.");
    return defaultGitContext;
  }
  return context;
}
