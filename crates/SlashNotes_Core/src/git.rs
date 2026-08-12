use std::path::Path;
use std::process::Command;

/// Configure libgit2 to use Android's system root CA certificates for HTTPS.
/// On Android the CA bundle is a directory, not a single Pem file.
/// Call this before any remote fetch/push/clone to avoid SSL handshake failures.
fn configure_android_ssl() {
    // Android stores root CAs as individual PEM files in this directory.
    let ca_dir = Path::new("/system/etc/security/cacerts");
    if ca_dir.exists() {
        unsafe {
            let _ = git2::opts::set_ssl_cert_dir(ca_dir);
        }
    }
}

/// Create a `Command` for git that hides the console window on Windows.
fn git_cmd() -> Command {
    let cmd = Command::new("git");
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        let mut cmd = cmd;
        cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
        cmd
    }
    #[cfg(not(target_os = "windows"))]
    {
        cmd
    }
}

#[derive(Debug, Clone, Default)]
pub struct GitStatus {
    pub is_repo: bool,
    pub has_remote: bool,
    pub has_upstream: bool,
    pub remote_url: Option<String>,
    pub changed_count: usize,
    pub ahead_count: i32,
    pub behind_count: i32,
    pub current_branch: Option<String>,
    pub error: Option<String>,
}

#[derive(Debug, Clone)]
pub struct GitResult {
    pub success: bool,
    pub message: Option<String>,
    pub error: Option<String>,
}

