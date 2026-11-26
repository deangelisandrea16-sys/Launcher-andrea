package it.andreadeangelis.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class Contact(val name: String, val number: String, val search: String)

/** Contatti letti solo in memoria per la ricerca: non vengono salvati. */
object Contacts {
    fun granted(c: Context) =
        ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun load(c: Context): List<Contact> {
        if (!granted(c)) return emptyList()
        val out = mutableListOf<Contact>()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        try {
            c.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(0) ?: continue
                    val num = cur.getString(1) ?: continue
                    out += Contact(name, num, normalize(name))
                }
            }
        } catch (e: Exception) {
        }
        return out.distinctBy { it.name + it.number.filter { ch -> ch.isDigit() }.takeLast(9) }
    }

    /** Numero in formato internazionale per wa.me (prefisso italiano se manca). */
    fun waNumber(n: String): String {
        var d = n.filter { it.isDigit() || it == '+' }
        if (d.startsWith("00")) d = "+" + d.drop(2)
        if (!d.startsWith("+")) d = "+39$d"
        return d.drop(1)
    }
}
