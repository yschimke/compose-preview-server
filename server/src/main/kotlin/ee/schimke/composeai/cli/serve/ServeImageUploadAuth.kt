package ee.schimke.composeai.cli.serve

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The identity gate on the image lane ([ServeImageStore]): **who is allowed to upload**.
 *
 * The lane hands out hosting on the operator's origin, so unlike the document drop-box it is never
 * anonymous — not even on a `--public` box, where every *browsing* surface is open. A caller must
 * present a GitHub credential that GitHub itself says has real access to the operator's repository.
 *
 * ## What counts as a credential
 *
 * Both kinds a headless caller actually holds:
 * - A **user token** — `gh auth token`, a PAT — verified as that user, against the same repo-access
 *   rule the playground applies.
 * - A **GitHub App installation token**, which is what `${'$'}{{ github.token }}` is inside a
 *   GitHub Actions job. There is no user behind one, so it is verified on the installation's own
 *   write permission on the gating repo and attributed to [GitHubOAuthVerifier.INSTALLATION_LOGIN].
 *
 * ## Why a bearer token and not the OAuth session
 *
 * [ServeGithubAuth] already gates the playground on a signed-in GitHub account, and reusing it here
 * would have been less code — but its credential is a **cookie minted by a browser redirect flow**,
 * and the entire audience for this lane is headless: an agent in a CI job or a cloud coding
 * session, holding a `GITHUB_TOKEN` or a `gh auth token`, running `curl`. There is no browser to
 * round-trip. So the credential is `Authorization: Bearer <github-token>`, verified live against
 * GitHub on the host's own outbound connection.
 *
 * That also means the lane needs **no OAuth app**: it never mints a token, it only checks one the
 * caller already has. `--accept-images` therefore works on a box with no `--github-auth-*` config
 * at all, given a repository to check access against.
 *
 * ## Which tokens, from whom
 *
 * A user token says who the user is, not who is presenting it: any OAuth app the user ever
 * authorized holds one that reads `GET /user` just as well. So when this server *does* have its own
 * OAuth app, a user token must have been issued to it (`POST /applications/{client_id}/token`), and
 * other kinds are accepted only as [ImageUploadTokenPolicy] (`--image-upload-tokens`) allows.
 *
 * ## What the token is used for, and what happens to it
 *
 * Two GitHub reads as the caller — who they are, and whether they have access to [repository] —
 * plus, for a user token on a host with its own OAuth app, one call as that app asking whether the
 * token is its own. The token is never stored, never logged, and never echoed back — the cache
 * below is keyed by its SHA-256, so a heap dump of a running server yields a hash, not a
 * credential. The access rule itself is [GitHubOAuthVerifier]'s, unchanged: write access on a
 * public repository (on which every GitHub user has read), any real grant on a private one.
 */
interface ServeImageUploadAuth {

  /** The repository a caller must have access to. Shown in the refusal, so it can be acted on. */
  val repository: String

  /** Decide who [bearerToken] is, or why it isn't good enough. */
  fun identify(bearerToken: String?): Identity

  sealed interface Identity {
    /**
     * Verified: [login] has access to [repository].
     *
     * [budgetKey] is who to charge for the upload, which is **not** always [login]. Every verified
     * installation token shares one placeholder login (there is no user behind one), so keying a
     * budget on that would put every GitHub App with write access to the gating repo in a single
     * bucket — one app's batch would 429 another's. The key is therefore the credential's own
     * fingerprint for those, and the login for a real user.
     */
    data class Ok(val login: String, val budgetKey: String = "gh:$login") : Identity

    /** No credential presented at all — answered `401`, with how to present one. */
    data object Missing : Identity

    /**
     * A credential was presented and is not good enough: unreadable by GitHub, or a real account
     * without access to the gating repository. [status] is what the route answers with, and
     * [reason] is safe to hand back — it never contains any part of the token.
     */
    data class Refused(val status: Int, val reason: String) : Identity
  }
}

/**
 * The real gate: verifies the presented token against GitHub, with a short-lived positive/negative
 * cache in front.
 *
 * The cache is not an optimisation so much as a rate control. Without it, every uploaded PNG in a
 * batch of twenty costs two GitHub API calls against the *caller's* rate limit, and an attacker
 * spraying random tokens gets the host to spend its outbound connections one-per-guess. With it, a
 * batch verifies once and a repeated bad token is refused locally.
 *
 * Positive entries are short ([POSITIVE_TTL_SECONDS]) because they cache an *authorisation* —
 * access revoked on GitHub must stop working here promptly, and the whole point of checking live is
 * that it does. Negative entries are shorter still, so a caller who fixes their token's scopes
 * isn't locked out of their own fix.
 */