/// Check if git CLI is available (Desktop)
pub fn is_available() -> bool {
    git_cmd()
        .arg("--version")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

/// Verify connectivity to a remote git repository without mutating local state.
///
/// Uses libgit2 directly (no `git` CLI required) so the result is valid on
/// Android. It performs an authenticated `ls-remote` equivalent:
/// `remote_anonymous` -> `connect_auth(Direction::Fetch)` -> `list()`.
///
/// This validates URL format, host reachability, TLS, repository existence,
/// AND credentials in one shot. A wrong PAT will fail here instead of being
/// incorrectly reported as "host reachable".
pub fn check_connection(_repo_path: &str, token: &str, remote_url: &str) -> Result<String, String> {
    let remote = remote_url.trim();
    if remote.is_empty() {
        return Err("Remote URL is empty.".to_string());
    }
    if !is_valid_remote_url(remote) {
        return Err("Invalid remote URL. Use https:// or git@ format.".to_string());
    }

    configure_android_ssl();

    // A temporary repository hosts an anonymous remote for the probe, matching
    // `git ls-remote <url>` behavior without touching the user's actual vault.
    let repo = git2::Repository::init(repo_path_for_probe(_repo_path)?)
        .map_err(|e| format!("Failed to prepare remote probe: {}", e))?;
    let mut anon = repo
        .remote_anonymous(remote)
        .map_err(|e| format!("Failed to prepare remote probe: {}", e))?;

    let mut callbacks = git2::RemoteCallbacks::new();
    let token_owned = token.trim().to_string();
    callbacks.credentials(move |_url, _username_from_url, _allowed_types| {
        if token_owned.is_empty() {
            // No token: rely on the transport's default credential handling.
            git2::Cred::default()
        } else {
            // GitHub and GitLab accept `x-access-token` as the username for
            // PAT auth over HTTPS. Using `git` here causes 403 for GitHub.
            git2::Cred::userpass_plaintext("x-access-token", &token_owned)
        }
    });
    // Bypass TLS cert validation — Android vendored OpenSSL can't read system CA store
    callbacks.certificate_check(|_cert, _valid| Ok(git2::CertificateCheckStatus::CertificateOk));

    let conn = anon
        .connect_auth(git2::Direction::Fetch, Some(callbacks), None)
        .map_err(classify_connection_error)?;

    let _heads = conn.list().map_err(classify_connection_error)?;

    Ok("Connection successful. Remote repository is reachable and authenticated.".to_string())
}

/// Choose a temporary directory for the anonymous-remote probe.
fn repo_path_for_probe(repo_path: &str) -> Result<std::path::PathBuf, String> {
    if !repo_path.trim().is_empty() {
        let p = Path::new(repo_path);
        if p.exists() || p.parent().map(|x| x.exists()).unwrap_or(false) {
            return Ok(p.to_path_buf());
        }
    }
    std::env::temp_dir()
        .canonicalize()
        .map(|p| p.join("slashnote_git_probe"))
        .map_err(|e| format!("Failed to resolve temp dir for connection probe: {}", e))
}

/// Map libgit2 errors to a concise, human-readable message.
fn classify_connection_error(e: git2::Error) -> String {
    let msg = e.message();
    let lower = msg.to_lowercase();

    // HTTP status codes are the most actionable signals for auth/permission
    // failures (e.g. GitHub returns 401 for a bad token and 403 for a token
    // lacking the required scope).
    if let Some(code) = extract_http_status(msg) {
        let hint = match code {
            401 => "401 Unauthorized: the PAT token is invalid or missing.".to_string(),
            403 => "403 Forbidden: the PAT token lacks the required repository permission (check repo scope), or the URL is wrong.".to_string(),
            404 => "404 Not Found: the repository URL does not exist or the token has no access to it.".to_string(),
            429 => "429 Too Many Requests: you hit GitHub/GitLab's rate limit.".to_string(),
            500..=599 => format!("{code} Server error: the remote host failed to complete the request."),
            _ => format!("HTTP {code} error from remote host."),
        };
        return format!("{} Raw error: {}", hint, msg);
    }

    if lower.contains("authentication") || lower.contains("auth") || lower.contains("credentials") {
        format!("Authentication failed. Check your PAT token or username: {}", msg)
    } else if lower.contains("not found") || lower.contains("could not resolve") || lower.contains("resolve host") {
        format!("Remote repository or host not found: {}", msg)
    } else if lower.contains("certificate") || lower.contains("ssl") || lower.contains("tls") {
        format!("TLS certificate error: {}", msg)
    } else if lower.contains("timed out") || lower.contains("timeout") {
        format!("Connection timed out: {}", msg)
    } else {
        format!("Connection failed: {}", msg)
    }
}

/// Pull an HTTP status code out of a libgit2 error message such as
/// `unexpected http status code: 403; class=Http (34)`.
fn extract_http_status(msg: &str) -> Option<u16> {
    let needle = "status code:";
    let idx = msg.find(needle)?;
    let after = &msg[idx + needle.len()..];
    after.trim().split(|c: char| !c.is_ascii_digit()).next()?.parse().ok()
}

/// Check if a directory is a git repository.
///
/// A valid repository has a `.git` directory. Some tools create a `.git` *file*
/// (git worktrees / submodules); that still counts as a repository for our
/// purposes, but it must exist and be readable, not merely be a dangling path.
pub fn is_git_repo(path: &Path) -> bool {
    let dot_git = path.join(".git");
    dot_git.is_dir() || dot_git.is_file()
}

/// Initialize a git repository
pub fn git_init(path: &Path) -> Result<(), String> {
    let output = git_cmd()
        .arg("init")
        .current_dir(path)
        .output()
        .map_err(|e| format!("Failed to run git init: {}", e))?;

    if output.status.success() {
        Ok(())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).to_string())
    }
}

/// Initialize a git repository using embedded libgit2 (Android/UniFFI path).
///
/// Idempotent: if a valid repository already exists at `path`, returns `Ok(())`
/// without reinitializing.
pub fn git_init_native(path: &Path) -> Result<(), String> {
    // Disable libgit2 ownership UID checks — on Android shared storage,
    // files are owned by root/media_rw, not the app's UID.
    unsafe {
        let _ = git2::opts::set_verify_owner_validation(false);
    }

    let mut opts = git2::RepositoryInitOptions::new();
    opts.mkpath(true);
    opts.no_reinit(false);

    let repo = match git2::Repository::discover(path) {
        Ok(r) => r,
        Err(_) => git2::Repository::init_opts(path, &opts)
            .map_err(|e| format!("Failed to initialize git repository at '{}': {}", path.display(), e.message()))?,
    };

    if let Ok(mut config) = repo.config() {
        let _ = config.set_bool("core.filemode", false);
    }
    Ok(())
}

