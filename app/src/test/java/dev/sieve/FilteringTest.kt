package dev.sieve

import dev.sieve.filter.BlocklistRepository
import dev.sieve.filter.DomainSet
import dev.sieve.filter.FilterEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DomainSetTest {

    private val set = DomainSet.of(listOf("example.com", "ads.tracker.net", "single.io"))

    @Test
    fun `matches an exact domain`() {
        assertTrue(set.matches("example.com"))
        assertTrue(set.matches("ads.tracker.net"))
    }

    @Test
    fun `matches subdomains of a blocked domain`() {
        assertTrue(set.matches("cdn.example.com"))
        assertTrue(set.matches("a.b.c.example.com"))
    }

    @Test
    fun `does not match a parent of a blocked subdomain`() {
        // ads.tracker.net is listed; tracker.net itself is not.
        assertFalse(set.matches("tracker.net"))
        assertFalse(set.matches("other.tracker.net"))
    }

    @Test
    fun `does not match a domain that merely ends with the same text`() {
        assertFalse(set.matches("notexample.com"))
        assertFalse(set.matches("myexample.com"))
    }

    @Test
    fun `never matches on a bare public suffix`() {
        val tldListed = DomainSet.of(listOf("com"))
        assertFalse("a list entry of 'com' must not take out the TLD", tldListed.matches("google.com"))
    }

    @Test
    fun `is case insensitive`() {
        assertTrue(set.matches("CDN.Example.COM".lowercase()))
        assertEquals(DomainSet.hash("Example.COM"), DomainSet.hash("example.com"))
    }

    @Test
    fun `empty set matches nothing`() {
        assertFalse(DomainSet.EMPTY.matches("example.com"))
    }

    @Test
    fun `deduplicates on build`() {
        assertEquals(1, DomainSet.of(listOf("example.com", "example.com", "EXAMPLE.com")).size)
    }
}

class FilterEngineTest {

    private val engine = FilterEngine(
        blocklist = DomainSet.of(listOf("blocked.example", "shared.example")),
        customBlocked = setOf("mine.example"),
        allowed = setOf("shared.example"),
    )

    @Test
    fun `list entries are blocked`() {
        assertEquals(FilterEngine.Verdict.BLOCKED_BY_LIST, engine.evaluate("blocked.example"))
        assertEquals(FilterEngine.Verdict.BLOCKED_BY_LIST, engine.evaluate("www.blocked.example"))
    }

    @Test
    fun `custom rules are blocked`() {
        assertEquals(FilterEngine.Verdict.BLOCKED_BY_RULE, engine.evaluate("mine.example"))
    }

    @Test
    fun `an allow rule overrides the downloaded list`() {
        assertEquals(FilterEngine.Verdict.ALLOWED_BY_RULE, engine.evaluate("shared.example"))
        assertFalse(engine.evaluate("shared.example").isBlocked)
    }

    @Test
    fun `unlisted domains pass`() {
        assertEquals(FilterEngine.Verdict.ALLOWED, engine.evaluate("fine.example"))
    }
}

class HostsParsingTest {

    private fun parse(line: String) = BlocklistRepository.parseHostsLine(line)

    @Test
    fun `reads hosts syntax`() {
        assertEquals("ads.example.com", parse("0.0.0.0 ads.example.com"))
        assertEquals("ads.example.com", parse("127.0.0.1\tads.example.com"))
        assertEquals("ads.example.com", parse("0.0.0.0 ads.example.com # an inline comment"))
    }

    @Test
    fun `reads a bare domain list`() {
        assertEquals("ads.example.com", parse("ads.example.com"))
        assertEquals("ads.example.com", parse("  ADS.Example.com  "))
    }

    @Test
    fun `skips comments, blanks and loopback entries`() {
        assertNull(parse("# Title: StevenBlack/hosts"))
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("127.0.0.1 localhost"))
        assertNull(parse("255.255.255.255 broadcasthost"))
        assertNull(parse("::1 ip6-localhost"))
    }

    @Test
    fun `rejects bare IPs and malformed names`() {
        assertNull(parse("0.0.0.0 1.2.3.4"))
        assertNull(parse("0.0.0.0 no-dot-here"))
        assertNull(parse("0.0.0.0 .leading.dot"))
        assertNull(parse("0.0.0.0 double..dot.com"))
    }

    @Test
    fun `strips a trailing root dot`() {
        assertEquals("ads.example.com", parse("0.0.0.0 ads.example.com."))
    }
}

class DomainSetPersistenceTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `survives a write and read at realistic scale`() {
        val domains = buildList {
            add("example.com")
            add("ads.tracker.net")
            repeat(150_000) { add("filler-$it.example.org") }
        }
        val original = DomainSet.of(domains)
        val file = folder.newFile("blocklist.idx")
        original.writeTo(file)

        val restored = DomainSet.readFrom(file)!!
        assertEquals(original.size, restored.size)
        assertTrue(restored.matches("example.com"))
        assertTrue(restored.matches("cdn.example.com"))
        assertTrue(restored.matches("filler-99999.example.org"))
        assertFalse(restored.matches("nowhere.example.net"))

        // 8 bytes per entry plus a 12-byte header, which is the point of hashing.
        assertEquals(12L + 8L * original.size, file.length())
    }

    @Test
    fun `rejects a corrupt or foreign file`() {
        val bogus = folder.newFile("bogus.idx")
        bogus.writeBytes(ByteArray(64) { 0x7F })
        assertNull(DomainSet.readFrom(bogus))
        assertNull(DomainSet.readFrom(folder.root.resolve("missing.idx")))
    }

    @Test
    fun `lookups stay fast on a large set`() {
        val set = DomainSet.of(List(200_000) { "domain-$it.example.com" })
        val start = System.nanoTime()
        repeat(20_000) { set.matches("a.b.lookup-$it.example.net") }
        val perLookupNanos = (System.nanoTime() - start) / 20_000
        assertTrue("lookup took ${perLookupNanos}ns", perLookupNanos < 100_000)
    }
}