class GithubTokenUploadAuth(
  override val repository: String,
  /** When non-empty, only these logins may upload, whatever GitHub says about repo access. */
  private val allowedUsers: Set<String> = emptySet(),
  /** Organizations whose members pass the same bar as a login in [allowedUsers]. */
  private val allowedOrgs: Set<String> = emptySet(),
  /** Which token kinds are accepted beyond [app]'s own; see [ImageUploadTokenPolicy]. */
  private val tokens: ImageUploadTokenPolicy = ImageUploadTokenPolicy.ANY,
  /** This server's OAuth app, when configured: a user token issued to it is always accepted. */
  private val app: GitHubOAuthApp? = null,
  /**
   * The GitHub round-trip, as a function so a test can stand in for it: identity + repo access for
   * a presented credential. Defaults to the real [GitHubOAuthVerifier], whose rule the playground
   * already shares.
   */
  private val verifier: (String, String, Set<String>) -> Result<GitHubOAuthUser> =
    GitHubOAuthVerifier().let { verifier ->
      { token, repository, users ->
        verifier.verifyAccessToken(token, repository, users, allowedOrgs, tokens, app)
      }
    },
  private val clock: () -> Long = System::currentTimeMillis,
) : ServeImageUploadAuth {

  private class Entry(val identity: ServeImageUploadAuth.Identity, val expiresAtMillis: Long)

  private val cache = ConcurrentHashMap<String, Entry>()

  override fun identify(bearerToken: String?): ServeImageUploadAuth.Identity {
    val token =
      bearerToken?.trim()?.takeIf { it.isNotEmpty() }
        ?: return ServeImageUploadAuth.Identity.Missing
    val key = fingerprint(token)
    val now = clock()
    cache[key]
      ?.takeIf { it.expiresAtMillis > now }
      ?.let {
        return it.identity
      }
    // Never cache under an unbounded key space: a token spray would otherwise grow the map by one
    // entry per guess. The ceiling is far above any real caller count on one host.
    if (cache.size >= MAX_CACHED_TOKENS) {
      cache.entries.removeIf { it.value.expiresAtMillis <= now }
      if (cache.size >= MAX_CACHED_TOKENS) cache.clear()
    }
    val identity = verify(token, key)
    val ttl =
      when {
        identity is ServeImageUploadAuth.Identity.Ok -> POSITIVE_TTL_SECONDS
        // GitHub didn't answer, which says nothing about the token: the next request asks again.
        identity is ServeImageUploadAuth.Identity.Refused && identity.status == UNAVAILABLE ->
          return identity
        else -> NEGATIVE_TTL_SECONDS
      }
    cache[key] = Entry(identity, now + ttl * 1000)
    return identity
  }

  private fun verify(token: String, fingerprint: String): ServeImageUploadAuth.Identity {
    val user =
      verifier(token, repository, allowedUsers).getOrElse { error ->
        // The message is GitHub's or ours about GitHub — a status code, a "not allowed" — and never
        // contains the credential, which is the only thing that must not travel back out.
        if (error is ImageUploadTokenRefusedException) {
          return ServeImageUploadAuth.Identity.Refused(
            status = 403,
            reason =
              "${error.message?.replaceFirstChar(Char::uppercase)}. Accepted here: " +
                "${tokens.describe(app != null)}. Otherwise ask this host for an agent access " +
                "grant carrying the images capability.",
          )
        }
        if (error is GitHubCheckUnavailableException) {
          return ServeImageUploadAuth.Identity.Refused(
            status = UNAVAILABLE,
            reason = "GitHub could not be asked about that token (${error.message}). Try again.",
          )
        }
        return ServeImageUploadAuth.Identity.Refused(
          status = 401,
          reason = "GitHub could not verify that token (${error.message ?: "unknown error"}).",
        )
      }
    if (!user.repositoryAccess) {
      return ServeImageUploadAuth.Identity.Refused(
        status = 403,
        reason =
          "GitHub user ${user.login} does not have access to $repository. " +
            "Uploading preview images is limited to that repository's collaborators.",
      )
    }
    // An installation has no login to charge, so it is charged as the credential it presented.
    // A GitHub Actions token rotates hourly, so its bucket rotates with it — which is the right
    // granularity anyway: one workflow run, one budget.
    val budgetKey =
      if (user.login == GitHubOAuthVerifier.INSTALLATION_LOGIN) "app:${fingerprint.take(16)}"
      else "gh:${user.login}"
    return ServeImageUploadAuth.Identity.Ok(user.login, budgetKey)
  }

  /** SHA-256, hex — a stable cache key that is not the credential it stands for. */
  private fun fingerprint(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") {
      "%02x".format(it)
    }

  companion object {
    /**
     * How long a verified identity is reused before GitHub is asked again — long enough that a
     * batch of uploads verifies once, short enough that a revoked token stops working here soon.
     */
    const val POSITIVE_TTL_SECONDS = 60L

    /**
     * How long a refusal from GitHub sticks — short, so fixing a token's scopes takes effect
     * quickly. A failure to reach GitHub at all is not cached.
     */
    const val NEGATIVE_TTL_SECONDS = 30L

    /** The status for "GitHub didn't answer": retryable, and never cached. */
    private const val UNAVAILABLE = 503

    private const val MAX_CACHED_TOKENS = 4096
  }
}

