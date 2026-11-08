package com.abybijo.openflow

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

// ---------- Theme (from the HTML spec) ----------

private val Accent = Color(0xFF5B5BF0)
private val Accent2 = Color(0xFF8B5CF6)
private val Green = Color(0xFF16A34A)
private val Red = Color(0xFFEF4444)

private val LightScheme = lightColorScheme(
    primary = Accent,
    secondary = Accent2,
    background = Color(0xFFF5F5FA),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF14142B),
    onSurface = Color(0xFF14142B),
    surfaceVariant = Color(0xFFE8E8F1),
    outline = Color(0xFFE8E8F1)
)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    secondary = Accent2,
    background = Color(0xFF0D0D18),
    surface = Color(0xFF181829),
    onBackground = Color(0xFFF2F2FA),
    onSurface = Color(0xFFF2F2FA),
    surfaceVariant = Color(0xFF272740),
    outline = Color(0xFF272740)
)

@Composable
fun OpenFlowTheme(isDark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isDark) DarkScheme else LightScheme,
        content = content
    )
}

// ---------- Shared components ----------

@Composable
fun ColumnScope.AnimItem(index: Int, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(450, delayMillis = index * 55)) +
            slideInVertically(tween(600, delayMillis = index * 55)) { it / 3 }
    ) {
        content()
    }
}

@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconRes: Int? = null
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(listOf(Accent, Accent2)),
                RoundedCornerShape(18.dp)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (iconRes != null) {
                Spacer(Modifier.width(8.dp))
                Image(
                    painter = painterResource(iconRes), contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White)
                )
            }
        }
    }
    if (!enabled) {
        // Keep the disabled state visible: dim via a translucent overlay is
        // handled by callers showing the button greyed through alpha.
    }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, iconRes: Int? = null) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold
            )
            if (iconRes != null) {
                Spacer(Modifier.width(8.dp))
                Image(
                    painter = painterResource(iconRes), contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        onClick = { onClick?.invoke() }
    ) {
        Column(modifier = Modifier.padding(18.dp), content = content)
    }
}

@Composable
fun IconTile(iconRes: Int, size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(Accent.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes), contentDescription = null,
            modifier = Modifier.size(24.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Accent)
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), letterSpacing = 0.4.sp,
        modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
fun ProgressDots(current: Int, total: Int = 5) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        repeat(total) { i ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (i < current) Accent else MaterialTheme.colorScheme.outline)
            )
        }
    }
}

@Composable
fun UsageRing(percent: Int, size: Dp = 84.dp) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = 8.dp.toPx()
            drawArc(
                color = Color(0xFFE8E8F1),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = Accent,
                startAngle = -90f, sweepAngle = 360f * (percent / 100f), useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Text("$percent%", fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

fun greeting(): String {
    return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 0..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
}

// ---------- App root ----------

@Composable
fun OpenFlowApp(
    vm: MainViewModel,
    prefs: Prefs,
    prefsReady: Boolean,
    isDark: Boolean,
    micGranted: Boolean,
    overlayGranted: Boolean,
    a11yGranted: Boolean,
    onRequestMic: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenA11ySettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onStartBubble: () -> Unit,
    onStopBubble: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopyText: (String) -> Unit,
    onToast: (String) -> Unit
) {
    var route by remember { mutableStateOf<Route>(if (prefs.onboardingDone) Route.Home else Route.Onboarding(0)) }
    // Once onboarding completes, move to Home.
    LaunchedEffect(prefs.onboardingDone) {
        if (prefs.onboardingDone && route is Route.Onboarding) route = Route.Home
    }
    OpenFlowTheme(isDark = isDark) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Until the stored preferences have actually loaded, show a
            // branded splash. Without this the first frame uses the default
            // prefs (onboarding not done) and flashes onboarding before
            // jumping to Home.
            if (!prefsReady) {
                SplashScreen()
                return@Surface
            }
            when (val r = route) {
                is Route.Onboarding -> OnboardingScreen(
                    step = r.step,
                    vm = vm,
                    micGranted = micGranted,
                    overlayGranted = overlayGranted,
                    a11yGranted = a11yGranted,
                    onRequestMic = onRequestMic,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onOpenA11ySettings = onOpenA11ySettings,
                    onOpenAppInfo = onOpenAppInfo,
                    onRefreshPermissions = onRefreshPermissions,
                    onOpenUrl = onOpenUrl,
                    onStep = { route = Route.Onboarding(it) },
                    onFinish = {
                        vm.completeOnboarding()
                        route = Route.Home
                    }
                )
                Route.Home -> MainScreen(
                    vm = vm, prefs = prefs, tab = 0,
                    onTab = { route = if (it == 0) Route.Home else Route.Settings },
                    onDemo = { route = Route.Demo },
                    onCopyText = onCopyText,
                    onStartBubble = onStartBubble,
                    onStopBubble = onStopBubble,
                    onToast = onToast
                )
                Route.Settings -> MainScreen(
                    vm = vm, prefs = prefs, tab = 1,
                    onTab = { route = if (it == 0) Route.Home else Route.Settings },
                    onDemo = { route = Route.Demo },
                    onCopyText = onCopyText,
                    onStartBubble = onStartBubble,
                    onStopBubble = onStopBubble,
                    onToast = onToast,
                    onAbout = { route = Route.About },
                    onChangeKey = { route = Route.ChangeKey }
                )
                Route.Demo -> DemoScreen(vm = vm, onClose = { route = Route.Home }, onRequestMic = onRequestMic, micGranted = micGranted, onToast = onToast)
                Route.About -> AboutScreen(onBack = { route = Route.Settings }, onOpenUrl = onOpenUrl)
                Route.ChangeKey -> KeyScreen(
                    vm = vm, isChange = true,
                    onDone = { route = Route.Settings },
                    onBack = { route = Route.Settings }
                )
            }
        }
    }
}

