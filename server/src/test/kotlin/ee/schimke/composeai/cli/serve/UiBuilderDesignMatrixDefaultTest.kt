package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals

class UiBuilderDesignMatrixDefaultTest {
  @Test
  fun `a watch face defaults to the round Wear screens, not phone foldable and tablet`() {
    assertEquals(
      UiBuilderDesignMatrix.FORM_WEAR,
      UiBuilderDesignMatrix.defaultFormFactor("watchface"),
    )
    assertEquals(
      listOf("wear_small_round", "wear_large_round"),
      UiBuilderDesignMatrix.FORM_FACTOR_DEFAULTS[
          UiBuilderDesignMatrix.defaultFormFactor("watchface")],
    )
  }

  @Test
  fun `other platforms keep their defaults`() {
    assertEquals(UiBuilderDesignMatrix.FORM_WEAR, UiBuilderDesignMatrix.defaultFormFactor("wear"))
    assertEquals(UiBuilderDesignMatrix.FORM_TV, UiBuilderDesignMatrix.defaultFormFactor("tv"))
    assertEquals(
      UiBuilderDesignMatrix.FORM_ADAPTIVE,
      UiBuilderDesignMatrix.defaultFormFactor("android"),
    )
    assertEquals(UiBuilderDesignMatrix.FORM_ADAPTIVE, UiBuilderDesignMatrix.defaultFormFactor(null))
  }
}