/**
 * This server's own GitHub OAuth (or GitHub App) client — the `--github-auth-client-*` pair — as
 * the image lane needs it: to ask GitHub whether a presented user token was issued to it (`POST
 * /applications/{client_id}/token`). [toString] leaves the secret out.
 */
class GitHubOAuthApp(val clientId: String, val clientSecret: String) {
  init {
    require(clientId.isNotBlank() && clientSecret.isNotBlank()) {
      "GitHub OAuth client id and secret are both required"
    }
  }

  override fun toString(): String = "GitHubOAuthApp(clientId=$clientId)"
}

/**
 * The kind of GitHub token a caller presented, read from its documented prefix. The prefix is part
 * of the credential — changing it makes the token invalid — so it only chooses which rule applies;
 * GitHub still decides whether the token is any good.
 */
enum class GitHubTokenKind {
  /** `ghp_` (classic) and `github_pat_` (fine-grained): minted by the user for themselves. */
  PERSONAL,

  /** `ghs_`: a GitHub App installation token, which is what `GITHUB_TOKEN` is in Actions. */
  INSTALLATION,

  /**
   * `gho_` / `ghu_`, and anything unprefixed: a user token some OAuth or GitHub App obtained on the
   * user's behalf — `gh auth token` is one of these, issued to the GitHub CLI.
   */
  APP_USER;

  companion object {
    fun of(token: String): GitHubTokenKind =
      when {
        token.startsWith("ghp_") || token.startsWith("github_pat_") -> PERSONAL
        token.startsWith("ghs_") -> INSTALLATION
        else -> APP_USER
      }
  }
}

/**
 * Which GitHub tokens the image lane accepts (`--image-upload-tokens`), beyond the one kind it
 * always accepts: a user token issued to **this server's own** OAuth app, when one is configured.
 *
 * - [personal] — personal access tokens.
 * - [otherApps] — user tokens issued to any other OAuth or GitHub App, `gh auth token` included.
 *   GitHub offers no way to tell *which* app holds such a token without that app's secret, so
 *   accepting them means accepting a token any app the user ever authorized could present.
 * - [installation] — GitHub App installation tokens with write on the repository. An installation
 *   token cannot name its app (`GET /app` needs the app's JWT), so this admits every app installed
 *   on the repository with write, not only GitHub Actions.
 */
data class ImageUploadTokenPolicy(
  val personal: Boolean,
  val otherApps: Boolean,
  val installation: Boolean,
) {
  /** The flag spelling of this policy, for the startup line and refusals. */
  fun describe(appConfigured: Boolean): String = buildList {
    if (appConfigured) add(APP)
    if (personal) add(PERSONAL)
    if (otherApps) add(OTHER_APPS)
    if (installation) add(INSTALLATION)
  }
    .joinToString(",")
    .ifEmpty { "none" }

  companion object {
    const val APP = "app"
    const val PERSONAL = "personal"
    const val OTHER_APPS = "other-apps"
    const val INSTALLATION = "installation"

    /** Everything — what the lane accepted before the policy existed. */
    val ANY = ImageUploadTokenPolicy(personal = true, otherApps = true, installation = true)

    /**
     * Parse `--image-upload-tokens`. Unset takes the default, which depends on whether this server
     * has its own OAuth app: with one, user tokens issued to other apps are refused (a user who
     * wants to upload signs in through an agent grant, or uses a personal access token); without
     * one there is nothing to recognise a user token by, so the lane keeps accepting them as it
     * always has. Installation tokens are accepted by default either way, because a GitHub Actions
     * job's `GITHUB_TOKEN` is the lane's documented CI credential.
     */
    fun parse(raw: String?, appConfigured: Boolean): ImageUploadTokenPolicy {
      val names =
        raw?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
          ?: return ImageUploadTokenPolicy(
            personal = true,
            otherApps = !appConfigured,
            installation = true,
          )
      val known = setOf(APP, PERSONAL, OTHER_APPS, INSTALLATION)
      val unknown = names.filterNot { it in known }
      require(unknown.isEmpty()) {
        "--image-upload-tokens: unknown kind ${unknown.joinToString()} " +
          "(expected any of ${known.joinToString(",")})"
      }
      require(names.isNotEmpty()) { "--image-upload-tokens needs at least one kind" }
      return ImageUploadTokenPolicy(
        personal = PERSONAL in names,
        otherApps = OTHER_APPS in names,
        installation = INSTALLATION in names,
      )
    }
  }
}

/**
 * GitHub could not give an answer about a token — unreachable, rate limited, a 5xx, or rejecting
 * this server's own client credentials. Not a verdict on the token, so it is never cached.
 */
class GitHubCheckUnavailableException(message: String) : IllegalStateException(message)

/**
 * The token is a kind this host's [ImageUploadTokenPolicy] does not accept. A verdict, not an
 * outage — refused with `403` and the reason, which names no part of the token.
 */
class ImageUploadTokenRefusedException(message: String) : IllegalStateException(message)