sealed class Route {
    data class Onboarding(val step: Int) : Route()
    object Home : Route()
    object Settings : Route()
    object Demo : Route()
    object About : Route()
    object ChangeKey : Route()
}

/** Branded launch splash: logo and name only, shown while stored prefs load. */
@Composable
fun SplashScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.openflow_logo),
            contentDescription = "OpenFlow logo",
            modifier = Modifier.size(92.dp).clip(RoundedCornerShape(22.dp)),
            contentScale = ContentScale.Crop
        )
        Text(
            "OpenFlow",
            fontSize = 28.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 20.dp)
        )
        Text(
            "Speak freely.",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

// ---------- Onboarding ----------

@Composable
fun OnboardingScreen(
    step: Int,
    vm: MainViewModel,
    micGranted: Boolean,
    overlayGranted: Boolean,
    a11yGranted: Boolean,
    onRequestMic: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenA11ySettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onStep: (Int) -> Unit,
    onFinish: () -> Unit
) {
    LaunchedEffect(Unit) { onRefreshPermissions() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp)
    ) {
        when (step) {
            0 -> WelcomeStep(onStep)
            1 -> GetKeyStep(onStep, onOpenUrl)
            2 -> ConsentStep(onStep)
            3 -> KeyScreen(vm = vm, isChange = false, onDone = { onStep(4) }, onBack = { onStep(2) })
            4 -> PermissionsStep(
                micGranted = micGranted,
                overlayGranted = overlayGranted,
                a11yGranted = a11yGranted,
                onRequestMic = onRequestMic,
                onOpenOverlaySettings = onOpenOverlaySettings,
                onOpenA11ySettings = onOpenA11ySettings,
                onOpenAppInfo = onOpenAppInfo,
                onFinish = onFinish
            )
        }
    }
}

