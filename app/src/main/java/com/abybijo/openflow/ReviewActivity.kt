package com.abybijo.openflow

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Review before inserting: shows the cleaned transcript in an editable
 * field so the user can fix it before it goes anywhere.
 *
 * Insert never writes into a field directly from here — this activity is
 * in the foreground while it is open, so the accessibility service would
 * see this screen's own text field. Instead Insert hands the edited text
 * to BubbleService, finishes, and the bubble inserts it once the host
 * app is back in front (with the event-driven retries as cover).
 * Copy copies the text and closes. Back closes without inserting.
 */
class ReviewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TEXT = "text"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialText = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        setContent {
            val prefsFlow = remember { Store.prefsFlow(applicationContext) }
            val prefs by prefsFlow.collectAsState(initial = Prefs())
            val systemDark = isSystemInDarkTheme()
            val isDark = when (prefs.theme) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            // The edited text survives rotation, so a recreated activity
            // never wipes what the user just fixed.
            var text by rememberSaveable { mutableStateOf(initialText) }
            OpenFlowTheme(isDark = isDark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .imePadding()
                            .verticalScroll(rememberScrollState())
                            .padding(22.dp)
                    ) {
                        Text("Review dictation", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Fix anything below, then insert it where you were typing.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Spacer(Modifier.height(18.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            minLines = 5,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            GhostButton(
                                text = "Copy",
                                onClick = {
                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(
                                        android.content.ClipData.newPlainText("OpenFlow", text)
                                    )
                                    Toast.makeText(this@ReviewActivity, "Copied", Toast.LENGTH_SHORT).show()
                                    finish()
                                },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(12.dp))
                            GradientButton(
                                text = "Insert",
                                onClick = {
                                    val intent = Intent(this@ReviewActivity, BubbleService::class.java).apply {
                                        action = BubbleService.ACTION_INSERT_REVIEWED
                                        putExtra(EXTRA_TEXT, text)
                                    }
                                    try {
                                        startService(intent)
                                    } catch (_: Exception) {
                                        Toast.makeText(
                                            this@ReviewActivity,
                                            "Could not insert — text copied instead.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(
                                            android.content.ClipData.newPlainText("OpenFlow", text)
                                        )
                                    }
                                    finish()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
