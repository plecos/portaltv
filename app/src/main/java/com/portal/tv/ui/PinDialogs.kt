package com.portal.tv.ui

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.portal.tv.data.Profile
import com.portal.tv.repo.ParentPin
import com.portal.tv.repo.ProfileCode

/** Dialogs for entering, setting and changing the parent PIN. */
object PinDialogs {

    /** Said every time the PIN is asked for, so a look-alike screen from an app is easier to spot. */
    const val PIN_WARNING = "Portal asks for this PIN only here, in its own Settings and profile screens. If any other app or page asks for it, don't enter it."

    /** Asks for the current PIN and runs [onVerified] if it matches. */
    fun verify(context: Context, title: String = "Enter parent PIN", onVerified: () -> Unit) {
        PinPad.ask(context, title, PIN_WARNING, onDone = { pin ->
            if (ParentPin.verify(context, pin)) onVerified()
            else toast(context, "Wrong PIN")
        })
    }

    /** Asks for a new PIN twice, stores it, then runs [onSet]. */
    fun create(
        context: Context,
        title: String = "Set a parent PIN",
        message: String =
            "Set a ${ParentPin.MIN_LENGTH} digit parent PIN that is asked to leave a kid profile or change profiles",
        onSet: () -> Unit = {}
    ) {
        PinPad.ask(context, title, message, onDone = { first ->
            if (first.length < ParentPin.MIN_LENGTH) {
                toast(context, "PIN must be at least ${ParentPin.MIN_LENGTH} digits")
                return@ask
            }
            PinPad.ask(context, "Confirm PIN", onDone = { second ->
                if (second != first) {
                    toast(context, "PINs don't match")
                } else {
                    ParentPin.set(context, first)
                    onSet()
                }
            })
        })
    }

    /**
     * Verifies the current PIN, then removes it. Refused while a kid profile exists, because
     * the PIN is the only way to unlock one.
     */
    fun remove(context: Context, onRemoved: () -> Unit = {}) {
        val kids = com.portal.tv.repo.ProfileManager.getAll(context).filter { it.isKids }
        if (kids.isNotEmpty()) {
            PortalDialog.Builder(context)
                .setTitle("Can't remove the PIN yet")
                .setMessage(
                    "The PIN unlocks kid profiles (${kids.joinToString { it.name }}). " +
                        "Delete them or make them adult profiles first."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        verify(context, "Enter PIN to remove it") {
            ParentPin.clear(context)
            toast(context, "PIN removed")
            onRemoved()
        }
    }

    /** Verifies the current PIN, then sets a new one. */
    fun change(context: Context, onChanged: () -> Unit = {}) {
        verify(context, "Enter current PIN") {
            create(
                context,
                title = "Choose a new PIN",
                message = "Set a new ${ParentPin.MIN_LENGTH} digit parent PIN that is asked to leave a kid profile or change profiles",
                onSet = {
                    toast(context, "PIN changed")
                    onChanged()
                }
            )
        }
    }


    // ---------- Profile codes ----------

    /** Asks for [profile]'s code and runs [onVerified] if it matches. */
    fun verifyProfile(context: Context, profile: Profile, onVerified: () -> Unit) {
        PinPad.ask(context, "Enter ${profile.name}'s code", onDone = { pin ->
            if (ProfileCode.verify(context, profile.id, pin)) onVerified()
            else toast(context, "Wrong code")
        })
    }

    /** Asks for a new code for [profile] twice, then stores it. */
    fun createProfileCode(context: Context, profile: Profile, onSet: () -> Unit = {}) {
        PinPad.ask(
            context, "Set a code for ${profile.name}",
            "Set a ${ProfileCode.MIN_LENGTH} digit code that is asked when opening this profile",
            onDone = { first ->
                if (first.length < ProfileCode.MIN_LENGTH) {
                    toast(context, "Code must be at least ${ProfileCode.MIN_LENGTH} digits")
                    return@ask
                }
                PinPad.ask(context, "Confirm code", onDone = { second ->
                    if (second != first) {
                        toast(context, "Codes don't match")
                    } else {
                        ProfileCode.set(context, profile.id, first)
                        toast(context, "Code set")
                        onSet()
                    }
                })
            })
    }

    /** Verifies the current code, then sets a new one. */
    fun changeProfileCode(context: Context, profile: Profile, onSet: () -> Unit = {}) =
        verifyProfile(context, profile) { createProfileCode(context, profile, onSet) }

    /** Verifies the current code, then removes it. */
    fun removeProfileCode(context: Context, profile: Profile, onRemoved: () -> Unit = {}) =
        verifyProfile(context, profile) {
            ProfileCode.clear(context, profile.id)
            toast(context, "Code removed")
            onRemoved()
        }

    private fun toast(context: Context, msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
