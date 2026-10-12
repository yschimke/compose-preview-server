package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreviewSourceExcerptTest {
  // The shape yschimke/compose-agent-plugins#149 met in wear-os-samples' WearWidget: a thin,
  // expression-body preview below the widget it shows.
  private val weather =
    """
    package com.example.widget

    class WeatherWidget : GlanceWearWidget() {
        override suspend fun provideWidgetData(
            context: Context,
            params: WearWidgetParams,
        ): WearWidgetData {
            val brush = Brush.verticalGradient(listOf(Color.Blue, Color.Black))
            return WearWidgetDocument(background = brush) {
                WeatherContent("Sunny", "London", Color.White.rc)
            }
        }
    }

    class MockWeatherWidget(val temperature: Int, val condition: WeatherCondition) : WeatherWidget()

    enum class WeatherCondition { SUNNY, RAINY }

    // Never called by the preview.
    @Composable
    fun Unrelated() {
        Text("not part of the widget")
    }

    @RemoteComposable
    @Composable
    fun WeatherContent(weatherText: String, location: String, textColor: RemoteColor) {
        RemoteBox(modifier = RemoteModifier.fillMaxSize(), contentAlignment = RemoteAlignment.Center) {
            RemoteText(weatherText, color = textColor)
        }
    }

    @Preview(
        name = "Round Preview",
        device = "spec:width=1000dp,height=1000dp,dpi=320",
    )
    @Composable
    fun WeatherWidgetRoundPreview(
        @PreviewParameter(RoundAllWidgetPreviewParams::class) params: WearWidgetParams
    ) = WearWidgetPreview(MockWeatherWidget(75, WeatherCondition.SUNNY), params)

    @Preview(name = "Squircle Preview")
    @Composable
    fun WeatherWidgetSquirclePreview(
        @PreviewParameter(SquircleAllWidgetPreviewParams::class) params: WearWidgetParams
    ) = WearWidgetPreview(MockWeatherWidget(60, WeatherCondition.RAINY), params)
    """
      .trimIndent()
      .lines()

  private fun lineOf(text: String) = weather.indexOfFirst { it.startsWith(text) } + 1

  @Test
  fun `an expression-body preview ends with its expression, not at the end of the file`() {
    val excerpt = PreviewSourceExcerpt.of(weather, lineOf("@Preview("))!!
    assertThat(excerpt).contains("fun WeatherWidgetRoundPreview(")
    assertThat(excerpt).doesNotContain("fun WeatherWidgetSquirclePreview(")
  }

  @Test
  fun `the declarations a thin preview reaches by name come with it, in file order`() {
    val excerpt = PreviewSourceExcerpt.of(weather, lineOf("@Preview("))!!
    // MockWeatherWidget names WeatherWidget, whose document names WeatherContent.
    assertThat(excerpt).contains("class MockWeatherWidget(")
    assertThat(excerpt).contains("WearWidgetDocument(background = brush)")
    assertThat(excerpt).contains("RemoteBox(modifier = RemoteModifier.fillMaxSize()")
    assertThat(excerpt).contains("enum class WeatherCondition")
    assertThat(excerpt).doesNotContain("fun Unrelated()")
    assertThat(excerpt.indexOf("fun WeatherWidgetRoundPreview("))
      .isLessThan(excerpt.indexOf("class WeatherWidget :"))
    assertThat(excerpt.indexOf("class WeatherWidget :"))
      .isLessThan(excerpt.indexOf("fun WeatherContent("))
  }

  @Test
  fun `a block-body preview ends at its closing brace`() {
    val lines =
      """
      @Preview
      @Composable
      fun ButtonPreview() {
          Button(onClick = {}) { Text("Go {") }
      }

      @Preview
      @Composable
      fun OtherPreview() {}
      """
        .trimIndent()
        .lines()
    val excerpt = PreviewSourceExcerpt.of(lines, 1)!!
    assertThat(excerpt.lines()).hasSize(5)
    assertThat(excerpt).doesNotContain("OtherPreview")
  }

  @Test
  fun `the line cap bounds the preview and the declarations added after it`() {
    val excerpt = PreviewSourceExcerpt.of(weather, lineOf("@Preview("), maxLines = 8)!!
    assertThat(excerpt).contains("fun WeatherWidgetRoundPreview(")
    assertThat(excerpt.lines().filter { it.isNotEmpty() }.size).isAtMost(8)
  }

  @Test
  fun `an unknown line has no excerpt`() {
    assertThat(PreviewSourceExcerpt.of(weather, null)).isNull()
    assertThat(PreviewSourceExcerpt.of(emptyList(), 3)).isNull()
  }
}
