package ee.schimke.composeai.cli.serve

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CachingDocumentCompilerTest {
  @Test
  fun `the same content is compiled once and different content again`() {
    val calls = mutableListOf<String>()
    val compiler =
      CachingDocumentCompiler(
        { content ->
          calls += content
          content.toByteArray()
        },
      )

    assertContentEquals("a".toByteArray(), compiler.compile("a"))
    assertContentEquals("a".toByteArray(), compiler.compile("a"))
    compiler.compile("b")

    assertEquals(listOf("a", "b"), calls)
  }

  @Test
  fun `a failure is not kept, so the next request asks again`() {
    var fail = true
    val calls = AtomicInteger()
    val compiler =
      CachingDocumentCompiler(
        {
          calls.incrementAndGet()
          if (fail) throw IllegalStateException("Kobento refused this server's token (HTTP 401)")
          byteArrayOf(1)
        },
      )

    assertFailsWith<IllegalStateException> { compiler.compile("a") }
    fail = false
    assertContentEquals(byteArrayOf(1), compiler.compile("a"))
    assertEquals(2, calls.get())
  }

  @Test
  fun `a refusal of the content is kept like an answer, until it expires`() {
    var now = 0L
    val calls = AtomicInteger()
    val compiler =
      CachingDocumentCompiler(
        {
          calls.incrementAndGet()
          throw IllegalArgumentException("Kobento convert refused the design: give a chord")
        },
        ttlMillis = 100,
        clock = { now },
      )

    repeat(3) {
      val refused = assertFailsWith<IllegalArgumentException> { compiler.compile("piano") }
      assertEquals("Kobento convert refused the design: give a chord", refused.message)
    }
    assertEquals(1, calls.get())
    now = 100
    assertFailsWith<IllegalArgumentException> { compiler.compile("piano") }
    assertEquals(2, calls.get())
  }

  @Test
  fun `an entry expires and the least recently used leaves first`() {
    var now = 0L
    val calls = mutableListOf<String>()
    val compiler =
      CachingDocumentCompiler(
        { content ->
          calls += content
          byteArrayOf()
        },
        maxEntries = 2,
        ttlMillis = 1_000,
        clock = { now },
      )

    compiler.compile("a")
    compiler.compile("b")
    compiler.compile("a") // a is now the more recently used
    compiler.compile("c") // evicts b
    compiler.compile("a") // still cached
    compiler.compile("b") // compiled again
    now = 5_000
    compiler.compile("a") // expired

    assertEquals(listOf("a", "b", "c", "b", "a"), calls)
  }

  @Test
  fun `concurrent requests for the same content share one call`() {
    val calls = AtomicInteger()
    val release = CountDownLatch(1)
    val compiler =
      CachingDocumentCompiler(
        {
          calls.incrementAndGet()
          release.await(5, TimeUnit.SECONDS)
          byteArrayOf(7)
        },
      )
    val pool = Executors.newFixedThreadPool(8)
    try {
      val results = (1..8).map { pool.submit<ByteArray> { compiler.compile("same") } }
      Thread.sleep(200)
      release.countDown()
      results.forEach { assertContentEquals(byteArrayOf(7), it.get(5, TimeUnit.SECONDS)) }
    } finally {
      pool.shutdownNow()
    }
    assertEquals(1, calls.get())
  }

  @Test
  fun `a request that misses just before another caches the result does not compile again`() {
    val calls = AtomicInteger()
    lateinit var compiler: CachingDocumentCompiler
    var raced = false
    compiler =
      CachingDocumentCompiler(
        {
          calls.incrementAndGet()
          byteArrayOf(9)
        },
        afterMiss = {
          // The first request has missed and not yet taken the slot: another one runs to the end.
          if (!raced) {
            raced = true
            val other = Thread { compiler.compile("same") }
            other.start()
            other.join()
          }
        },
      )

    assertContentEquals(byteArrayOf(9), compiler.compile("same"))
    assertEquals(1, calls.get())
  }
}
