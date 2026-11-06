package com.portal.tv.extensions

import android.content.Context

/** What an adult has allowed each extension to do, and the signer it was allowed with. The same for every profile. */
object ExtensionStore {

    private const val PREFS = "portal_extensions"
    private const val KEY = "allowed"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): Map<String, Allowance> = ExtensionJson.decode(prefs(context).getString(KEY, null))

    fun get(context: Context, packageName: String): Allowance? = all(context)[packageName]

    fun put(context: Context, packageName: String, allowance: Allowance) = save(context, all(context) + (packageName to allowance))

    fun remove(context: Context, packageName: String) = save(context, all(context) - packageName)

    private fun save(context: Context, map: Map<String, Allowance>) {
        prefs(context).edit().putString(KEY, ExtensionJson.encode(map)).apply()
    }
}
