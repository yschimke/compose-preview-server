package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServeAnalyticsTest {
  private val website = "e676c9b4-11e4-4ef1-a4d7-87001773e9f2"

  @Test
  fun `analytics is explicitly configured and rejects invalid endpoints`() {
    assertEquals("", ServeAnalytics.scriptTag(emptyMap()))
    for (url in
      listOf(
        "javascript:alert(1)",
        "http://example.com",
        "https://user:pass@example.com",
        "https://example.com?token=secret",
      )) {
      assertEquals(
        "",
        ServeAnalytics.scriptTag(
          mapOf("SERVE_UMAMI_URL" to url, "SERVE_UMAMI_WEBSITE_ID" to website)
        ),
      )
    }
    val tag =
      ServeAnalytics.scriptTag(
        mapOf(
          "SERVE_UMAMI_URL" to "https://preview.test/__analytics/",
          "SERVE_UMAMI_WEBSITE_ID" to website,
        )
      )
    assertTrue(tag.contains("analytics.js"))
    assertTrue(tag.contains("data-umami-url=\"https://preview.test/__analytics\""))
    assertTrue(tag.contains("data-website-id=\"$website\""))
  }
}
