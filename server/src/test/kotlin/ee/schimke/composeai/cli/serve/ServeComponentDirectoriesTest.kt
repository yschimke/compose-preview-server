package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The component subtree's directories: named groups under a component that are not its renders
 * (recorded interactions, catalogs about it). Variants stay a flat list; anything else hangs under
 * a named head with a count. Which directories a page gets is
 * [ServeHttpServer.componentRelatedDirectories]'s concern, not asserted here.
 */
class ServeComponentDirectoriesTest {

  private val token = "t"

  private fun preview(
    id: String,
    label: String,
    state: String? = null,
    motion: List<ServeMotion> = emptyList(),
  ) = ServePreview(id, label, state = state, motion = motion)

  private val default = preview("button__ideal__default__light", "Button")
  private val pressed =
    preview("button__ideal__pressed__light", "Button · Pressed", state = "pressed")

  /**
   * A component with [n] baked states; the tree only lists variant rows from three, as in
   * [ServeViewerDisclosuresTest].
   */
  private fun states(n: Int): List<ServePreview> =
    (0 until n).map { i ->
      val state = if (i == 0) "default" else "state-$i"
      preview("button__ideal__${state}__light", "Button · $state", state = state)
    }

  private fun viewer(
    current: ServePreview,
    siblings: List<ServePreview>,
    directories: List<ServeWeb.ComponentDirectory> = emptyList(),
    componentBrowser: Boolean = false,
  ) =
    ServeWeb.viewerPage(
      current,
      token,
      siblings = siblings,
      componentDirectories = directories,
      componentBrowser = componentBrowser,
    )

  private fun tree(html: String) =
    html.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")

  private val samples =
    ServeWeb.ComponentDirectory(
      "related",
      "Samples",
      listOf(
        ServeWeb.ComponentDirectoryRow("ButtonSample", "/m3-samples/p/button-sample"),
        ServeWeb.ComponentDirectoryRow(
          "ButtonWithIconSample",
          "/m3-samples/p/button-icon-sample",
          title = "call sites",
        ),
      ),
    )

  @Test
  fun `a directory is a named head with a count, and its rows link out`() {
    val subtree = tree(viewer(default, listOf(default, pressed), listOf(samples)))
    assertTrue(subtree.contains("cp-tree-dir--related"), subtree)
    assertTrue(subtree.contains(">Samples</span><span class=\"cp-tree-count\">2</span>"), subtree)
    assertTrue(subtree.contains("href=\"/m3-samples/p/button-sample\""), subtree)
    // The catalog's own wording for the relationship rides on the row it is not already showing.
    assertTrue(subtree.contains("title=\"call sites\""), subtree)
  }

  @Test
  fun `a directory does not inflate the component's render count`() {
    // The component row's count is renders only; recordings and samples are not renders of it.
    val renders = states(3)
    val without = tree(viewer(renders.first(), renders))
    val with = tree(viewer(renders.first(), renders, listOf(samples)))
    val count = "class=\"cp-tree-count\">3</span></a>"
    assertTrue(without.contains(count), without)
    assertTrue(with.contains(count), with)
    // …and the variant rows are still the flat list they were, with the directory after them.
    assertTrue(with.indexOf("cp-tree-variant") < with.indexOf("cp-tree-dir--related"), with)
  }

  @Test
  fun `the subtree is drawn for a component that has no second render but has a directory`() {
    // A single-node tree normally renders nothing, but with a directory it has something to show.
    val alone = viewer(default, listOf(default))
    assertFalse(alone.contains("cp-axes-tree"), "no variants and no directories ⇒ no subtree")
    val withSamples = viewer(default, listOf(default), listOf(samples))
    assertTrue(withSamples.contains("cp-axes-tree"), withSamples)
    assertTrue(tree(withSamples).contains("href=\"/m3-samples/p/button-sample\""))
  }

  @Test
  fun `a row whose destination has no host yet is disabled rather than dropped`() {
    val loading =
      ServeWeb.ComponentDirectory(
        "related",
        "Samples",
        listOf(ServeWeb.ComponentDirectoryRow("ButtonSample", "/m3-samples/p/x", live = false)),
      )
    val subtree = tree(viewer(default, listOf(default, pressed), listOf(loading)))
    assertTrue(subtree.contains("cp-tree-dead"), subtree)
    assertTrue(subtree.contains("aria-disabled=\"true\""), subtree)
    // Not a link: following it now would land on a catalog that is still loading.
    assertFalse(subtree.contains("href=\"/m3-samples/p/x\""), subtree)
  }

