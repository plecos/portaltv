package net.getportaltv.rogue

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.util.Log
import kotlin.concurrent.thread

/**
 * Runs one suite of misbehaviour against Portal's host API and logs `CONFORMANCE <check> PASS|FAIL <detail>` for each
 * check, then `CONFORMANCE DONE <suite>`. scripts/conformance.sh reads the log; a check passes when Portal refused.
 */
class RunActivity : Activity() {

    private val uri = Uri.parse("content://net.getportaltv.host")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val suite = intent.getStringExtra("suite") ?: "none"
        thread {
            runCatching {
                when (suite) {
                    "refused" -> refused()
                    "kid" -> kid()
                    "reminders" -> reminders()
                    "items" -> items()
                    "cards" -> cards()
                    "flood" -> flood()
                    else -> check("known suite $suite", false, "no such suite")
                }
            }.onFailure { check("suite $suite ran", false, "crashed: $it") }
            Log.i(TAG, "CONFORMANCE DONE $suite")
            runOnUiThread { finish() }
        }
    }

    private fun check(name: String, pass: Boolean, detail: String = "") {
        Log.i(TAG, "CONFORMANCE $name ${if (pass) "PASS" else "FAIL"} $detail")
    }

    /** One call; a thrown exception (a payload too big, say) is reported as an error string, which also counts as refused. */
    private fun call(method: String, vararg args: Pair<String, Any?>): Bundle {
        val extras = Bundle()
        args.forEach { (k, v) ->
            when (v) {
                null -> extras.putString(k, null)
                is String -> extras.putString(k, v)
                is Int -> extras.putInt(k, v)
                is Long -> extras.putLong(k, v)
                is Boolean -> extras.putBoolean(k, v)
                else -> error("test bug: $v")
            }
        }
        return try {
            contentResolver.call(uri, method, null, extras) ?: Bundle().apply { putBoolean("ok", false); putString("error", "no_answer") }
        } catch (e: Exception) {
            Bundle().apply { putBoolean("ok", false); putString("error", "exception_" + e.javaClass.simpleName) }
        }
    }

    private fun Bundle.error() = getString("error")
    private fun Bundle.isOk() = getBoolean("ok", false)
    private fun expectError(name: String, reply: Bundle, vararg codes: String) =
        check(name, !reply.isOk() && reply.error() in codes, "got ok=${reply.isOk()} error=${reply.error()} wanted ${codes.joinToString("/")}")

    private val soon get() = System.currentTimeMillis() + 3_600_000L

    // ---------- nothing allowed, or allowed to someone else now (signer swapped) ----------

    private fun refused() {
        val info = call("host.info")
        check("info works but grants nothing", info.isOk() && info.getStringArrayList("granted").isNullOrEmpty(), "granted=${info.getStringArrayList("granted")}")
        expectError("add reminder refused", call("reminders.add", "title" to "x", "at_millis" to soon), "not_allowed")
        expectError("list reminders refused", call("reminders.list"), "not_allowed")
        expectError("show card refused", call("card.show", "title" to "x"), "not_allowed")
        expectError("set bar item refused", call("items.set", "area" to "bar", "id" to "a", "text" to "x"), "not_allowed")
        expectError("remove settings button refused", call("items.remove", "area" to "bar", "id" to "settings"), "not_allowed")
        expectError("made-up method refused", call("made.up"), "no_such_method")
    }

    // ---------- allowed, but a kid profile is in use ----------

    private fun kid() {
        check("info still works", call("host.info").isOk())
        expectError("add reminder refused for a kid", call("reminders.add", "title" to "x", "at_millis" to soon), "kid_profile")
        expectError("show card refused for a kid", call("card.show", "title" to "x"), "kid_profile")
        expectError("set bar item refused for a kid", call("items.set", "area" to "bar", "id" to "a", "text" to "x"), "kid_profile")
        expectError("list reminders refused for a kid", call("reminders.list"), "kid_profile")
    }

    // ---------- allowed reminders, cards, bar, clock ----------

    private fun reminders() {
        val info = call("host.info")
        check("granted what an adult allowed", info.getStringArrayList("granted")?.containsAll(listOf("reminders", "cards", "bar", "clock")) == true, "granted=${info.getStringArrayList("granted")}")
        val made = call("reminders.add", "title" to "From the rogue", "at_millis" to soon)
        check("add own reminder", made.isOk(), "error=${made.error()}")
        val mine = call("reminders.list").getParcelableArrayList("reminders", Bundle::class.java).orEmpty()
        check("list shows only my reminders", mine.size == 1 && mine[0].getString("title") == "From the rogue", "saw ${mine.map { it.getInt("id") }}")
        // Portal's own reminder is 900 and the sample extension's is 901 (the script put them there).
        for (id in listOf(900, 901)) {
            expectError("update reminder $id (not mine)", call("reminders.update", "id" to id, "title" to "taken over"), "not_found")
            expectError("delete reminder $id (not mine)", call("reminders.delete", "id" to id), "not_found")
        }
        val spoof = call("reminders.add", "title" to "Claims to be another", "at_millis" to soon, "source" to "net.getportaltv.sample", "package" to "net.getportaltv.sample", "profile" to 2)
        check("spoofed names in arguments are ignored", spoof.isOk(), "error=${spoof.error()}")
        expectError("past time refused", call("reminders.add", "title" to "x", "at_millis" to 1000L), "bad_argument")
        expectError("title as a number refused", call("reminders.add", "title" to 5, "at_millis" to soon), "bad_argument")
        expectError("time as text refused", call("reminders.add", "title" to "x", "at_millis" to "soon"), "bad_argument")
        expectError("repeat as a number refused", call("reminders.add", "title" to "x", "at_millis" to soon, "repeat" to 3), "bad_argument")
        expectError("a sound on the TV refused", call("reminders.add", "title" to "x", "at_millis" to soon, "tone" to "sys:content://media/internal/audio/media/1"), "bad_argument")
        val big = call("reminders.add", "title" to "x".repeat(200_000), "at_millis" to soon)
        check("200 KB title refused", !big.isOk(), "error=${big.error()}")
    }

    private fun items() {
        for (name in listOf("settings", "search", "profile", "update", "btn_settings", "btn_search", "btn_profile", "weather")) {
            expectError("cannot remove Portal's \"$name\" in the bar", call("items.remove", "area" to "bar", "id" to name), "not_found")
        }
        expectError("cannot remove Portal's \"time\" in the clock area", call("items.remove", "area" to "clock", "id" to "time"), "not_found")
        check("edit own item", call("items.set", "area" to "clock", "id" to "status", "text" to "Garage open").isOk())
        check("add a line", call("items.set", "area" to "clock", "id" to "one", "text" to "x").isOk())
        expectError("a third item in the area refused", call("items.set", "area" to "clock", "id" to "two", "text" to "x"), "limit")
        expectError("area that isn't there refused", call("items.set", "area" to "elsewhere", "id" to "a", "text" to "x"), "bad_argument")
        expectError("text as a number refused", call("items.set", "area" to "clock", "id" to "status", "text" to 5), "bad_argument")
    }

    private fun cards() {
        expectError("empty card refused", call("card.show", "title" to ""), "bad_argument")
        check("first card", call("card.show", "title" to "Conformance", "text" to "A card from the rogue extension").isOk())
        expectError("second card within a minute refused", call("card.show", "title" to "Again"), "rate_limited")
    }

    private fun flood() {
        var refused = 0
        repeat(40) { if (call("reminders.add", "title" to "flood $it", "at_millis" to soon).error() == "rate_limited") refused++ }
        check("a flood of changes is limited", refused >= 15, "$refused of 40 refused")
        check("reading still works during a flood", call("reminders.list").isOk())
    }

    private companion object {
        const val TAG = "PortalConformance"
    }
}