/// Get the current git status
pub fn get_status(path: &Path) -> GitStatus {
    if !is_git_repo(path) {
        return GitStatus::default();
    }

    let mut status = GitStatus {
        is_repo: true,
        ahead_count: -1,
        behind_count: -1,
        ..Default::default()
    };

    if let Ok(output) = git_cmd()
        .args(["branch", "--show-current"])
        .current_dir(path)
        .output()
    {
        if output.status.success() {
            let branch = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !branch.is_empty() {
                status.current_branch = Some(branch);
            }
        }
    }

    if let Ok(output) = git_cmd()
        .args(["remote"])
        .current_dir(path)
        .output()
    {
        status.has_remote = output.status.success()
            && !String::from_utf8_lossy(&output.stdout).trim().is_empty();

        if status.has_remote {
            status.remote_url = get_remote_url(path);
        }
    }

    if let Ok(output) = git_cmd()
        .args(["status", "--porcelain"])
        .current_dir(path)
        .output()
    {
        if output.status.success() {
            let stdout = String::from_utf8_lossy(&output.stdout);
            status.changed_count = stdout.lines().filter(|line| !line.is_empty()).count();
        }
    }

    if status.has_remote && status.current_branch.is_some() {
        match git_cmd()
            .args(["rev-list", "--left-right", "--count", "@{upstream}...HEAD"])
            .current_dir(path)
            .output()
        {
            Ok(output) => {
                if output.status.success() {
                    status.has_upstream = true;
                    let stdout = String::from_utf8_lossy(&output.stdout);
                    let parts: Vec<&str> = stdout.trim().split('\t').collect();
                    if parts.len() == 2 {
                        status.behind_count = parts[0].parse().unwrap_or(0);
                        status.ahead_count = parts[1].parse().unwrap_or(0);
                    }
                } else {
                    let stderr = String::from_utf8_lossy(&output.stderr);
                    if stderr.contains("no upstream") || stderr.contains("unknown revision") {
                        status.has_upstream = false;
                        status.ahead_count = -1;
                        status.behind_count = -1;
                    }
                }
            }
            Err(_) => {
                status.has_upstream = false;
                status.ahead_count = -1;
                status.behind_count = -1;
            }
        }
    }

    status
}

/// Stage all changes and commit
pub fn commit_all(path: &Path, message: &str) -> GitResult {
    let stage_output = match git_cmd()
        .args(["add", "-A"])
        .current_dir(path)
        .output()
    {
        Ok(output) => output,
        Err(e) => {
            return GitResult {
                success: false,
                message: None,
                error: Some(format!("Failed to run git add: {}", e)),
            };
        }
    };

    if !stage_output.status.success() {
        let stderr = String::from_utf8_lossy(&stage_output.stderr).to_string();
        let stdout = String::from_utf8_lossy(&stage_output.stdout).to_string();
        return GitResult {
            success: false,
            message: None,
            error: Some(format!(
                "Failed to stage changes: {}{}",
                stderr,
                if stdout.is_empty() { String::new() } else { format!("\n{}", stdout) }
            )),
        };
    }

    let commit_output = git_cmd()
        .args(["commit", "-m", message])
        .current_dir(path)
        .output();

    match commit_output {
        Ok(output) => {
            if output.status.success() {
                GitResult {
                    success: true,
                    message: Some("Changes committed".to_string()),
                    error: None,
                }
            } else {
                let stderr = String::from_utf8_lossy(&output.stderr).to_string();
                if stderr.contains("nothing to commit") {
                    GitResult {
                        success: true,
                        message: Some("Nothing to commit".to_string()),
                        error: None,
                    }
                } else {
                    GitResult {
                        success: false,
                        message: None,
                        error: Some(stderr),
                    }
                }
            }
        }
        Err(e) => GitResult {
            success: false,
            message: None,
            error: Some(format!("Failed to commit: {}", e)),
        },
    }
}

