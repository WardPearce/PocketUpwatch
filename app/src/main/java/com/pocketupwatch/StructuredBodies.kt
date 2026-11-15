package com.pocketupwatch

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.io.StringWriter
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections
import java.util.IdentityHashMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.AliasEvent
import org.snakeyaml.engine.v2.events.CollectionStartEvent
import org.snakeyaml.engine.v2.events.CollectionEndEvent
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.nodes.*
import org.snakeyaml.engine.v2.schema.CoreSchema
import org.w3c.dom.Document
import org.w3c.dom.Node as XmlNode
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

internal data class BodyProblem(val message: String, val line: Int? = null, val column: Int? = null) {
    val description: String get() = if (line != null) "Line $line${column?.let { ", column $it" }.orEmpty()}: $message" else message
}

/** Parsing and comparison are independent of editor colors and never rewrite saved input. */
internal object StructuredBodies {
    const val MAX_BYTES = 1_048_576
    private const val MAX_DEPTH = 64
    private val pretty = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
    private fun bounded(text: String) { require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Keep the body under 1 MiB." } }

    fun json(text: String): JsonElement {
        bounded(text)
        JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            fun value(depth: Int): JsonElement {
                require(depth <= MAX_DEPTH) { "Use at most $MAX_DEPTH nested levels. $reader" }
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            require(!has(key)) { "Duplicate field '${key.take(60)}'. $reader" }
                            add(key, value(depth + 1))
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> JsonArray().apply { reader.beginArray(); while (reader.hasNext()) add(value(depth + 1)); reader.endArray() }
                    JsonToken.STRING -> JsonPrimitive(reader.nextString())
                    JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                    JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                    JsonToken.NUMBER -> {
                        val number = reader.nextString()
                        require(number.length <= 1000) { "Numbers can have up to 1,000 characters. $reader" }
                        JsonPrimitive(BigDecimal(number))
                    }
                    else -> error("Expected a JSON value. $reader")
                }
            }
            val result = value(0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Use one JSON document. $reader" }
            return result
        }
    }

    fun yaml(text: String): JsonElement {
        bounded(text)
        require(text.isNotBlank()) { "Enter a YAML value." }
        val settings = LoadSettings.builder().setSchema(CoreSchema()).setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(0).setCodePointLimit(MAX_BYTES).build()
        var nesting = 0
        for (event in Parse(settings).parseString(text)) {
            require(event !is AliasEvent) { "YAML aliases are not supported. Use the values directly." }
            if (event is CollectionStartEvent) { nesting++; require(nesting <= MAX_DEPTH) { "Use at most $MAX_DEPTH nested YAML levels." } }
            if (event is CollectionEndEvent) nesting--
        }
        val root = Compose(settings).composeString(text).orElseThrow { IllegalArgumentException("Enter a YAML value.") }
        val ancestors = Collections.newSetFromMap(IdentityHashMap<org.snakeyaml.engine.v2.nodes.Node, Boolean>())
        fun value(node: org.snakeyaml.engine.v2.nodes.Node, depth: Int): JsonElement {
            require(depth <= MAX_DEPTH && ancestors.add(node)) { "YAML nesting is too deep or recursive." }
            try {
                return when (node) {
                    is ScalarNode -> when (node.tag) {
                        Tag.STR -> JsonPrimitive(node.value)
                        Tag.NULL -> { require(node.value in listOf("", "~", "null", "Null", "NULL")) { "Invalid YAML null value." }; JsonNull.INSTANCE }
                        Tag.BOOL -> { require(node.value.lowercase() in listOf("true", "false")) { "Invalid YAML boolean value." }; JsonPrimitive(node.value.equals("true", ignoreCase = true)) }
                        Tag.INT, Tag.FLOAT -> {
                            val number = node.value.replace("_", "")
                            require(number.length <= 1000) { "Numbers can have up to 1,000 characters." }
                            val sign = if (number.startsWith('-')) -1 else 1
                            val unsigned = number.removePrefix("-").removePrefix("+")
                            val parsed = when {
                                node.tag == Tag.INT && unsigned.startsWith("0x") -> BigDecimal(BigInteger(unsigned.drop(2), 16).multiply(BigInteger.valueOf(sign.toLong())))
                                node.tag == Tag.INT && unsigned.startsWith("0o") -> BigDecimal(BigInteger(unsigned.drop(2), 8).multiply(BigInteger.valueOf(sign.toLong())))
                                node.tag == Tag.INT -> runCatching { BigDecimal(BigInteger(number)) }.getOrElse { error("Invalid YAML integer value.") }
                                else -> runCatching { BigDecimal(number) }.getOrElse { error("Use finite JSON-compatible numbers in YAML.") }
                            }
                            JsonPrimitive(parsed)
                        }
                        else -> error("Custom YAML tags are not supported. Use ordinary values.")
                    }
                    is SequenceNode -> {
                        require(node.tag == Tag.SEQ) { "Custom YAML tags are not supported." }
                        JsonArray().apply { node.value.forEach { add(value(it, depth + 1)) } }
                    }
                    is MappingNode -> {
                        require(node.tag == Tag.MAP) { "Custom YAML tags are not supported." }
                        JsonObject().apply { node.value.forEach { pair ->
                            val key = pair.keyNode as? ScalarNode
                            require(key?.tag == Tag.STR) { "Use string keys in YAML mappings. Quote numeric keys." }
                            require(!has(key!!.value)) { "Duplicate YAML key '${key.value.take(60)}'." }
                            add(key.value, value(pair.valueNode, depth + 1))
                        } }
                    }
                    else -> error("Unsupported YAML value.")
                }
            } finally { ancestors.remove(node) }
        }
        return value(root, 0)
    }

