package ee.schimke.composeai.cli.serve

/**
 * The startup banner `serve` prints to stderr, as lines, so what it says about the browse token can
 * be checked without starting a server.
 *
 * **A token the operator supplied (`--token`, `SERVE_TOKEN`) is shown as a short prefix, never in
 * full.** The operator already holds it, and on a deployment stderr is the container log, which is
 * kept, shipped and read by more people than the token is meant for. The prefix is enough to tell
 * which token a box is running with.
 *
 * **A token `serve` generated itself is shown in full.** It exists nowhere else — not in a flag, a
 * file or the environment — so the banner link (and the `--open` browser, which is handed the same
 * URL) is the only way in; it dies with the process.
 *
 * Nothing parses these lines. They are for a person at a terminal.
 */
internal object ServeBanner {
  /** How many leading characters of a supplied token the banner shows. */
  const val TOKEN_PREFIX_CHARS: Int = 4

  fun lines(
    moduleLabel: String,
    localOrigin: String,
    /** One origin per site-local address when bound to every interface, else null. */
    networkOrigins: List<String>?,
    token: String,
    tokenSupplied: Boolean,
    public: Boolean,
    previewCount: Int,
    /** The builder page to point at, when the UI builder is a lane this run opens; else null. */
    builderPath: String?,
    acceptDocs: Boolean,
  ): List<String> = buildList {
    // Public mode is open, so the links carry no token; otherwise the token gates every route.
    fun url(origin: String, path: String): String =
      when {
        public -> "$origin$path"
        tokenSupplied -> "$origin$path?token=${redact(token)}"
        else -> ServeUrls.pathUrl(origin, path, token)
      }

    add("compose-preview serve — module $moduleLabel")
    if (public) add("  ⚠ Public mode — every route is open (no token required).")
    add("  Local:   ${url(localOrigin, "/")}")
    if (networkOrigins != null) {
      if (networkOrigins.isEmpty()) add("  Network: (no site-local IPv4 address found)")
      networkOrigins.forEach { add("  Network: ${url(it, "/")}") }
      add(
        "  ⚠ Bound to all interfaces — reachable by anyone on your LAN. The token in the link is " +
          "the only gate; share it only with people you'd let see these previews."
      )
    }
    if (!public && tokenSupplied) {
      add("  Token:   the value passed with --token (SERVE_TOKEN), shown above as a prefix only")
    }
    add("  Previews: $previewCount")
    if (builderPath != null) add("  Builder: ${url(localOrigin, builderPath)}")
    if (acceptDocs) {
      add("  Documents: ${url(localOrigin, "/docs")} (drop a .rc / Lottie, get an expiring link)")
    }
    add("  Press Ctrl-C to stop.")
  }

  /**
   * [token] shortened for display: its first [TOKEN_PREFIX_CHARS] characters and an ellipsis, or
   * just the ellipsis when the token is too short for a prefix to leave most of it unsaid.
   */
  fun redact(token: String): String =
    if (token.length < TOKEN_PREFIX_CHARS * 3) "…" else token.take(TOKEN_PREFIX_CHARS) + "…"
}