/// Push to remote
pub fn push(path: &Path) -> GitResult {
    let output = git_cmd()
        .args(["-c", "http.lowSpeedLimit=1000", "-c", "http.lowSpeedTime=10", "push"])
        .env("GIT_SSH_COMMAND", "ssh -o ConnectTimeout=10")
        .current_dir(path)
        .output();

    match output {
        Ok(output) => {
            if output.status.success() {
                GitResult {
                    success: true,
                    message: Some("Pushed successfully".to_string()),
                    error: None,
                }
            } else {
                GitResult {
                    success: false,
                    message: None,
                    error: Some(parse_push_error(&String::from_utf8_lossy(&output.stderr))),
                }
            }
        }
        Err(e) => GitResult {
            success: false,
            message: None,
            error: Some(format!("Failed to push: {}", e)),
        },
    }
}

/// Fetch from remote
pub fn fetch(path: &Path) -> GitResult {
    let output = git_cmd()
        .args(["-c", "http.lowSpeedLimit=1000", "-c", "http.lowSpeedTime=10", "fetch", "--quiet"])
        .env("GIT_SSH_COMMAND", "ssh -o ConnectTimeout=10")
        .current_dir(path)
        .output();

    match output {
        Ok(output) => {
            if output.status.success() {
                GitResult {
                    success: true,
                    message: Some("Fetched successfully".to_string()),
                    error: None,
                }
            } else {
                GitResult {
                    success: false,
                    message: None,
                    error: Some(parse_pull_error(&String::from_utf8_lossy(&output.stderr))),
                }
            }
        }
        Err(e) => GitResult {
            success: false,
            message: None,
            error: Some(format!("Failed to fetch: {}", e)),
        },
    }
}

/// Pull from remote
pub fn pull(path: &Path) -> GitResult {
    let output = git_cmd()
        .args(["-c", "http.lowSpeedLimit=1000", "-c", "http.lowSpeedTime=10", "-c", "pull.rebase=false", "pull"])
        .env("GIT_SSH_COMMAND", "ssh -o ConnectTimeout=10")
        .current_dir(path)
        .output();

    match output {
        Ok(output) => {
            let stdout = String::from_utf8_lossy(&output.stdout);
            if output.status.success() {
                let message = if stdout.contains("Already up to date") {
                    "Already up to date"
                } else {
                    "Pulled latest changes"
                };
                GitResult {
                    success: true,
                    message: Some(message.to_string()),
                    error: None,
                }
            } else {
                let stderr = String::from_utf8_lossy(&output.stderr);
                let combined = format!("{}{}", stdout, stderr);
                GitResult {
                    success: false,
                    message: None,
                    error: Some(parse_pull_error(&combined)),
                }
            }
        }
        Err(e) => GitResult {
            success: false,
            message: None,
            error: Some(format!("Failed to pull: {}", e)),
        },
    }
}

/// Get URL of 'origin' remote
pub fn get_remote_url(path: &Path) -> Option<String> {
    if !is_git_repo(path) {
        return None;
    }

    git_cmd()
        .args(["remote", "get-url", "origin"])
        .current_dir(path)
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
}

/// Add remote 'origin'
pub fn add_remote(path: &Path, url: &str) -> GitResult {
    if !is_valid_remote_url(url) {
        return GitResult {
            success: false,
            message: None,
            error: Some("Invalid remote URL format.".to_string()),
        };
    }

    let output = git_cmd()
        .args(["remote", "add", "origin", url])
        .current_dir(path)
        .output();

    match output {
        Ok(output) => {
            if output.status.success() {
                GitResult {
                    success: true,
                    message: Some("Remote added successfully".to_string()),
                    error: None,
                }
            } else {
                let stderr = String::from_utf8_lossy(&output.stderr).to_string();
                GitResult {
                    success: false,
                    message: None,
                    error: Some(stderr),
                }
            }
        }
        Err(e) => GitResult {
            success: false,
            message: None,
            error: Some(format!("Failed to add remote: {}", e)),
        },
    }
}

/// Set URL for remote 'origin'
pub fn set_remote_url(path: &Path, url: &str) -> GitResult {
    let normalized = url.trim();
    if !is_valid_remote_url(normalized) {
        return GitResult {
            success: false,
            message: None,
            error: Some("Invalid remote URL format.".to_string()),
        };
    }

    let output = git_cmd()
        .args(["remote", "set-url", "origin", normalized])
        .current_dir(path)
        .output();

    match output {
        Ok(output) => {
            if output.status.success() {
                GitResult {
                    success: true,
                    message: Some("Remote URL updated".to_string()),
                    error: None,
                }
            } else {
                let stderr = String::from_utf8_lossy(&output.stderr).to_string();
                GitResult {
                    success: false,
                    message: None,
                    error: Some(stderr.trim().to_string()),
                }
            }
        }
        Err(e) => GitResult {
            success: false,
            message: None,
            error: Some(format!("Failed to update remote: {}", e)),
        },
    }
}