  @Test
  fun `the component browser offers no directories`() {
    // That chrome is a reading surface and every route out of it is one it does not offer — the
    // same rule its comparison chips already follow.
    val html = viewer(default, listOf(default, pressed), listOf(samples), componentBrowser = true)
    assertFalse(html.contains("cp-tree-dir--related"), html)
  }

  @Test
  fun `a capture on a listed variant is reachable from the component's own page`() {
    // A capture belongs to the preview that took it, so the directory reaches across the renders
    // the tree lists (not every render, which produced dozens of rows on real components).
    val renders = states(3)
    val recorded =
      renders[1].copy(motion = listOf(ServeMotion("press", caption = "Press and release")))
    val subtree = tree(viewer(renders.first(), listOf(renders[0], recorded, renders[2])))
    assertTrue(subtree.contains("cp-tree-dir--motion"), subtree)
    assertTrue(subtree.contains(">Motion</span><span class=\"cp-tree-count\">1</span>"), subtree)
    // Escaped, because it is an href in HTML and the query already carries the link token.
    assertTrue(
      subtree.contains("/p/${recorded.id}?token=t&amp;mode=motion&amp;motion=press"),
      subtree,
    )
  }

  @Test
  fun `captures spread across renders are named by the render, not by the capture`() {
    // A caption-less capture is "Animation" in every render's set, so across renders the row must
    // name the render.
    val renders =
      states(3).map { p -> p.copy(motion = listOf(ServeMotion("m-${p.id}", kind = "animation"))) }
    val subtree = tree(viewer(renders.first(), renders))
    val labels =
      Regex("cp-tree-dir--motion.*?</ul>", RegexOption.DOT_MATCHES_ALL).find(subtree)!!.value.let {
        Regex(">([^<>]+)</a>").findAll(it).map { m -> m.groupValues[1] }.toList()
      }
    assertEquals(3, labels.size, subtree)
    assertEquals(labels.size, labels.distinct().size, "every row names a different render: $labels")
    assertFalse(labels.all { it == labels.first() }, labels.toString())
  }

  @Test
  fun `captures on one render keep their own titles`() {
    // With nothing else varying, the capture's title is the informative label.
    val recorded =
      preview(
        "button__ideal__default__light",
        "Button",
        motion =
          listOf(
            ServeMotion("press", caption = "Press and release"),
            ServeMotion("hold", caption = "Press and hold"),
          ),
      )
    val subtree = tree(viewer(recorded, listOf(recorded, pressed)))
    assertTrue(subtree.contains(">Press and release</a>"), subtree)
    assertTrue(subtree.contains(">Press and hold</a>"), subtree)
  }

  @Test
  fun `the motion directory follows the tree's renders, not every render of the component`() {
    // Renders the tree doesn't list contribute no rows, except the one on screen.
    val listed = states(2)
    val unlisted =
      preview(
        "button__ideal__default__light__fontscale-2",
        "Button · Large font",
        motion = listOf(ServeMotion("unlisted", caption = "Never listed")),
      )
    val subtree = tree(viewer(listed.first(), listed + unlisted))
    assertFalse(subtree.contains("Never listed"), subtree)
    assertFalse(subtree.contains("cp-tree-dir--motion"), subtree)
  }

  @Test
  fun `a component with no recordings has no motion directory`() {
    assertFalse(tree(viewer(default, listOf(default, pressed))).contains("cp-tree-dir--motion"))
  }

  @Test
  fun `motion leads the related directories`() {
    // Nearest first: a recording is this catalog's own artifact about the render on screen, while a
    // related row leaves for another catalog entirely.
    val recorded =
      preview(
        "button__ideal__default__light",
        "Button",
        motion = listOf(ServeMotion("press", caption = "Press and release")),
      )
    val subtree = tree(viewer(recorded, listOf(recorded, pressed), listOf(samples)))
    assertTrue(
      subtree.indexOf("cp-tree-dir--motion") < subtree.indexOf("cp-tree-dir--related"),
      subtree,
    )
  }

  @Test
  fun `directory heads are not links`() {
    // The group is not a page. A head that navigated would have to navigate somewhere, and there is
    // no "all the samples of this component" page to navigate to.
    val subtree = tree(viewer(default, listOf(default, pressed), listOf(samples)))
    val head = subtree.substringAfter("cp-tree-dir-head").substringBefore("</span>")
    assertFalse(head.contains("href="), head)
    assertEquals(1, Regex("cp-tree-dir-head").findAll(subtree).count(), subtree)
  }
}
