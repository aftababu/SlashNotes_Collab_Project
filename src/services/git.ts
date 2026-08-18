import { invoke } from "@tauri-apps/api/core";

export interface GitStatus {
  isRepo: boolean;
  hasRemote: boolean;
  hasUpstream: boolean;
  remoteUrl: string | null;
  changedCount: number;
  aheadCount: number;
  behindCount: number;
  currentBranch: string | null;
  error: string | null;
}

export interface GitResult {
  success: boolean;
  message: string | null;
  error: string | null;
}

export async function isGitAvailable(): Promise<boolean> {
  return invoke("git_is_available");
}

export async function getGitStatus(): Promise<GitStatus> {
  return invoke("git_get_status");
}

export async function initGitRepo(): Promise<void> {
  return invoke("git_init_repo");
}

export async function gitCommit(message: string): Promise<GitResult> {
  return invoke("git_commit", { message });
}

export interface VaultSignatureCheck {
  matches: boolean;
  remoteVaultId: string | null;
  localVaultId: string;
}

export async function checkVaultSignature(): Promise<VaultSignatureCheck> {
  return invoke("git_check_vault_signature");
}

export async function pushDualBranch(force: boolean = false): Promise<GitResult> {
  return invoke("git_push_dual_branch", { force });
}

export async function gitPush(): Promise<GitResult> {
  return invoke("git_push_dual_branch", { force: false });
}

export async function gitFetch(): Promise<GitResult> {
  return invoke("git_fetch");
}

export async function gitPull(): Promise<GitResult> {
  return invoke("git_pull");
}

export async function addRemote(url: string): Promise<GitResult> {
  return invoke("git_add_remote", { url });
}

export async function setRemoteUrl(url: string): Promise<GitResult> {
  return invoke("git_set_remote_url", { url });
}

export async function removeRemote(): Promise<GitResult> {
  return invoke("git_remove_remote");
}

export async function pushWithUpstream(): Promise<GitResult> {
  return invoke("git_push_with_upstream");
}