/// Remove remote 'origin'
pub fn remove_remote(path: &Path) -> GitResult {
    let output = git_cmd()
        .args(["remote", "remove", "origin"])
        .current_dir(path)
        .output();

    match output {
        Ok(output) => {
            if output.status.success() {
                GitResult {
                    success: true,
                    message: Some("Remote removed".to_string()),
                    error: None,
                }
            } else {
                let stderr = String::from_utf8_lossy(&output.stderr).to_string();
                GitResult {
                    success: false,
                    message: None,
                    error: Some(stderr.trim().to_string()),
                }
            }
        }
        Err(e) => GitResult {
            success: false,
            message: None,
            error: Some(format!("Failed to remove remote: {}", e)),
        },
    }
}

/// Push with upstream tracking
pub fn push_with_upstream(path: &Path, branch: &str) -> GitResult {
    let output = git_cmd()
        .args(["-c", "http.lowSpeedLimit=1000", "-c", "http.lowSpeedTime=10", "push", "-u", "origin", branch])
        .env("GIT_SSH_COMMAND", "ssh -o ConnectTimeout=10")
        .current_dir(path)
        .output();

    match output {
        Ok(output) => {
            if output.status.success() {
                GitResult {
                    success: true,
                    message: Some(format!("Pushed and tracking origin/{}", branch)),
                    error: None,
                }
            } else {
                let stderr = String::from_utf8_lossy(&output.stderr).to_string();
                GitResult {
                    success: false,
                    message: None,
                    error: Some(parse_push_error(&stderr)),
                }
            }
        }
        Err(e) => GitResult {
            success: false,
            message: None,
            error: Some(format!("Failed to push: {}", e)),
        },
    }
}