    fun equivalent(a: JsonElement, b: JsonElement): Boolean = when {
        a.isJsonNull || b.isJsonNull -> a.isJsonNull && b.isJsonNull
        a.isJsonObject && b.isJsonObject -> a.asJsonObject.keySet() == b.asJsonObject.keySet() && a.asJsonObject.entrySet().all { (key, item) -> equivalent(item, b.asJsonObject[key]) }
        a.isJsonArray && b.isJsonArray -> a.asJsonArray.size() == b.asJsonArray.size() && a.asJsonArray.zip(b.asJsonArray).all { equivalent(it.first, it.second) }
        a.isJsonPrimitive && b.isJsonPrimitive -> {
            val x = a.asJsonPrimitive; val y = b.asJsonPrimitive
            when { x.isNumber && y.isNumber -> x.asBigDecimal.compareTo(y.asBigDecimal) == 0
                x.isBoolean && y.isBoolean -> x.asBoolean == y.asBoolean
                x.isString && y.isString -> x.asString == y.asString
                else -> false }
        }
        else -> false
    }

    fun xml(text: String, coalesce: Boolean = false): Document {
        bounded(text)
        return DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false; isExpandEntityReferences = false; isNamespaceAware = true; isCoalescing = coalesce
        }.newDocumentBuilder().apply {
            setErrorHandler(object : DefaultHandler() {
                override fun error(e: SAXParseException) { throw e }
                override fun fatalError(e: SAXParseException) { throw e }
            })
        }.parse(InputSource(StringReader(text))).apply {
            val pending = java.util.ArrayDeque<Pair<XmlNode, Int>>()
            pending.add(documentElement to 0)
            while (pending.isNotEmpty()) {
                val (node, depth) = pending.removeLast()
                require(depth <= MAX_DEPTH) { "Use at most $MAX_DEPTH nested XML levels." }
                for (i in 0 until node.childNodes.length) if (node.childNodes.item(i).nodeType == XmlNode.ELEMENT_NODE) pending.add(node.childNodes.item(i) to depth + 1)
            }
            normalizeDocument()
        }
    }
    private sealed interface XmlPart
    private data class Element(val name: Pair<String, String>, val attributes: Map<Pair<String, String>, String>, val children: List<XmlPart>) : XmlPart
    private data class Text(val value: String) : XmlPart
    private fun xmlElement(node: XmlNode, ignoreWhitespace: Boolean, inheritedSpace: Boolean = false, depth: Int = 0): Element {
        require(depth <= MAX_DEPTH) { "Use at most $MAX_DEPTH nested XML levels." }
        val space = node.attributes?.getNamedItemNS(XMLConstants.XML_NS_URI, "space")?.nodeValue
        val preserve = when (space) { "preserve" -> true; "default" -> false; else -> inheritedSpace }
        val attributes = linkedMapOf<Pair<String, String>, String>()
        node.attributes?.let { list -> for (i in 0 until list.length) {
            val attr = list.item(i)
            if (attr.namespaceURI != XMLConstants.XMLNS_ATTRIBUTE_NS_URI) attributes[(attr.namespaceURI.orEmpty() to (attr.localName ?: attr.nodeName))] = attr.nodeValue
        } }
        val children = mutableListOf<XmlPart>()
        for (i in 0 until node.childNodes.length) {
            val child = node.childNodes.item(i)
            when (child.nodeType) {
                XmlNode.ELEMENT_NODE -> children.add(xmlElement(child, ignoreWhitespace, preserve, depth + 1))
                XmlNode.TEXT_NODE, XmlNode.CDATA_SECTION_NODE -> {
                    val text = child.nodeValue
                    if (ignoreWhitespace && !preserve && text.all { it in " \t\r\n" }) continue
                    if (text.isNotEmpty()) {
                        val last = children.lastOrNull()
                        if (last is Text) children[children.lastIndex] = Text(last.value + text) else children.add(Text(text))
                    }
                }
            }
        }
        return Element(node.namespaceURI.orEmpty() to (node.localName ?: node.nodeName), attributes, children)
    }
    fun xmlEquivalent(expected: String, actual: String, ignoreWhitespace: Boolean): Boolean =
        xmlElement(xml(expected, true).documentElement, ignoreWhitespace) == xmlElement(xml(actual, true).documentElement, ignoreWhitespace)

    fun problem(type: String, text: String): BodyProblem? = try {
        when (type) { "JSON" -> json(text); "YAML" -> yaml(text); "XML" -> xmlElement(xml(text, true).documentElement, false); "Text", "None" -> bounded(text); else -> error("Choose JSON, YAML, XML or Text.") }
        null
    } catch (e: Exception) {
        val yamlMark = (e as? MarkedYamlEngineException)?.problemMark?.orElse(null)
        val message = ((e as? MarkedYamlEngineException)?.problem ?: e.message ?: "Invalid $type value.").substringBefore(" at line ").substringBefore(" JsonReader").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(180)
        val location = Regex("line (\\d+)[, ]+column (\\d+)").find(e.message.orEmpty())
        BodyProblem(message, if (e is SAXParseException) e.lineNumber else if (yamlMark != null) yamlMark.line + 1 else location?.groupValues?.get(1)?.toIntOrNull(), if (e is SAXParseException) e.columnNumber else if (yamlMark != null) yamlMark.column + 1 else location?.groupValues?.get(2)?.toIntOrNull())
    }

    fun format(type: String, text: String): String = when (type) {
        "JSON" -> pretty.toJson(json(text))
        "YAML" -> {
            fun scalar(value: JsonElement) = value.isJsonPrimitive || value.isJsonNull || value.isJsonArray && value.asJsonArray.size() == 0 || value.isJsonObject && value.asJsonObject.size() == 0
            fun emit(value: JsonElement, indent: String): String = when {
                scalar(value) -> indent + value.toString()
                value.isJsonObject -> value.asJsonObject.entrySet().joinToString("\n") { (key, item) -> indent + JsonPrimitive(key).toString() + ":" + if (scalar(item)) " ${item}" else "\n" + emit(item, indent + "  ") }
                else -> value.asJsonArray.joinToString("\n") { item -> indent + "-" + if (scalar(item)) " $item" else "\n" + emit(item, indent + "  ") }
            }
            emit(yaml(text), "")
        }
        "XML" -> {
            val doc = xml(text, true)
            val factory = TransformerFactory.newInstance().apply { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            val output = StringWriter()
            factory.newTransformer().apply { setOutputProperty(OutputKeys.INDENT, "yes"); setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes"); setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2") }.transform(DOMSource(doc), StreamResult(output))
            output.toString().trimEnd().also { require(xmlEquivalent(text, it, true)) { "Formatting would change XML text or preserved whitespace. Keep the original value." } }
        }
        else -> text
    }
}
