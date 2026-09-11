package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The component subtree's **directories** — the named groups under a component that are not more of
 * its renders: its recorded interactions, the catalogs that are about it.
 *
 * The drawer's subtree used to hold exactly one kind of row, a primary-axis variant, and everything
 * else a component reaches invented its own affordance somewhere else on the page. A recording was
 * a chip on the stage, which a reader can only find by already being on the page it is on; a sample
 * in another catalog had nowhere at all. These tests hold the shape that gives both one home:
 * variants stay the flat list they were, and anything else hangs under a named head with a count.
 *
 * What they do NOT assert is placement — which directories a page ends up with is the handler's
 * answer ([ServeHttpServer.componentRelatedDirectories]) and the producers' business.
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
   * A component with [n] baked states, which is what it takes to get variant ROWS out of the
   * subtree — the same fixture shape [ServeViewerDisclosuresTest] uses. Two renders alone do not
   * make a state axis the tree will list, so a directory test that wants variants beside it has to
   * ask for three.
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
    // The count on the component row answers "how many renders of this are there". A recording is
    // not a render of it and a sample is another catalog's component, so folding either into that
    // number would leave it meaning nothing in particular. Three renders ⇒ 3, with or without.
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
    // Without directories this returns "" — a tree of one node is its own title. With one it has
    // something to show, which is the case of a component that never varies and is called by five
    // samples.
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
  fun `motion rows are built from every render of the component, not just the one on screen`() {
    // A capture belongs to the preview that took it, so a recording declared on `Pressed` is
    // invisible from the default page unless the directory unions the component's renders — which
    // is exactly the discovery problem it exists to fix.
    val recorded =
      preview(
        "button__ideal__pressed__light",
        "Button · Pressed",
        state = "pressed",
        motion = listOf(ServeMotion("press", caption = "Press and release")),
      )
    val subtree = tree(viewer(default, listOf(default, recorded)))
    assertTrue(subtree.contains("cp-tree-dir--motion"), subtree)
    assertTrue(subtree.contains(">Motion</span><span class=\"cp-tree-count\">1</span>"), subtree)
    // Escaped, because it is an href in HTML and the query already carries the link token.
    assertTrue(
      subtree.contains("/p/button__ideal__pressed__light?token=t&amp;mode=motion&amp;motion=press"),
      subtree,
    )
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
