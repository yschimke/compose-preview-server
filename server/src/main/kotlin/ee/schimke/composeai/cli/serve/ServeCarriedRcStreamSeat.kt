package ee.schimke.composeai.cli.serve

/**
 * The live seat one carried-document socket ([ServeCarriedRcLane]) is charged. Its stream is paid
 * for exactly once, by one of:
 * - its own [ticket], held while the lane is the replay (or waiting for a held session);
 * - the player daemon it handed that ticket to ([take]), whose start it became part of; or
 * - the daemon whose held session the lane adopted ([onHeld]): the daemon's seat stands for that
 *   session, so the socket's own ticket is returned.
 *
 * A socket admitted without a ticket because its player daemon already holds a seat must still pay
 * when it does not get the held session — the daemon holds one at a time, so a second viewer's lane
 * is the replay. [ensureCharged] takes a ticket then, and a socket that cannot have one is closed
 * at capacity like any other.
 */
class ServeCarriedRcStreamSeat(
  private val seats: LiveSeatLimiter,
  private val weight: Int,
) : ServeRcPlayerDaemon.SeatHandOver, AutoCloseable {

  private val lock = Any()
  private var ticket: LiveSeatLimiter.Ticket? = null
  /** The daemon this socket's ticket went into, which pays for the stream while it holds a seat. */
  private var coveredBy: ServeRcPlayerDaemon? = null
  private var held = false
  private var closed = false

  /**
   * Take a ticket if none is held. [countRefusal] as [LiveSeatLimiter.acquire]: only the attempt
   * that turns a viewer away should count.
   */
  fun acquire(countRefusal: Boolean = false): Boolean =
    synchronized(lock) {
      if (closed) return false
      if (ticket != null) return true
      ticket = seats.acquire(weight, verified = true, countRefusal = countRefusal)
      ticket != null
    }

  /** Whether the socket currently holds a ticket of its own. */
  val holdsTicket: Boolean
    get() = synchronized(lock) { ticket != null }

  override val permits: Int
    get() = synchronized(lock) { ticket?.permits ?: 0 }

  override fun take(daemon: ServeRcPlayerDaemon): LiveSeatLimiter.Ticket? =
    synchronized(lock) {
      val handed = ticket ?: return null
      ticket = null
      coveredBy = daemon
      handed
    }

  /** The lane adopted a held session; the daemon's seat pays for it from now on. */
  fun onHeld() {
    val released =
      synchronized(lock) {
        held = true
        ticket.also { ticket = null }
      }
    released?.close()
  }

  /**
   * The lane is (or will be) the replay: make sure the stream is paid for. True when it is — by its
   * own ticket, by a held session, or by the daemon its ticket went into while that daemon still
   * holds its seat — otherwise a new ticket is tried, and false means none was free.
   *
   * [startingFor] is the daemon the lane is waiting on to start (it answered `Warming`). Its seat
   * stands for the session about to open, which this socket may well get — a viewer reloading onto
   * a start the previous page began — so the wait is not charged again. If the session goes to
   * someone else the lane's next attempt is `Busy`, and is charged then.
   */
  fun ensureCharged(startingFor: ServeRcPlayerDaemon? = null): Boolean {
    val daemon =
      synchronized(lock) {
        if (closed || held || ticket != null) return true
        coveredBy
      }
    // Asked outside [lock]: the daemon calls [take] under its own lock, so holding ours while
    // taking its would invert the order.
    if (daemon?.holdsSeat == true) return true
    if (startingFor?.holdsSeat == true) return true
    return synchronized(lock) {
      if (closed || held || ticket != null) return true
      coveredBy = null
      ticket = seats.acquire(weight, verified = true)
      ticket != null
    }
  }

  override fun close() {
    val released =
      synchronized(lock) {
        closed = true
        ticket.also { ticket = null }
      }
    released?.close()
  }
}
