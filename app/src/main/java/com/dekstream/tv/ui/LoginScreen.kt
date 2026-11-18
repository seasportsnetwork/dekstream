package com.dekstream.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dekstream.tv.DekStreamApp
import com.dekstream.tv.data.Account
import com.dekstream.tv.data.XtreamApi
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    val app = DekStreamApp.instance
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf("http://") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row {
                Text("Dek", color = DS.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("Stream", color = DS.Accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            Text("Sign in with your Xtream Codes login", color = DS.TextDim, fontSize = 15.sp)

            val colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = DS.Accent, unfocusedBorderColor = DS.Surface2,
                focusedLabelColor = DS.Accent, cursorColor = DS.Accent,
            )
            OutlinedTextField(
                value = server, onValueChange = { server = it }, label = { Text("Server URL") },
                singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it }, label = { Text("Username") },
                singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = pass, onValueChange = { pass = it }, label = { Text("Password") },
                singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            )
            error?.let { Text(it, color = DS.Live, fontSize = 14.sp) }
            Button(
                onClick = {
                    error = null; busy = true
                    scope.launch {
                        val acct = Account(server.trim(), user.trim(), pass.trim())
                        try {
                            val info = XtreamApi(app.http, acct).login()
                            app.useAccount(acct)
                            app.prefs.serverTimezone = info.timezone
                            onSignedIn()
                        } catch (e: Exception) {
                            error = e.message ?: "Couldn't sign in"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && user.isNotBlank() && pass.isNotBlank() && server.length > 8,
                colors = ButtonDefaults.buttonColors(containerColor = DS.Accent),
                modifier = Modifier.fillMaxWidth().height(52.dp).focusRing(),
            ) {
                if (busy) CircularProgressIndicator(color = DS.Text, modifier = Modifier.height(22.dp))
                else Text("Sign in", fontSize = 16.sp)
            }
        }
    }
}
