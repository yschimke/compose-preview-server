package ee.schimke.composeai.cli.serve

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The identity gate on the image lane ([ServeImageStore]): **who is allowed to upload**. Never
 * anonymous, not even on a `--public` box, because it hands out hosting on the operator's origin.
 *
 * The credential is `Authorization: Bearer <github-token>`, because the audience is headless (CI
 * jobs, agents running `curl`) and cannot do [ServeGithubAuth]'s browser cookie flow; no OAuth app
 * is needed. Accepted:
 * - a **user token** (`gh auth token`, a PAT), verified as that user against
 *   [GitHubOAuthVerifier]'s repo-access rule;
 * - a **GitHub App installation token** (`${'$'}{{ github.token }}`), verified on the
 *   installation's write permission and attributed to [GitHubOAuthVerifier.INSTALLATION_LOGIN].
 *
 * When this server has its own OAuth app, a user token must have been issued to it; other kinds are
 * accepted only as [ImageUploadTokenPolicy] (`--image-upload-tokens`) allows. The token is never
 * stored, logged or echoed; the cache below is keyed by its SHA-256.
 */
interface ServeImageUploadAuth {

  /** The repository a caller must have access to. Shown in the refusal, so it can be acted on. */
  val repository: String

  /** Decide who [bearerToken] is, or why it isn't good enough. */
  fun identify(bearerToken: String?): Identity

  sealed interface Identity {
    /**
     * Verified: [login] has access to [repository]. [budgetKey] is who to charge: the login for a
     * real user, but the credential's fingerprint for installation tokens, which all share one
     * placeholder login and would otherwise share one bucket.
     */
    data class Ok(val login: String, val budgetKey: String = "gh:$login") : Identity

    /** No credential presented at all — answered `401`, with how to present one. */
    data object Missing : Identity

    /**
     * A credential was presented but isn't good enough (unreadable by GitHub, or no access to the
     * gating repo). [status] is the route's answer; [reason] never contains any part of the token.
     */
    data class Refused(val status: Int, val reason: String) : Identity
  }
}

/**
 * The real gate: verifies the token against GitHub behind a short-lived positive/negative cache.
 * The cache is rate control: a batch verifies once rather than spending two API calls per PNG, and
 * repeated bad tokens are refused locally. Positive entries are short ([POSITIVE_TTL_SECONDS]) so
 * revoked access stops promptly; negative entries shorter still so a fixed token works quickly.
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
   * The GitHub round trip as a function (stubbable in tests); defaults to [GitHubOAuthVerifier],
   * sharing the playground's rule.
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
    // An installation has no login, so it is charged as its credential; an Actions token rotates
    // hourly, giving one budget per workflow run.
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
     * How long a verified identity is reused: a batch verifies once, and revocations take effect
     * soon.
     */
    const val POSITIVE_TTL_SECONDS = 60L

    /**
     * How long a refusal is cached: short, so fixing a token's scopes takes effect quickly.
     * Failures to reach GitHub aren't cached.
     */
    const val NEGATIVE_TTL_SECONDS = 30L

    /** The status for "GitHub didn't answer": retryable, and never cached. */
    private const val UNAVAILABLE = 503

    private const val MAX_CACHED_TOKENS = 4096
  }
}

/**
 * This server's own GitHub OAuth (or App) client (`--github-auth-client-*`), used to ask whether a
 * user token was issued to it (`POST /applications/{client_id}/token`). [toString] omits the
 * secret.
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
 * The kind of GitHub token presented, from its documented prefix. The prefix only selects which
 * rule applies; GitHub still validates the token.
 */
enum class GitHubTokenKind {
  /** `ghp_` (classic) and `github_pat_` (fine-grained): minted by the user for themselves. */
  PERSONAL,

  /** `ghs_`: a GitHub App installation token, which is what `GITHUB_TOKEN` is in Actions. */
  INSTALLATION,

  /**
   * `gho_` / `ghu_` and unprefixed tokens: user tokens an OAuth or GitHub App obtained (e.g. `gh
   * auth token`).
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
 * Which GitHub tokens the image lane accepts (`--image-upload-tokens`) beyond user tokens issued to
 * this server's own OAuth app:
 * - [personal]: personal access tokens.
 * - [otherApps]: user tokens issued to any other app (`gh auth token` included); GitHub can't say
 *   which app without its secret.
 * - [installation]: App installation tokens with write; these can't name their app, so this admits
 *   any app installed with write.
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
     * Parse `--image-upload-tokens`. The unset default depends on whether this server has its own
     * OAuth app: with one, other apps' user tokens are refused; without one they're accepted as
     * before. Installation tokens are accepted by default, since `GITHUB_TOKEN` is the documented
     * CI credential.
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
 * GitHub gave no answer about a token (unreachable, rate limited, 5xx, or rejecting our client
 * credentials). Not a verdict, so never cached.
 */
class GitHubCheckUnavailableException(message: String) : IllegalStateException(message)

/**
 * The token is a kind [ImageUploadTokenPolicy] refuses: a verdict, answered `403` with a reason
 * naming no part of the token.
 */
class ImageUploadTokenRefusedException(message: String) : IllegalStateException(message)