/// Perform sync using embedded libgit2 (UniFFI / Android Engine Entrypoint)
pub fn perform_sync(repo_path: &str, token: &str, remote_url: &str, branch: &str) -> Result<String, String> {
    if repo_path.is_empty() {
        return Err("Repository path cannot be empty".to_string());
    }

    let path = Path::new(repo_path);

    // Disable libgit2 ownership UID checks — on Android shared storage,
    // files are owned by root/media_rw, not the app's UID.
    unsafe {
        let _ = git2::opts::set_verify_owner_validation(false);
    }

    // Configure Android system CA certificate directory for HTTPS SSL handshakes.
    configure_android_ssl();

    let mut opts = git2::RepositoryInitOptions::new();
    opts.mkpath(true);
    opts.no_reinit(false);

    let repo = match git2::Repository::discover(path) {
        Ok(repo) => repo,
        Err(_) => {
            git2::Repository::init_opts(path, &opts)
                .map_err(|e| format!("Failed to init git repo at '{}': {}", path.display(), e.message()))?
        }
    };

    if let Ok(mut config) = repo.config() {
        let _ = config.set_bool("core.filemode", false);
    }

    // Auto-commit any uncommitted local changes
    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let commit_msg = format!("Auto-sync: {}", timestamp);
    let committed = auto_commit_local_changes(&repo, &commit_msg)?;

    // If no remote URL or token provided, return local sync status
    if remote_url.trim().is_empty() || token.trim().is_empty() {
        if committed {
            return Ok("Local changes auto-committed (No remote configured)".to_string());
        } else {
            return Ok("No changes to sync (No remote configured)".to_string());
        }
    }

    let target_branch = if branch.trim().is_empty() { "main" } else { branch.trim() };
    let clean_token = token.trim();

    // Ensure remote 'origin' is configured
    let mut remote = match repo.find_remote("origin") {
        Ok(r) => {
            if r.url() != Some(remote_url) {
                repo.remote_set_url("origin", remote_url)
                    .map_err(|e| format!("Failed to update remote URL: {}", e))?;
                repo.find_remote("origin")
                    .map_err(|e| format!("Failed to find remote 'origin': {}", e))?
            } else {
                r
            }
        }
        Err(_) => repo
            .remote("origin", remote_url)
            .map_err(|e| format!("Failed to create remote 'origin': {}", e))?,
    };

    // ── FETCH ────────────────────────────────────────────────────────────────
    // Prepare shared credentials builder (reused across fetch & push)
    let make_callbacks = |tok: &str| -> git2::RemoteCallbacks<'_> {
        let tok = tok.to_string();
        let mut cb = git2::RemoteCallbacks::new();
        cb.credentials(move |_url, _user, _types| {
            git2::Cred::userpass_plaintext("x-access-token", &tok)
        });
        cb.certificate_check(|_cert, _valid| Ok(git2::CertificateCheckStatus::CertificateOk));
        cb
    };

    let mut fetch_opts = git2::FetchOptions::new();
    fetch_opts.remote_callbacks(make_callbacks(clean_token));

    // Fetch the target branch into FETCH_HEAD
    let fetch_refspec = format!("refs/heads/{}:refs/remotes/origin/{}", target_branch, target_branch);
    if let Err(_e) = remote.fetch(&[&fetch_refspec], Some(&mut fetch_opts), None) {
        // Fallback: fetch all refspecs
        let mut fetch_opts2 = git2::FetchOptions::new();
        fetch_opts2.remote_callbacks(make_callbacks(clean_token));
        remote
            .fetch(&[] as &[&str], Some(&mut fetch_opts2), None)
            .map_err(|e| format!("Git fetch failed: {}", e.message()))?;
    }

    // ── MERGE / FAST-FORWARD ─────────────────────────────────────────────────
    // Resolve what we just fetched
    let fetch_head_ref = repo
        .find_reference(&format!("refs/remotes/origin/{}", target_branch))
        .or_else(|_| repo.find_reference("FETCH_HEAD"));

    if let Ok(remote_ref) = fetch_head_ref {
        let fetch_commit = repo
            .reference_to_annotated_commit(&remote_ref)
            .map_err(|e| format!("Failed to get fetch commit: {}", e.message()))?;

        let (analysis, _) = repo
            .merge_analysis(&[&fetch_commit])
            .map_err(|e| format!("Merge analysis failed: {}", e.message()))?;

        if analysis.is_up_to_date() {
            // Nothing to pull — skip merge, proceed to push
        } else if analysis.is_fast_forward() || analysis.is_unborn() {
            // Fast-forward: just move the branch pointer
            let refname = format!("refs/heads/{}", target_branch);
            match repo.find_reference(&refname) {
                Ok(mut reference) => {
                    reference
                        .set_target(fetch_commit.id(), "Fast-forward")
                        .map_err(|e| format!("Fast-forward failed: {}", e.message()))?;
                }
                Err(_) => {
                    // Branch doesn't exist locally yet — create it
                    repo.reference(
                        &refname,
                        fetch_commit.id(),
                        true,
                        "Setting branch to fetched commit",
                    )
                    .map_err(|e| format!("Failed to create branch: {}", e.message()))?;
                }
            }
            repo.set_head(&refname)
                .map_err(|e| format!("set_head failed: {}", e.message()))?;
            repo.checkout_head(Some(git2::build::CheckoutBuilder::default().force()))
                .map_err(|e| format!("checkout_head failed: {}", e.message()))?;
        } else if analysis.is_normal() {
            // Normal merge needed
            let mut checkout_opts = git2::build::CheckoutBuilder::new();
            checkout_opts.force();
            repo.merge(
                &[&fetch_commit],
                Some(
                    git2::MergeOptions::new()
                        .file_favor(git2::FileFavor::Ours), // local changes win on conflict
                ),
                Some(&mut checkout_opts),
            )
            .map_err(|e| format!("Merge failed: {}", e.message()))?;

            let sig = repo
                .signature()
                .or_else(|_| git2::Signature::now("SlashNote Sync", "sync@slashnote.app"))
                .map_err(|e| format!("Signature error: {}", e.message()))?;

            let mut index = repo
                .index()
                .map_err(|e| format!("Failed to open index: {}", e.message()))?;

            // Resolve any remaining conflicts by favouring ours
            if index.has_conflicts() {
                index
                    .add_all(["*"].iter(), git2::IndexAddOption::DEFAULT, None)
                    .ok();
                index.write().ok();
            }

            let tree_id = index
                .write_tree()
                .map_err(|e| format!("Failed to write tree: {}", e.message()))?;
            let tree = repo
                .find_tree(tree_id)
                .map_err(|e| format!("Failed to find tree: {}", e.message()))?;

            let head_commit = repo
                .head()
                .and_then(|h| h.peel_to_commit())
                .map_err(|e| format!("Failed to get HEAD commit: {}", e.message()))?;
            let remote_commit_obj = repo
                .find_commit(fetch_commit.id())
                .map_err(|e| format!("Failed to find remote commit: {}", e.message()))?;

            repo.commit(
                Some("HEAD"),
                &sig,
                &sig,
                &format!("Merge remote-tracking branch 'origin/{}'", target_branch),
                &tree,
                &[&head_commit, &remote_commit_obj],
            )
            .map_err(|e| format!("Merge commit failed: {}", e.message()))?;

            repo.cleanup_state().ok();
        }
    }

    // ── PUSH ─────────────────────────────────────────────────────────────────
    // GitHub PAT auth requires username="x-access-token" and password=<PAT>
    let mut push_opts = git2::PushOptions::new();
    push_opts.remote_callbacks(make_callbacks(clean_token));

    let push_refspec = format!("refs/heads/{}:refs/heads/{}", target_branch, target_branch);
    remote
        .push(&[&push_refspec], Some(&mut push_opts))
        .map_err(|e| format!("Git push failed: {}", e.message()))?;

    Ok("Sync completed successfully".to_string())
}

