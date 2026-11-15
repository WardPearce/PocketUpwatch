package com.pocketupwatch

import org.junit.Assert.*
import org.junit.Test

class StructuredBodiesTest {
    @Test fun jsonIgnoresPresentationButKeepsArraysAndTypes() {
        assertTrue(BodyMatcher.matches("JSON", "{\"a\":1,\"b\":[true,null]}", "{ \"b\": [true, null], \"a\": 1.00 }"))
        assertFalse(BodyMatcher.matches("JSON", "[1,2]", "[2,1]"))
        assertFalse(BodyMatcher.matches("JSON", "{\"a\":null}", "{}"))
        assertFalse(BodyMatcher.matches("JSON", "true", "\"true\""))
        assertFalse(BodyMatcher.matches("JSON", "1", "\"1\""))
    }
    @Test fun numbersNeverCompareUsingLossyDoubles() {
        assertFalse(BodyMatcher.matches("JSON", "9007199254740992", "9007199254740993"))
        assertFalse(BodyMatcher.matches("JSON", "0.1234567890123456789", "0.1234567890123456790"))
        assertTrue(BodyMatcher.matches("JSON", "1e3", "1000.0"))
    }
    @Test fun duplicateFieldsInvalidSyntaxAndExtraDocumentsAreRejected() {
        listOf("{\"a\":1,\"a\":2}", "{a:1}", "NaN", "1 2", "", "[1,]").forEach { assertNotNull(it, BodyMatcher.validate("JSON", it)) }
        assertFalse(BodyMatcher.matches("JSON", "{}", "{\"a\":1,\"a\":2}"))
    }
    @Test fun jsonPathsPreserveMissingVersusNull() {
        assertTrue(BodyMatcher.matches("JSON", "null", "{\"a\":null}", "$.a"))
        assertFalse(BodyMatcher.matches("JSON", "null", "{}", "$.a"))
        assertTrue(BodyMatcher.matches("JSON", "true", "{\"items\":[{\"ok\":true}]}", "$.items[0].ok"))
        assertNotNull(BodyMatcher.validate("JSON", "true", "$..ok"))
    }
    @Test fun yamlAcceptsYmlAndNormalizesFlowBlockKeysCommentsAndNumbers() {
        val expected = "status: ready\nitems:\n  - 1\n  - true\ncount: 0x10"
        assertTrue(BodyMatcher.matches("YAML", expected, "{ count: 16, items: [1.0, true], status: ready } # healthy"))
        assertTrue(BodyMatcher.matches("YML", expected, expected))
        assertNull(BodyMatcher.validate("YML", expected))
        assertFalse(BodyMatcher.matches("YAML", "items: [1, 2]", "items: [2, 1]"))
    }
    @Test fun yamlUsesYaml12TypesAndPreservesStrings() {
        assertTrue(BodyMatcher.matches("YAML", "flag: yes\ndate: 2026-01-01", "flag: 'yes'\ndate: '2026-01-01'"))
        assertFalse(BodyMatcher.matches("YAML", "flag: true", "flag: 'true'"))
        assertFalse(BodyMatcher.matches("YAML", "a: null", "{}"))
        assertFalse(BodyMatcher.matches("YAML", "a: |\n  hi\n", "a: hi"))
    }
    @Test fun unsafeOrAmbiguousYamlIsRejected() {
        listOf("a: 1\na: 2", "1: value", "a: !custom value", "a: !!java/object {}", "a: .nan", "a: !!bool perhaps", "a: !!int 1.5", "a: !!null nope", "a: &value hello\nb: *value", "a: &a [1]\nb: *a", "---\na: 1\n---\na: 2").forEach { assertNotNull(it, BodyMatcher.validate("YAML", it)) }
    }
    @Test fun structuredParsingHasDepthAndSizeLimits() {
        assertNotNull(BodyMatcher.validate("JSON", "[".repeat(70) + "0" + "]".repeat(70)))
        assertNotNull(BodyMatcher.validate("YAML", "[".repeat(70) + "0" + "]".repeat(70)))
        assertNotNull(BodyMatcher.validate("XML", "<a>".repeat(70) + "</a>".repeat(70)))
        assertNotNull(BodyMatcher.validate("YAML", "a".repeat(StructuredBodies.MAX_BYTES + 1)))
    }
    @Test fun xmlStructuralComparisonIgnoresPrefixesCommentsAndCdataRepresentation() {
        val a = "<x:r xmlns:x='urn:test' a='1' b='2'><x:c>ok</x:c></x:r>"
        val b = "<y:r b='2' a='1' xmlns:y='urn:test'><!--note--><y:c><![CDATA[ok]]></y:c></y:r>"
        assertTrue(BodyMatcher.matches("XML", a, b, xmlStructural = true))
        assertFalse(BodyMatcher.matches("XML", a, b))
        assertFalse(BodyMatcher.matches("XML", "<a xmlns='one'/>", "<a xmlns='two'/>", xmlStructural = true))
    }
    @Test fun xmlWhitespaceIsExplicitAndXmlSpaceIsRespected() {
        val a = "<root><item>ok</item></root>"
        val b = "<root>\n  <item>ok</item>\n</root>"
        assertFalse(BodyMatcher.matches("XML", a, b, xmlStructural = true))
        assertTrue(BodyMatcher.matches("XML", a, b, xmlStructural = true, xmlIgnoreWhitespace = true))
        assertFalse(BodyMatcher.matches("XML", "<r xml:space='preserve'><a/></r>", "<r xml:space='preserve'> <a/></r>", xmlStructural = true, xmlIgnoreWhitespace = true))
        assertFalse(BodyMatcher.matches("XML", "<r> hello </r>", "<r>hello</r>", xmlStructural = true, xmlIgnoreWhitespace = true))
    }
    @Test fun xmlNeverExpandsExternalEntities() {
        assertNotNull(BodyMatcher.validate("XML", "<!DOCTYPE r [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><r>&x;</r>"))
        assertFalse(BodyMatcher.matches("XML", "<r/>", "<!DOCTYPE r SYSTEM 'https://example.com/dtd'><r/>", xmlStructural = true))
    }
    @Test fun formatPreservesStructuredValues() {
        listOf("JSON" to "{\"b\":[null,1.20],\"a\":true}", "YAML" to "items: [true, null, 'yes']\nname: hello").forEach { (type, value) ->
            assertTrue(BodyMatcher.matches(type, value, StructuredBodies.format(type, value)))
        }
        val xml = "<r><a x='1'>ok</a></r>"
        assertTrue(BodyMatcher.matches("XML", xml, StructuredBodies.format("XML", xml), xmlStructural = true, xmlIgnoreWhitespace = true))
    }
    @Test fun diagnosticsIncludeSourceLocations() {
        assertNotNull(StructuredBodies.problem("JSON", "{\n\"a\": }" )?.line)
        assertNotNull(StructuredBodies.problem("XML", "<r>\n<a></r>")?.line)
        assertNotNull(StructuredBodies.problem("YAML", "a: [\n  value")?.line)
    }
    @Test fun headerRowsAllowEditsDeletionAndEmptyValuesButRejectDuplicates() {
        val a = HeaderDraft("X-Env", "prod")
        val b = HeaderDraft("X-Empty", "")
        assertEquals(mapOf("X-Env" to "prod", "X-Empty" to ""), headerValues(listOf(a, b, HeaderDraft())))
        assertEquals(mapOf("X-Env" to "staging"), headerValues(listOf(a.copy(value = "staging"))))
        assertTrue(headerValues(emptyList()).isEmpty())
        assertTrue(runCatching { headerValues(listOf(a, HeaderDraft("x-env", "other"))) }.isFailure)
        assertTrue(runCatching { headerValues(listOf(HeaderDraft(value = "missing name"))) }.isFailure)
    }
    @Test fun backupPreservesYamlAndXmlOptionsAndPreventsOldReadersFromDroppingThem() {
        val service = ServiceEntry(name = "API", url = "https://example.com", intervalSeconds = 300, bodyType = "YAML", expectedBody = "ok: true", xmlStructural = true, xmlIgnoreWhitespace = true)
        val encoded = BackupCodec.encode(listOf(service), "a long test password".toCharArray()) { emptyMap() }
        val restored = BackupCodec.decode(encoded, "a long test password".toCharArray()) { "" }.single()
        assertEquals("YAML", restored.bodyType)
        assertTrue(restored.xmlStructural)
        assertTrue(restored.xmlIgnoreWhitespace)
        val template = BackupCodec.encode(listOf(service), charArrayOf()) { emptyMap() }
        assertTrue(template.contains("\"version\": 3"))
    }
}