@Composable
fun WelcomeStep(onStep: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        AnimItem(0) {
            Image(
                painter = painterResource(R.drawable.openflow_logo),
                contentDescription = "OpenFlow logo",
                modifier = Modifier.size(92.dp).clip(RoundedCornerShape(22.dp)),
                contentScale = ContentScale.Crop
            )
        }
        AnimItem(1) {
            Text(
                "Speak freely.\nOpenFlow types.",
                fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 28.dp)
            )
        }
        AnimItem(2) {
            Text(
                "Dictate in any app with a floating bubble, powered by your own free Groq key.",
                fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 14.dp)
            )
        }
        AnimItem(3) {
            Surface(
                shape = RoundedCornerShape(99.dp),
                color = Green.copy(alpha = 0.13f)
            ) {
                Text(
                    "Free forever. No ads. No subscription.",
                    color = Green, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        AnimItem(4) {
            GradientButton(text = "Get started", onClick = { onStep(1) }, iconRes = R.drawable.ic_arrow_right)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun GetKeyStep(onStep: (Int) -> Unit, onOpenUrl: (String) -> Unit) {
    Column {
        AnimItem(0) { ProgressDots(current = 2) }
        AnimItem(1) {
            Text("Get your free Groq key", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
        }
        AnimItem(2) {
            Text("It takes about a minute.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        }
        AnimItem(3) {
            AppCard {
                listOf(
                    "Open the Groq Console in your browser.",
                    "Tap Continue with Google to create your account.",
                    "Go to API Keys and tap Create API Key.",
                    "Copy the key that starts with gsk_."
                ).forEachIndexed { i, text ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 13.dp)) {
                        Box(
                            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(Accent),
                            contentAlignment = Alignment.Center
                        ) { Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                        Spacer(Modifier.width(14.dp))
                        Text(text, fontSize = 16.sp)
                    }
                    if (i < 3) Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outline))
                }
            }
        }
        AnimItem(4) {
            Spacer(Modifier.height(8.dp))
            GhostButton(text = "Open Groq Console", onClick = { onOpenUrl("https://console.groq.com/keys") }, modifier = Modifier.fillMaxWidth(), iconRes = R.drawable.ic_external)
        }
        Spacer(Modifier.weight(1f))
        AnimItem(5) {
            GradientButton(text = "I have my key", onClick = { onStep(2) })
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun ConsentStep(onStep: (Int) -> Unit) {
    var checked by remember { mutableStateOf(false) }
    Column {
        AnimItem(0) { ProgressDots(current = 3) }
        AnimItem(1) {
            Text("Your audio and Groq", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
        }
        AnimItem(2) {
            Text("Please read this before continuing.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        }
        AnimItem(3) {
            AppCard {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(R.drawable.ic_mic)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Audio is sent to Groq", fontWeight = FontWeight.Bold)
                        Text("Your recording is sent to Groq to be turned into text. OpenFlow has no servers and never stores your audio.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                    }
                }
            }
        }
        AnimItem(4) {
            AppCard {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(R.drawable.ic_clock)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Up to 8 hours a day, free", fontWeight = FontWeight.Bold)
                        Text("Groq’s free tier covers about 8 hours of dictation daily. Far more than most people need.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                    }
                }
            }
        }
        AnimItem(5) {
            AppCard {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(R.drawable.ic_lock)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Your key stays on your phone", fontWeight = FontWeight.Bold)
                        Text("It’s stored securely on this device and only used to talk to Groq.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                    }
                }
            }
        }
        AnimItem(6) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 14.dp).clickable { checked = !checked }
            ) {
                Checkbox(
                    checked = checked, onCheckedChange = { checked = it },
                    colors = CheckboxDefaults.colors(checkedColor = Accent)
                )
                Text("I understand my audio will be sent to Groq for dictation.", fontSize = 14.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        AnimItem(7) {
            Box(modifier = Modifier.fillMaxWidth()) {
                GradientButton(text = "Continue", onClick = { onStep(3) }, enabled = checked)
                if (!checked) {
                    Box(modifier = Modifier.matchParentSize().background(Color.Transparent))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun KeyScreen(vm: MainViewModel, isChange: Boolean, onDone: () -> Unit, onBack: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var messageOk by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var shakeTrigger by remember { mutableIntStateOf(0) }
    var showKey by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0) {
            listOf(-8f, 8f, -8f, 8f, 0f).forEach { x ->
                shake.animateTo(x, animationSpec = tween(60))
            }
        }
    }
    Column {
        if (!isChange) AnimItem(0) { ProgressDots(current = 4) }
        AnimItem(1) {
            Text(
                if (isChange) "Change your Groq API key" else "Paste your API key",
                fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp)
            )
        }
        AnimItem(2) {
            Text(
                "Paste the key you copied from the Groq Console.",
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
            )
        }
        AnimItem(3) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim(); message = "" },
                placeholder = { Text("gsk_...") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    Text(
                        if (showKey) "Hide" else "Show",
                        color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.clickable { showKey = !showKey }.padding(8.dp)
                    )
                },
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.fillMaxWidth().graphicsLayer { translationX = shake.value }
            )
        }
        AnimItem(4) {
            Text(
                message,
                color = if (messageOk) Green else Red,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 4.dp, top = 12.dp).height(22.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        AnimItem(5) {
            GradientButton(
                text = if (verifying) "Verifying…" else "Verify and continue",
                onClick = {
                    if (verifying) return@GradientButton
                    val candidate = key.trim()
                    if (!candidate.startsWith("gsk_") || candidate.length <= 12) {
                        messageOk = false
                        message = "Groq keys start with gsk_. Please check and try again."
                        shakeTrigger++
                        return@GradientButton
                    }
                    verifying = true
                    message = ""
                    scope.launch {
                        when (val result = GroqClient.verifyKey(candidate)) {
                            is VerifyResult.Ok -> {
                                vm.saveKey(candidate)
                                messageOk = true
                                message = "Key verified"
                                verifying = false
                                delay(700)
                                onDone()
                            }
                            is VerifyResult.InvalidKey -> {
                                messageOk = false
                                message = "Invalid key — please check and try again."
                                verifying = false
                                shakeTrigger++
                            }
                            is VerifyResult.Error -> {
                                messageOk = false
                                message = result.message
                                verifying = false
                                shakeTrigger++
                            }
                        }
                    }
                }
            )
        }
        if (isChange) {
            Spacer(Modifier.height(8.dp))
            AnimItem(6) {
                GhostButton(text = "Back to Settings", onClick = onBack, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun PermissionsStep(
    micGranted: Boolean,
    overlayGranted: Boolean,
    a11yGranted: Boolean,
    onRequestMic: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenA11ySettings: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onFinish: () -> Unit
) {
    Column {
        AnimItem(0) { ProgressDots(current = 5) }
        AnimItem(1) {
            Text("Two quick permissions", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
        }
        AnimItem(2) {
            Text("These let the bubble work over your other apps.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        }
        AnimItem(3) {
            AppCard {
                PermissionRow(
                    iconRes = R.drawable.ic_mic, title = "Microphone",
                    subtitle = "To hear what you say", granted = micGranted, onClick = onRequestMic
                )
                Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outline))
                PermissionRow(
                    iconRes = R.drawable.ic_layers, title = "Display over other apps",
                    subtitle = "To show the flow bubble", granted = overlayGranted, onClick = onOpenOverlaySettings
                )
            }
        }
        AnimItem(4) {
            AppCard {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(R.drawable.ic_info)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Accessibility (optional)", fontWeight = FontWeight.Bold)
                        Text(
                            "This is what lets dictated text land at the cursor in other apps. It only reads the field that has focus when a dictation is ready. It never reads passwords or banking apps. Without it, dictation falls back to the clipboard.",
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (a11yGranted) "Accessibility is on" else "Open Accessibility Settings",
                            color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                            modifier = Modifier.clickable { onOpenA11ySettings() }
                        )
                        if (!a11yGranted) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "If Android shows “Controlled by restricted setting” for OpenFlow, it is because the app was installed directly (not from the Play Store). Fix it in one minute:",
                                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "1. Open App info below.\n2. Tap the ⋮ menu at the top right.\n3. Tap “Allow restricted settings”.\n4. Come back and open Accessibility Settings.",
                                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Open App info",
                                color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                modifier = Modifier.clickable { onOpenAppInfo() }
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        AnimItem(5) {
            GradientButton(
                text = "Finish setup", onClick = onFinish,
                enabled = micGranted && overlayGranted
            )
            if (!micGranted || !overlayGranted) {
                Text(
                    "Microphone and “Display over other apps” are needed to finish setup.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun PermissionRow(iconRes: Int, title: String, subtitle: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 13.dp).clickable(onClick = onClick)
    ) {
        IconTile(iconRes)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
        Switch(
            checked = granted, onCheckedChange = { onClick() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = MaterialTheme.colorScheme.outline
            )
        )
    }
}

// ---------- Home / Settings ----------

@Composable
fun MainScreen(
    vm: MainViewModel,
    prefs: Prefs,
    tab: Int,
    onTab: (Int) -> Unit,
    onDemo: () -> Unit,
    onCopyText: (String) -> Unit,
    onStartBubble: () -> Unit,
    onStopBubble: () -> Unit,
    onToast: (String) -> Unit,
    onAbout: () -> Unit = {},
    onChangeKey: () -> Unit = {}
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(22.dp)) {
            if (tab == 0) {
                HomeContent(vm, prefs, onDemo, onCopyText, onStartBubble, onStopBubble, onToast)
            } else {
                SettingsContent(vm, prefs, context, onAbout, onChangeKey)
            }
        }
        // Bottom tabs, matching the HTML: pill highlight on the active tab.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .padding(vertical = 10.dp)
        ) {
            BottomTab(
                label = "Home", iconRes = R.drawable.ic_home, selected = tab == 0,
                onClick = { onTab(0) }, modifier = Modifier.weight(1f)
            )
            BottomTab(
                label = "Settings", iconRes = R.drawable.ic_settings, selected = tab == 1,
                onClick = { onTab(1) }, modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun BottomTab(label: String, iconRes: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp, 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) Accent.copy(alpha = 0.18f) else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes), contentDescription = null,
                modifier = Modifier.size(24.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                    if (selected) Accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            )
        }
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun HomeContent(
    vm: MainViewModel,
    prefs: Prefs,
    onDemo: () -> Unit,
    onCopyText: (String) -> Unit,
    onStartBubble: () -> Unit,
    onStopBubble: () -> Unit,
    onToast: (String) -> Unit
) {
    Column {
        AnimItem(0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.openflow_logo),
                    contentDescription = "OpenFlow logo",
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(greeting(), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                    Text("OpenFlow", fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp)
                }
            }
        }
        AnimItem(1) {
            Spacer(Modifier.height(18.dp))
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(R.drawable.ic_mic)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Flow bubble", fontWeight = FontWeight.Bold)
                        Text(
                            if (prefs.bubbleEnabled) "On. Floats over every app" else "Off",
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                    Switch(
                        checked = prefs.bubbleEnabled,
                        onCheckedChange = { on ->
                            vm.setBubbleEnabled(on)
                            if (on) onStartBubble() else onStopBubble()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White, checkedTrackColor = Accent,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }
            }
        }
        AnimItem(2) {
            val minutes = prefs.usageSeconds / 60
            val percent = ((prefs.usageSeconds / 28800f) * 100).toInt().coerceIn(0, 100)
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UsageRing(percent = percent)
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text("Free usage today", fontWeight = FontWeight.Bold)
                        Text("$minutes min of 8 hours", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                    }
                }
            }
        }
        AnimItem(3) {
            Spacer(Modifier.height(6.dp))
            GradientButton(text = "Try the bubble", onClick = onDemo, iconRes = R.drawable.ic_arrow_right)
        }
        AnimItem(4) {
            AppCard {
                Text("How to dictate", fontWeight = FontWeight.Bold)
                Text(
                    "Tap the bubble in any text field, speak, then tap again. Your words appear as text.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
        AnimItem(5) {
            SectionLabel("Recent")
            if (prefs.recent.isEmpty()) {
                Text(
                    "Nothing yet. Try the bubble above.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            } else {
                prefs.recent.forEach { entry ->
                    AppCard(onClick = { onCopyText(entry.text) }) {
                        Text(entry.text, fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun SettingsContent(
    vm: MainViewModel,
    prefs: Prefs,
    context: Context,
    onAbout: () -> Unit,
    onChangeKey: () -> Unit
) {
    var previewRecording by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column {
        AnimItem(0) {
            Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        AnimItem(1) { SectionLabel("Flow bubble") }
        AnimItem(2) {
            AppCard {
                // Live preview, matching the HTML preview area. The whole
                // preview is tappable — it plays the recording animation.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .clickable {
                            previewRecording = true
                            scope.launch {
                                delay(2200)
                                previewRecording = false
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    BubblePreview(prefs = prefs, recording = previewRecording)
                }
                Text(
                    "Tap to preview", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                Spacer(Modifier.height(12.dp))
                // Bubble size: named presets, not a slider.
                Text("Size", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                SegmentedControl(
                    options = BUBBLE_SIZE_NAMES,
                    selected = nearestPresetIndex(prefs.bubbleSize, BUBBLE_SIZE_PRESETS),
                    onSelect = { vm.setBubbleSize(BUBBLE_SIZE_PRESETS[it]) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                // Opacity: named presets, not a slider.
                Text("Opacity", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                SegmentedControl(
                    options = BUBBLE_OPACITY_NAMES,
                    selected = nearestPresetIndex(prefs.bubbleOpacity, BUBBLE_OPACITY_PRESETS),
                    onSelect = { vm.setBubbleOpacity(BUBBLE_OPACITY_PRESETS[it]) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Color", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    BUBBLE_COLORS.forEachIndexed { i, colors ->
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(19.dp))
                                .background(Brush.linearGradient(listOf(Color(colors.first), Color(colors.second))))
                                .border(
                                    if (prefs.bubbleColor == i) 3.dp else 0.dp,
                                    if (prefs.bubbleColor == i) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    RoundedCornerShape(19.dp)
                                )
                                .clickable { vm.setBubbleColor(i) }
                        )
                    }
                }
                // Shape: label above the control, so the label can never be
                // squeezed into a vertical sliver by the segmented control.
                Text("Shape", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                SegmentedControl(
                    options = listOf("Circle", "Pill"),
                    selected = if (prefs.bubbleShape == "pill") 1 else 0,
                    onSelect = { vm.setBubbleShape(if (it == 1) "pill" else "circle") },
                    modifier = Modifier.fillMaxWidth()
                )
                SettingRow(
                    title = "Snap to screen edge", subtitle = "Settles against the nearest side",
                    content = {
                        Switch(
                            checked = prefs.snapToEdge, onCheckedChange = { vm.setSnapToEdge(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Accent, uncheckedThumbColor = Color.White, uncheckedTrackColor = MaterialTheme.colorScheme.outline)
                        )
                    }
                )
                SettingRow(
                    title = "Show waveform", subtitle = "Animate while listening",
                    content = {
                        Switch(
                            checked = prefs.showWaveform, onCheckedChange = { vm.setShowWaveform(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Accent, uncheckedThumbColor = Color.White, uncheckedTrackColor = MaterialTheme.colorScheme.outline)
                        )
                    }
                )
            }
        }
        AnimItem(3) { SectionLabel("Dictation") }
        AnimItem(4) {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(R.drawable.ic_key)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Groq API key", fontWeight = FontWeight.Bold)
                        Text(KeyStore.maskedKey(context), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
                    }
                    GhostButton(text = "Change", onClick = onChangeKey, modifier = Modifier.width(110.dp))
                }
            }
        }
        AnimItem(5) {
            AppCard {
                SettingRow(
                    title = "Auto-punctuation",
                    content = {
                        Switch(
                            checked = prefs.autoPunctuation, onCheckedChange = { vm.setAutoPunctuation(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Accent, uncheckedThumbColor = Color.White, uncheckedTrackColor = MaterialTheme.colorScheme.outline)
                        )
                    }
                )
                Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outline))
                SettingRow(
                    title = "Haptic feedback",
                    content = {
                        Switch(
                            checked = prefs.haptic, onCheckedChange = { vm.setHaptic(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Accent, uncheckedThumbColor = Color.White, uncheckedTrackColor = MaterialTheme.colorScheme.outline)
                        )
                    }
                )
            }
        }
        AnimItem(6) {
            AppCard {
                Text("Accessibility", fontWeight = FontWeight.Bold)
                Text(
                    "This is what lets dictated text land at the cursor in other apps. If Android shows “Controlled by restricted setting” for OpenFlow, open App info, tap the ⋮ menu, tap “Allow restricted settings”, then enable OpenFlow in Accessibility Settings.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Open App info",
                    color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Open Accessibility Settings",
                    color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                )
            }
        }
        AnimItem(7) { SectionLabel("Appearance") }
        AnimItem(8) {
            AppCard {
                Text("Theme", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                SegmentedControl(
                    options = listOf("System", "Light", "Dark"),
                    selected = when (prefs.theme) { "light" -> 1; "dark" -> 2; else -> 0 },
                    onSelect = {
                        vm.setTheme(when (it) { 1 -> "light"; 2 -> "dark"; else -> "system" })
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Light is white mode, Dark is dark mode. System follows your phone.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        AnimItem(9) {
            AppCard(onClick = onAbout) {
                Text("About OpenFlow", fontWeight = FontWeight.Bold)
                Text(
                    "Free, always. Audio is processed by Groq. We never see or store it.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 13.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }
        content()
    }
}

/** Index of the preset closest to a stored value (older builds saved raw slider values). */
private fun nearestPresetIndex(value: Int, presets: List<Int>): Int {
    return presets.indices.minByOrNull { kotlin.math.abs(presets[it] - value) } ?: 1
}

@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(3.dp)
    ) {
        options.forEachIndexed { i, label ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (i == selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (i == selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun BubblePreview(prefs: Prefs, recording: Boolean) {
    val colors = BUBBLE_COLORS[prefs.bubbleColor]
    val size = prefs.bubbleSize.dp
    val width = if (prefs.bubbleShape == "pill" || recording) size * 1.9f else size
    Box(
        modifier = Modifier
            .size(width, size)
            .clip(RoundedCornerShape(size / 2))
            .background(
                if (recording) Brush.linearGradient(listOf(Red, Red))
                else Brush.linearGradient(listOf(Color(colors.first), Color(colors.second)))
            ),
        contentAlignment = Alignment.Center
    ) {
        if (recording && prefs.showWaveform) {
            // Animated waveform: each bar breathes on its own phase, so the
            // recording state is visibly alive — never a static picture.
            val transition = rememberInfiniteTransition(label = "waveform")
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(6, 14, 24, 12, 18).forEachIndexed { i, h ->
                    val scale by transition.animateFloat(
                        initialValue = 0.45f,
                        targetValue = 1.6f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 620, delayMillis = i * 110),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "waveBar$i"
                    )
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height((h * scale).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White)
                    )
                }
            }
        } else {
            Image(
                painter = painterResource(R.drawable.ic_mic), contentDescription = null,
                modifier = Modifier.size(size * 0.45f),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White)
            )
        }
    }
}

// ---------- Demo screen (in-app "Messages, Sam") ----------

@Composable
fun DemoScreen(vm: MainViewModel, onClose: () -> Unit, onRequestMic: () -> Unit, micGranted: Boolean, onToast: (String) -> Unit) {
    val context = LocalContext.current
    val prefs by vm.prefs.collectAsState()
    var fieldText by remember { mutableStateOf("") }
    var fieldHint by remember { mutableStateOf("Type a message") }
    var bubbleState by remember { mutableStateOf(0) } // 0 idle, 1 recording, 2 transcribing
    val scope = rememberCoroutineScope()
    val recorder = remember { WavRecorder() }
    var recordingFile by remember { mutableStateOf<File?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(22.dp)) {
        AnimItem(0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Messages, Sam", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                GhostButton(text = "Close", onClick = onClose, modifier = Modifier.width(110.dp))
            }
        }
        AnimItem(1) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Text("Where are you?", modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp))
                }
                Surface(
                    shape = RoundedCornerShape(20.dp), color = Accent,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Almost there!", color = Color.White, modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp))
                }
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Text("Can you pick up coffee?", modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp))
                }
                Text(
                    "This is a demo of another app. Tap the bubble to dictate.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
        AnimItem(2) {
            // The in-app floating bubble, draggable horizontally like the real one.
            // The tap target IS the bubble itself: it wraps the preview, so
            // the whole pill — including the wide recording state — is
            // tappable. A fixed 72 dp box used to miss most of the pill.
            Box(modifier = Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.CenterEnd) {
                Box(
                    modifier = Modifier.clickable {
                        when (bubbleState) {
                                0 -> {
                                    if (!micGranted) {
                                        onRequestMic()
                                        onToast("Allow the microphone, then tap the bubble again.")
                                        return@clickable
                                    }
                                    val file = File(context.cacheDir, "demo_recording.wav")
                                    if (recorder.start(file)) {
                                        recordingFile = file
                                        bubbleState = 1
                                        fieldHint = "Listening..."
                                        fieldText = ""
                                    } else {
                                        onToast("Could not start the microphone.")
                                    }
                                }
                                1 -> {
                                    bubbleState = 2
                                    fieldHint = "Transcribing..."
                                    val file = recordingFile ?: return@clickable
                                    val seconds = recorder.stop(file)
                                    scope.launch {
                                        when (val result = vm.transcribeFile(file)) {
                                            is TranscribeResult.Ok -> {
                                                val text = vm.applyPunctuation(result.text)
                                                // Type the text out, like the HTML demo.
                                                fieldHint = ""
                                                fieldText = ""
                                                for (i in text.indices) {
                                                    fieldText = text.substring(0, i + 1)
                                                    delay(26)
                                                }
                                                vm.recordDictation(result.text, seconds.toInt())
                                                bubbleState = 0
                                                fieldHint = "Type a message"
                                            }
                                            is TranscribeResult.InvalidKey -> {
                                                bubbleState = 0
                                                fieldHint = "Type a message"
                                                onToast("Groq key invalid — check it in Settings.")
                                            }
                                            is TranscribeResult.RateLimited -> {
                                                bubbleState = 0
                                                fieldHint = "Type a message"
                                                onToast("Groq daily limit reached — try tomorrow.")
                                            }
                                            is TranscribeResult.Error -> {
                                                bubbleState = 0
                                                fieldHint = "Type a message"
                                                onToast(result.message)
                                            }
                                        }
                                    }
                                }
                                else -> { /* ignore taps while transcribing */ }
                            }
                        }
                    ) {
                        BubblePreview(
                            prefs = prefs,
                            recording = bubbleState == 1 || bubbleState == 2
                        )
                    }
                }
            }
        AnimItem(3) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, if (bubbleState == 1) Accent else MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (fieldText.isNotEmpty()) fieldText else fieldHint,
                    color = if (fieldText.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------- About page ----------

@Composable
fun AboutScreen(onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
        AnimItem(0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Back", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    modifier = Modifier.clickable { onBack() }.padding(4.dp)
                )
            }
        }
        AnimItem(1) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Image(
                    painter = painterResource(R.drawable.openflow_logo),
                    contentDescription = "OpenFlow logo",
                    modifier = Modifier.size(92.dp).clip(RoundedCornerShape(22.dp)),
                    contentScale = ContentScale.Crop
                )
                Text("About OpenFlow", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
                Text("Version 2.0.1", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }
        AnimItem(2) {
            AppCard {
                Text("Free, always. Audio is processed by Groq. We never see or store it.", fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "OpenFlow is a free, open-source voice dictation app. Speak in any app with the floating bubble, and your words appear as text — powered by your own free Groq API key. No account, no ads, no subscription.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
        AnimItem(3) {
            AppCard {
                Text("Developer", fontWeight = FontWeight.Bold)
                Text("Aby Bijo", fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "Android and web developer. Computer Applications student, Mar Augustinos College, Ramapuram, Kottayam, Kerala.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
        AnimItem(4) { SectionLabel("Links") }
        AnimItem(5) {
            AppCard {
                LinkRow("GitHub", "github.com/AbyBijo") { onOpenUrl("https://github.com/AbyBijo") }
                Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outline))
                LinkRow("LinkedIn", "linkedin.com/in/aby-bijo-102359282") { onOpenUrl("https://www.linkedin.com/in/aby-bijo-102359282") }
                Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outline))
                LinkRow("Repository", "github.com/AbyBijo/open-flow") { onOpenUrl("https://github.com/AbyBijo/open-flow") }
                Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outline))
                LinkRow("Buy Me a Coffee", "buymeacoffee.com/AbyBijo") { onOpenUrl("https://www.buymeacoffee.com/AbyBijo") }
            }
        }
        AnimItem(6) {
            Text(
                "OpenFlow is free software under the MIT License. Groq and Whisper are trademarks of their respective owners.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 13.dp).clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        }
        Image(painter = painterResource(R.drawable.ic_external), contentDescription = null, modifier = Modifier.size(20.dp))
    }
}
