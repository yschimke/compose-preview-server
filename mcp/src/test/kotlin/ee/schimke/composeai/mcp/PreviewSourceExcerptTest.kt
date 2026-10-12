package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreviewSourceExcerptTest {
  // wear-os-samples' WearWidget/WeatherWidget.kt (imports dropped), which
  // yschimke/compose-agent-plugins#149 reviewed: thin expression-body previews below the widget
  // they show, with WeatherCondition declared in another file.
  private val weather =
    """
    package com.google.example.wear_widget

    private val ColorSunny = Color(0xFF2196F3)
    private val ColorCloudy = Color(0xFF9E9E9E)

    @AssociateWithGlanceWearWidget(WeatherWidget::class)
    class WeatherWidgetService : GlanceWearWidgetService() {
        override val widget: GlanceWearWidget = WeatherWidget()
    }

    class WeatherWidget : GlanceWearWidget() {
        override suspend fun provideWidgetData(
            context: Context,
            params: WearWidgetParams,
        ): WearWidgetData {
            val brush = WearWidgetBrush.color(ColorSunny.rc)
            return WearWidgetDocument(background = brush) {
                WeatherContent("Sunny", "London", Color.White.rc)
            }
        }
    }

    @RemoteComposable
    @Composable
    fun WeatherContent(weatherText: String, location: String, textColor: RemoteColor) {
        RemoteBox(modifier = RemoteModifier.fillMaxSize(), contentAlignment = RemoteAlignment.Center) {
            RemoteText(text = weatherText.rs, color = textColor, fontSize = 36.rsp)
        }
    }

    class MockWeatherWidget(private val temp: Int, private val condition: WeatherCondition) :
        GlanceWearWidget() {
        override suspend fun provideWidgetData(
            context: Context,
            params: WearWidgetParams,
        ): WearWidgetData {
            val bgColor =
                when (condition) {
                    WeatherCondition.SUNNY -> ColorSunny
                    WeatherCondition.CLOUDY -> ColorCloudy
                }
            val brush = WearWidgetBrush.color(bgColor.rc)
            return WearWidgetDocument(background = brush) {
                WeatherContent("75", "London", Color.White.rc)
            }
        }
    }

    @Preview(name = "Squircle Preview", device = "spec:width=1000dp,height=1000dp,dpi=320")
    @Composable
    fun WeatherWidgetSquirclePreview(
        @PreviewParameter(SquircleAllWidgetPreviewParams::class) params: WearWidgetParams
    ) = WearWidgetPreview(MockWeatherWidget(75, WeatherCondition.SUNNY), params)

    @Preview(name = "Round Preview", device = "spec:width=1000dp,height=1000dp,dpi=320")
    @Composable
    fun WeatherWidgetRoundPreview(
        @PreviewParameter(RoundAllWidgetPreviewParams::class) params: WearWidgetParams
    ) = WearWidgetPreview(MockWeatherWidget(75, WeatherCondition.SUNNY), params)
    """
      .trimIndent()
      .lines()

  private fun lineOf(text: String) = weather.indexOfFirst { it.startsWith(text) } + 1

  @Test
  fun `an expression-body preview ends with its expression, not at the end of the file`() {
    val excerpt = PreviewSourceExcerpt.of(weather, lineOf("@Preview(name = \"Squircle"))!!
    assertThat(excerpt).contains("fun WeatherWidgetSquirclePreview(")
    assertThat(excerpt).doesNotContain("fun WeatherWidgetRoundPreview(")
  }

  @Test
  fun `the declarations a thin preview reaches by name come with it, in file order`() {
    val excerpt = PreviewSourceExcerpt.of(weather, lineOf("@Preview(name = \"Round"))!!
    // MockWeatherWidget names the colours and WeatherContent; the real widget, its service and
    // the other preview are never reached, and WeatherCondition lives in another file.
    assertThat(excerpt).contains("class MockWeatherWidget(")
    assertThat(excerpt).contains("WearWidgetDocument(background = brush)")
    assertThat(excerpt).contains("RemoteBox(modifier = RemoteModifier.fillMaxSize()")
    assertThat(excerpt).contains("private val ColorSunny")
    assertThat(excerpt).doesNotContain("class WeatherWidget :")
    assertThat(excerpt).doesNotContain("WeatherWidgetService")
    assertThat(excerpt).doesNotContain("fun WeatherWidgetSquirclePreview(")
    assertThat(excerpt.indexOf("fun WeatherWidgetRoundPreview("))
      .isLessThan(excerpt.indexOf("private val ColorSunny"))
    assertThat(excerpt.indexOf("fun WeatherContent("))
      .isLessThan(excerpt.indexOf("class MockWeatherWidget("))
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
    val excerpt =
      PreviewSourceExcerpt.of(weather, lineOf("@Preview(name = \"Round"), maxLines = 8)!!
    assertThat(excerpt).contains("fun WeatherWidgetRoundPreview(")
    assertThat(excerpt.lines().filter { it.isNotEmpty() }.size).isAtMost(8)
  }

  @Test
  fun `an unknown line has no excerpt`() {
    assertThat(PreviewSourceExcerpt.of(weather, null)).isNull()
    assertThat(PreviewSourceExcerpt.of(emptyList(), 3)).isNull()
  }
}
