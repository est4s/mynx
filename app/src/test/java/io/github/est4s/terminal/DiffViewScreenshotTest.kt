package io.github.est4s.terminal

import android.app.Application
import io.github.est4s.terminal.core.NEON
import io.github.est4s.terminal.core.codeLanguageOf
import io.github.est4s.terminal.core.diffLines
import io.github.est4s.terminal.core.diffRows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val BEFORE = """
    #!/usr/bin/env python3
    ""${'"'}Splits a bill and adds the tip.""${'"'}

    import math
    import sys

    TIPS = {"low": 10, "usual": 15, "generous": 20}


    def tip(total: float, percent: int) -> float:
        return total * percent / 100


    class Bill:
        def __init__(self, total, people=1):
            self.total = total
            self.people = people

        def share(self, percent=15):
            whole = self.total + tip(self.total, percent)
            return whole / self.people


    def main():
        bill = Bill(float(sys.argv[1]), int(sys.argv[2]))
        print(bill.share(TIPS["usual"]))


    if __name__ == "__main__":
        main()
""".trimIndent() + "\n"

// Two changes far apart: tip() rounds up, and share() rounds and keeps one person at least.
private val AFTER = BEFORE
    .replace(
        "def tip(total: float, percent: int) -> float:\n    return total * percent / 100\n",
        "def tip(total: float, percent: int) -> float:\n    # Rounded up to whole cents, so nobody pays less.\n    return math.ceil(total * percent) / 100\n",
    )
    .replace("        self.people = people\n", "        self.people = max(1, people)\n")
    .replace("    return whole / self.people\n", "    return round(whole / self.people, 2)\n")
    .replace("    main()\n", "    main()")

private val NEW_FILE = """
    import unittest

    from tip import Bill, tip


    class TipTest(unittest.TestCase):
        def test_rounds_up(self):
            self.assertEqual(tip(10.01, 15), 1.51)

        def test_one_person_at_least(self):
            self.assertEqual(Bill(30, 0).share(10), 33.0)


    if __name__ == "__main__":
        unittest.main()
""".trimIndent() + "\n"

private val noDiffActions = object : DiffViewActions {
    override fun close() = Unit
    override fun openFile() = Unit
    override fun shareDiff() = Unit
}

/** The diff view: a code edit with two hunks, a new file, and one too big, fed diffs directly (see [Screenshots]). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = PHONE_QUALIFIERS)
class DiffViewScreenshotTest {
    private fun shoot(w: Int, h: Int, name: String, path: String, before: String?, after: String, tooBig: Boolean = false) {
        val view = Screenshots.open(w, h) { activity -> DiffView(activity, path, NEON, noDiffActions) }
        if (tooBig) {
            view.showTooBig()
        } else {
            val diff = diffLines(before ?: "", after).copy(isNew = before == null)
            view.showDiff(diff, diffRows(diff, codeLanguageOf(path.substringAfterLast('/'))))
        }
        Screenshots.settle()
        Screenshots.save(view, name)
    }

    @Test
    fun edit() = shoot(1080, 2400, "diff-edit", "/root/apps/tip-calc/tip.py", BEFORE, AFTER)

    @Test
    @Config(qualifiers = NARROW_QUALIFIERS)
    fun editNarrow() = shoot(945, 2100, "diff-edit-360dp", "/root/apps/tip-calc/tip.py", BEFORE, AFTER)

    @Test
    @Config(qualifiers = LANDSCAPE_QUALIFIERS)
    fun editLandscape() = shoot(2400, 1080, "diff-edit-landscape", "/root/apps/tip-calc/tip.py", BEFORE, AFTER)

    @Test
    fun newFile() = shoot(1080, 2400, "diff-new", "/root/apps/tip-calc/test_tip.py", null, NEW_FILE)

    @Test
    fun tooBig() = shoot(1080, 2400, "diff-too-big", "/root/data/dump.sql", null, "", tooBig = true)
}
