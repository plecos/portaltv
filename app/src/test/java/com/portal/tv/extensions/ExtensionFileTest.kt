package com.portal.tv.extensions

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks an extension's package name and its `portal-extension` file with the same code Portal runs, so an author learns
 * before installing whether Portal would refuse it. Run it with `scripts/check-extension.sh`; it only runs when
 * `PORTAL_CHECK_PACKAGE` and `PORTAL_CHECK_XML` are set (and optionally `PORTAL_CHECK_STRINGS`, a strings.xml, so
 * `@string/...` names are read as the words they stand for). It writes its findings to `PORTAL_CHECK_OUT`.
 */
class ExtensionFileTest {

    /** What was found, one line each; empty means Portal would accept the names. */
    internal fun check(packageName: String, xmlText: String, stringsText: String?): List<String> {
        val strings = stringsText?.let { stringTable(it) }.orEmpty()
        val found = mutableListOf<String>()
        if (Impersonation.isReservedPackage(packageName)) {
            found += "The package name $packageName has a part that is Portal's own name, so Portal refuses it unless the app is signed with Portal's own key. Choose a package name of your own, such as com.yourname.${packageName.substringAfterLast('.')}."
        }
        val root = try { node(DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlText.byteInputStream()).documentElement, strings) }
        catch (e: Exception) { return found + "The extension file isn't readable XML: ${e.message}" }
        when (val r = ManifestParser.parse(root, packageName)) {
            is ManifestParser.Result.Invalid -> found += r.reason + " (Portal shows this on the extension's page as \"Can't be used\".)"
            is ManifestParser.Result.NeedsNewerPortal -> found += "It needs contract ${r.contract}, newer than this Portal's ${ManifestParser.CONTRACT_VERSION}."
            is ManifestParser.Result.Ok -> {}
        }
        return found
    }

    private fun stringTable(text: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(text.byteInputStream())
        val list = doc.getElementsByTagName("string")
        return (0 until list.length).associate { list.item(it).attributes.getNamedItem("name").nodeValue to list.item(it).textContent.trim() }
    }

    private fun node(e: org.w3c.dom.Element, strings: Map<String, String>): XmlNode {
        val attrs = (0 until e.attributes.length).associate {
            val a = e.attributes.item(it)
            val value = a.nodeValue.let { v -> if (v.startsWith("@string/")) strings[v.removePrefix("@string/")] ?: v else v }
            // A picture the extension names is read as present; its size is judged on the TV.
            a.nodeName to XmlAttr(value, if (a.nodeName == "icon" && a.nodeValue.startsWith("@")) 0x7f080001 else 0)
        }
        val kids = (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<org.w3c.dom.Element>().map { node(it, strings) }
        return XmlNode(e.tagName, attrs, kids)
    }

    @Test
    fun checksTheExtensionNamedByTheEnvironment() {
        val pkg = System.getenv("PORTAL_CHECK_PACKAGE")
        val xml = System.getenv("PORTAL_CHECK_XML")
        assumeTrue(pkg != null && xml != null)
        val found = check(pkg, File(xml).readText(), System.getenv("PORTAL_CHECK_STRINGS")?.let { File(it).readText() })
        System.getenv("PORTAL_CHECK_OUT")?.let { File(it).writeText(found.joinToString("\n") + if (found.isEmpty()) "" else "\n") }
        assertTrue(found.joinToString("\n"), found.isEmpty())
    }

    // ---- The checker itself ----

    private val ok = """<portal-extension contract="1" id="p" label="Garage door"><tile id="t" label="Garage" activity=".A"/></portal-extension>"""

    @Test
    fun aCleanExtensionHasNoFindings() {
        assertTrue(check("net.example.garage", ok, null).isEmpty())
    }

    @Test
    fun aReservedPackageIsReportedWithWhatToDo() {
        val f = check("tv.portal.settings", ok, null)
        assertTrue(f.single(), f.single().contains("Choose a package name of your own"))
    }

    @Test
    fun aLookalikeNameIsReportedAndStringReferencesAreRead() {
        val xml = """<portal-extension contract="1" id="p" label="@string/name"/>"""
        val f = check("net.example.garage", xml, """<resources><string name="name">Sett1ngs</string></resources>""")
        assertTrue(f.joinToString(), f.any { it.contains("Sett1ngs") })
    }
}