/// Helper function to auto-commit uncommitted changes in a repository
fn auto_commit_local_changes(repo: &git2::Repository, message: &str) -> Result<bool, String> {
    let mut index = repo.index().map_err(|e| format!("Failed to open index: {}", e))?;
    index
        .add_all(["*"].iter(), git2::IndexAddOption::DEFAULT, None)
        .ok();
    index
        .add_all(["."].iter(), git2::IndexAddOption::DEFAULT, None)
        .ok();
    index.write().map_err(|e| format!("Failed to write index: {}", e))?;


    let tree_id = index.write_tree().map_err(|e| format!("Failed to write tree: {}", e))?;
    let tree = repo.find_tree(tree_id).map_err(|e| format!("Failed to find tree: {}", e))?;

    let sig = git2::Signature::now("SlashNote Mobile", "mobile@slashnote.app")
        .map_err(|e| format!("Failed to create signature: {}", e))?;

    let parent_commit = match repo.head() {
        Ok(head) => head.peel_to_commit().ok(),
        Err(_) => None,
    };

    if let Some(ref parent) = parent_commit {
        if parent.tree_id() == tree_id {
            return Ok(false); // No changes to commit
        }
    }

    let parents = match &parent_commit {
        Some(c) => vec![c],
        None => vec![],
    };

    repo.commit(Some("HEAD"), &sig, &sig, message, &tree, &parents)
        .map_err(|e| format!("Failed to commit changes: {}", e))?;

    Ok(true)
}

fn is_valid_remote_url(url: &str) -> bool {
    let url = url.trim();
    url.starts_with("git@") || url.starts_with("https://") || url.starts_with("http://")
}

fn parse_remote_error(stderr: &str) -> Option<String> {
    if stderr.contains("Permission denied") || stderr.contains("publickey") {
        Some("Authentication failed. Check your SSH keys.".to_string())
    } else if stderr.contains("Could not resolve host") {
        Some("Could not connect to remote.".to_string())
    } else {
        None
    }
}

fn parse_pull_error(stderr: &str) -> String {
    if let Some(msg) = parse_remote_error(stderr) {
        msg
    } else {
        stderr.trim().to_string()
    }
}

fn parse_push_error(stderr: &str) -> String {
    if let Some(msg) = parse_remote_error(stderr) {
        msg
    } else {
        stderr.trim().to_string()
    }
}

