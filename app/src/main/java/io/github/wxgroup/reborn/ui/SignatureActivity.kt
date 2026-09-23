package io.github.wxgroup.reborn.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.wxgroup.reborn.R
import io.github.wxgroup.reborn.core.GroupStore
import org.json.JSONObject

class SignatureActivity : AppCompatActivity() {

    private lateinit var etJson: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signature)
        title = getString(R.string.signature_title)

        etJson = findViewById(R.id.et_json)
        // 优先显示已保存的，否则显示默认
        val saved = GroupStore.getSignatures(this)
        etJson.setText(saved?.toJson()?.toString(2) ?: GroupStore.defaultSignatureJson(this))

        findViewById<Button>(R.id.btn_save).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_reset).setOnClickListener { reset() }
    }

    private fun save() {
        val text = etJson.text.toString()
        if (!isValidJson(text)) {
            Toast.makeText(this, R.string.signature_bad_json, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = GroupStore.saveSignature(this, text)
        Toast.makeText(this, if (ok) R.string.signature_saved else R.string.signature_bad_json, Toast.LENGTH_SHORT).show()
    }

    private fun reset() {
        GroupStore.resetSignature(this)
        etJson.setText(GroupStore.defaultSignatureJson(this))
        Toast.makeText(this, R.string.signature_saved, Toast.LENGTH_SHORT).show()
    }

    private fun isValidJson(text: String): Boolean = try {
        JSONObject(text); true
    } catch (t: Throwable) {
        false
    }
}
