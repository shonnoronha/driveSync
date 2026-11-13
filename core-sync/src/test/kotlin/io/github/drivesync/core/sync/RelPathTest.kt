package io.github.drivesync.core.sync

import io.github.drivesync.core.sync.model.RelPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RelPathTest {
    @Test
    fun `normalizes redundant slashes`() {
        assertEquals("a/b/c.txt", RelPath.of("/a//b/c.txt/").value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "/", "//", ".", "..", "a/./b", "a/../b", "../x"])
    fun `rejects empty and relative segments`(raw: String) {
        assertThrows<IllegalArgumentException> { RelPath.of(raw) }
    }

    @Test
    fun `allows dots inside names`() {
        assertEquals("...hidden/.a..b", RelPath.of("...hidden/.a..b").value)
    }

    @Test
    fun `name parent segments and depth`() {
        val p = RelPath.of("Notes/2026/todo.md")
        assertEquals("todo.md", p.name)
        assertEquals(RelPath.of("Notes/2026"), p.parent)
        assertEquals(listOf("Notes", "2026", "todo.md"), p.segments)
        assertEquals(3, p.depth)
    }

    @Test
    fun `top level path has no parent and depth one`() {
        val p = RelPath.of("todo.md")
        assertNull(p.parent)
        assertEquals(1, p.depth)
        assertEquals(emptyList<RelPath>(), p.ancestors.toList())
    }

    @Test
    fun `ancestors are nearest first`() {
        assertEquals(
            listOf(RelPath.of("a/b/c"), RelPath.of("a/b"), RelPath.of("a")),
            RelPath.of("a/b/c/d").ancestors.toList(),
        )
    }

    @Test
    fun `child and sibling`() {
        assertEquals(RelPath.of("a/b"), RelPath.of("a").child("b"))
        assertEquals(RelPath.of("a/c"), RelPath.of("a/b").sibling("c"))
        assertEquals(RelPath.of("c"), RelPath.of("b").sibling("c"))
    }

    @Test
    fun `isAncestorOf needs a whole segment`() {
        assertTrue(RelPath.of("a").isAncestorOf(RelPath.of("a/b")))
        assertTrue(RelPath.of("a").isAncestorOf(RelPath.of("a/b/c")))
        assertFalse(RelPath.of("a").isAncestorOf(RelPath.of("ab/c")))
        assertFalse(RelPath.of("a").isAncestorOf(RelPath.of("a")))
        assertFalse(RelPath.of("a/b").isAncestorOf(RelPath.of("a")))
    }

    @Test
    fun `ordering and toString use the string value`() {
        val sorted = listOf("b", "a/b", "a").map(RelPath::of).sorted()
        assertEquals(listOf("a", "a/b", "b"), sorted.map { it.toString() })
    }

    @Test
    fun `equality is by value`() {
        assertEquals(RelPath.of("a/b"), RelPath.of("a//b"))
    }
}
