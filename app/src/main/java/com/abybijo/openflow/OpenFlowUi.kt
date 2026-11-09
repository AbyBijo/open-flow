package com.abybijo.openflow

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
        enter = fadeIn(tween(300, delayMillis = index * 55)) +
            slideInVertically(tween(300, delayMillis = index * 55)) { it / 3 }
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(150), label = "pressScale"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer {
                alpha = if (enabled) 1f else 0.35f
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(listOf(Accent, Accent2)),
                RoundedCornerShape(18.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick),
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(150), label = "pressScale"
    )
    Box(
        modifier = modifier
            .height(56.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
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

/**
 * A history tile: AppCard with a stronger edge, so each tile reads as its
 * own card sitting below the last one. In dark mode AppCard's outline is
 * too close to the background and the tiles blur into one stack.
 */
@Composable
fun HistoryTile(entry: RecentEntry, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(entry.text, fontSize = 16.sp)
            Text(
                formatRecentTime(entry.timestamp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
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

/** "Today, 7:42 PM" for today's entries, otherwise "8 Nov, 7:42 PM". Device locale, 12-hour clock. */
fun formatRecentTime(timestamp: Long): String {
    val entry = Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = Calendar.getInstance()
    val timePart = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
    val sameDay = now.get(Calendar.YEAR) == entry.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == entry.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) {
        "Today, $timePart"
    } else {
        val datePart = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))
        "$datePart, $timePart"
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
    // The current screen survives rotation AND process death: rememberSaveable
    // restores it when the activity is recreated, and the onboarding step is
    // also persisted to DataStore for the cases where saved state is gone
    // (e.g. the process was killed while the user was in Settings).
    var route by rememberSaveable(saver = routeStateSaver) {
        mutableStateOf(if (prefs.onboardingDone) Route.Home else Route.Onboarding(prefs.onboardingStep.coerceIn(0, 4)))
    }
    // Every onboarding step change is written to DataStore as well as the
    // navigation state, so the step survives even without saved state.
    val goToStep: (Int) -> Unit = { step ->
        route = Route.Onboarding(step)
        vm.setOnboardingStep(step)
    }
    // If saved instance state is gone (fresh launch after the process was
    // killed), fall back to the onboarding step stored in DataStore.
    var routeRestored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(prefsReady) {
        if (prefsReady && !routeRestored) {
            routeRestored = true
            if (!prefs.onboardingDone) {
                val savedStep = prefs.onboardingStep.coerceIn(0, 4)
                val current = route
                if (current is Route.Onboarding && current.step == 0 && savedStep != 0) {
                    route = Route.Onboarding(savedStep)
                }
            }
        }
    }
    // Once onboarding completes, move to Home.
    LaunchedEffect(prefs.onboardingDone) {
        if (prefs.onboardingDone && route is Route.Onboarding) route = Route.Home
    }
    // System Back: step back through onboarding, Demo/About/ChangeKey to
    // their parent screen, Settings to Home. Home keeps the default
    // behaviour (exits the app).
    val backAction: (() -> Unit)? = when (val r = route) {
        is Route.Onboarding -> if (r.step > 0) ({ goToStep(r.step - 1) }) else null
        Route.Demo -> ({ route = Route.Home })
        Route.About -> ({ route = Route.Settings })
        Route.ChangeKey -> ({ route = Route.Settings })
        Route.Settings -> ({ route = Route.Home })
        Route.Home -> null
    }
    BackHandler(enabled = backAction != null) { backAction?.invoke() }
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
            // Screens move with a direction-aware slide + fade. Forward
            // navigation slides the new screen in from the right; going
            // back slides it in from the left.
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    val forward = routeDepth(targetState) > routeDepth(initialState)
                    if (forward) {
                        (slideInHorizontally(animationSpec = tween(280, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(280, easing = FastOutSlowInEasing))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(280, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(280, easing = FastOutSlowInEasing)))
                    } else {
                        (slideInHorizontally(animationSpec = tween(280, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(280, easing = FastOutSlowInEasing))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(280, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(280, easing = FastOutSlowInEasing)))
                    }
                },
                label = "route"
            ) { r ->
            when (r) {
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
                    onStep = goToStep,
                    onFinish = {
                        vm.completeOnboarding()
                        vm.setOnboardingStep(0)
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
                    onToast = onToast,
                    a11yGranted = a11yGranted,
                    onOpenA11ySettings = onOpenA11ySettings,
                    onOpenAppInfo = onOpenAppInfo
                )
                Route.Settings -> MainScreen(
                    vm = vm, prefs = prefs, tab = 1,
                    onTab = { route = if (it == 0) Route.Home else Route.Settings },
                    onDemo = { route = Route.Demo },
                    onCopyText = onCopyText,
                    onStartBubble = onStartBubble,
                    onStopBubble = onStopBubble,
                    onToast = onToast,
                    a11yGranted = a11yGranted,
                    onOpenA11ySettings = onOpenA11ySettings,
                    onOpenAppInfo = onOpenAppInfo,
                    onAbout = { route = Route.About },
                    onChangeKey = { route = Route.ChangeKey }
                )
                Route.Demo -> DemoScreen(vm = vm, onClose = { route = Route.Home }, onRequestMic = onRequestMic, micGranted = micGranted, onToast = onToast)
                Route.About -> AboutScreen(onBack = { route = Route.Settings }, onOpenUrl = onOpenUrl)
                Route.ChangeKey -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 22.dp)
                ) {
                    KeyScreen(
                        vm = vm, isChange = true,
                        onDone = { route = Route.Settings },
                        onBack = { route = Route.Settings }
                    )
                }
            }
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

/** Encode a Route as a single string so it can live in saved instance state. */
private fun encodeRoute(route: Route): String = when (route) {
    is Route.Onboarding -> "onboarding:${route.step}"
    Route.Home -> "home"
    Route.Settings -> "settings"
    Route.Demo -> "demo"
    Route.About -> "about"
    Route.ChangeKey -> "changekey"
}

private fun decodeRoute(encoded: String): Route = when {
    encoded.startsWith("onboarding:") ->
        Route.Onboarding(encoded.removePrefix("onboarding:").toIntOrNull()?.coerceIn(0, 4) ?: 0)
    encoded == "settings" -> Route.Settings
    encoded == "demo" -> Route.Demo
    encoded == "about" -> Route.About
    encoded == "changekey" -> Route.ChangeKey
    else -> Route.Home
}

/** Saver for the navigation state, so rotation and process death keep the screen. */
private val routeStateSaver: Saver<MutableState<Route>, String> = Saver(
    save = { state -> encodeRoute(state.value) },
    restore = { encoded -> mutableStateOf(decodeRoute(encoded)) }
)

/** Deeper in the app = higher number. Used to pick the slide direction. */
private fun routeDepth(route: Route): Int = when (route) {
    is Route.Onboarding -> route.step
    Route.Home -> 5
    Route.Settings -> 6
    Route.Demo, Route.About, Route.ChangeKey -> 7
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
    // The step content scrolls; the action buttons live outside the scroll
    // area, pinned to the bottom, so they are always visible without
    // scrolling. imePadding keeps the keyboard from covering them.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 22.dp)
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
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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
            Spacer(Modifier.height(8.dp))
        }
        AnimItem(5) {
            GradientButton(text = "I have my key", onClick = { onStep(2) })
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun ConsentStep(onStep: (Int) -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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
            Spacer(Modifier.height(8.dp))
        }
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
    // The pasted key and the verify state survive rotation, so a recreated
    // activity never wipes what the user just pasted.
    var key by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var messageOk by rememberSaveable { mutableStateOf(false) }
    var verifying by rememberSaveable { mutableStateOf(false) }
    var verified by rememberSaveable { mutableStateOf(false) }
    var shakeTrigger by rememberSaveable { mutableIntStateOf(0) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(shakeTrigger) {
        if (shakeTrigger > 0) {
            listOf(-8f, 8f, -8f, 8f, 0f).forEach { x ->
                shake.animateTo(x, animationSpec = tween(60))
            }
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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
            if (verified) {
                // The key box is gone the moment verification succeeds: only
                // the masked key and the confirmation remain.
                AnimItem(3) {
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("gsk_••••••••", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.width(10.dp))
                            Text("Key verified", color = Green, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }
            } else {
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
            }
            Spacer(Modifier.height(8.dp))
        }
        AnimItem(5) {
            GradientButton(
                text = if (verifying) "Verifying…" else if (verified) "Key verified" else "Verify and continue",
                onClick = {
                    if (verifying || verified) return@GradientButton
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
                                verified = true
                                keyboardController?.hide()
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
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
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
            Spacer(Modifier.height(8.dp))
        }
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
    a11yGranted: Boolean = true,
    onOpenA11ySettings: () -> Unit = {},
    onOpenAppInfo: () -> Unit = {},
    onAbout: () -> Unit = {},
    onChangeKey: () -> Unit = {}
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Box(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(22.dp)) {
            if (tab == 0) {
                HomeContent(vm, prefs, onDemo, onCopyText, onStartBubble, onStopBubble, onToast, a11yGranted, onOpenA11ySettings, onOpenAppInfo)
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
                .navigationBarsPadding()
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
    onToast: (String) -> Unit,
    a11yGranted: Boolean,
    onOpenA11ySettings: () -> Unit,
    onOpenAppInfo: () -> Unit
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
        if (!a11yGranted) {
            AnimItem(1) {
                Spacer(Modifier.height(18.dp))
                AppCard {
                    Text("Paste at cursor is off", fontWeight = FontWeight.Bold)
                    Text(
                        "Dictation copies to the clipboard until OpenFlow is switched on in Accessibility. Because the app was installed directly (not from the Play Store), Android first blocks that switch — allow it in App info, then switch OpenFlow on:\n\n1. Open App info below.\n2. Tap the ⋮ menu at the top right.\n3. Tap “Allow restricted settings”.\n4. Come back and open Accessibility Settings.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )
                    GradientButton(text = "Open App info", onClick = onOpenAppInfo)
                    Spacer(Modifier.height(8.dp))
                    GhostButton(text = "Open Accessibility Settings", onClick = onOpenA11ySettings, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        AnimItem(2) {
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
        AnimItem(3) {
            val minutes = prefs.usageSeconds / 60
            val percent = ((prefs.usageSeconds / 28800f) * 100).toInt().coerceIn(0, 100)
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UsageRing(percent = percent)
                    Spacer(Modifier.width(18.dp))
                    Column {
                        Text("Free usage today", fontWeight = FontWeight.Bold)
                        Text(
                            if (prefs.usageSeconds < 60) "${prefs.usageSeconds} sec of 8 hours" else "$minutes min of 8 hours",
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                }
            }
        }
        AnimItem(4) {
            Spacer(Modifier.height(6.dp))
            GradientButton(text = "Try the bubble", onClick = onDemo, iconRes = R.drawable.ic_arrow_right)
        }
        AnimItem(5) {
            AppCard {
                Text("How to dictate", fontWeight = FontWeight.Bold)
                Text(
                    "Tap the bubble in any text field, speak, then tap again. Your words appear as text.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
        }
        AnimItem(6) {
            SectionLabel("Recent")
            if (prefs.recent.isEmpty()) {
                Text(
                    "Nothing yet. Try the bubble above.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }
        if (prefs.recent.isNotEmpty()) {
            // Each tile animates in on its own, one after another, so the
            // list reads as tiles stacked below each other — never as one
            // block sliding in from behind. The stagger is capped so a
            // long list does not keep late tiles waiting for seconds.
            // Keyed by timestamp: adding a dictation composes and animates
            // only the new tile — existing tiles keep their state and
            // never re-animate or jump.
            prefs.recent.forEachIndexed { index, entry ->
                key(entry.timestamp) {
                    AnimItem(7 + minOf(index, 12)) {
                        HistoryTile(entry = entry, onClick = { onCopyText(entry.text) })
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        Spacer(Modifier.height(32.dp))
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
    var previewTranscribing by remember { mutableStateOf(false) }
    var previewResult by remember { mutableStateOf(BubblePreviewResult.NONE) }
    val downloadStates by vm.downloadStates.collectAsState()
    // A tap restarts the sequence; the run id stops an older sequence
    // from overwriting the new one after its delays.
    var previewRun by remember { mutableStateOf(0) }
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
                            val run = previewRun + 1
                            previewRun = run
                            previewRecording = true
                            previewTranscribing = false
                            previewResult = BubblePreviewResult.NONE
                            scope.launch {
                                delay(1500)
                                if (previewRun != run) return@launch
                                previewRecording = false
                                previewTranscribing = true
                                delay(1800)
                                if (previewRun != run) return@launch
                                previewTranscribing = false
                                previewResult = BubblePreviewResult.INSERTED
                                delay(600)
                                if (previewRun != run) return@launch
                                previewResult = BubblePreviewResult.COPIED
                                delay(1200)
                                if (previewRun != run) return@launch
                                previewResult = BubblePreviewResult.NONE
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    BubblePreview(prefs = prefs, recording = previewRecording, result = previewResult, transcribing = previewTranscribing)
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
                    title = "Clean up transcript",
                    subtitle = "Removes um and uh, fixes self-corrections",
                    content = {
                        Switch(
                            checked = prefs.cleanTranscript, onCheckedChange = { vm.setCleanTranscript(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Accent, uncheckedThumbColor = Color.White, uncheckedTrackColor = MaterialTheme.colorScheme.outline)
                        )
                    }
                )
                Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outline))
                SettingRow(
                    title = "Review before inserting",
                    subtitle = "Check the text before it goes in",
                    content = {
                        Switch(
                            checked = prefs.reviewBeforeInsert, onCheckedChange = { vm.setReviewBeforeInsert(it) },
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
        AnimItem(6) { SectionLabel("Transcription engine") }
        AnimItem(7) {
            AppCard {
                Text("Engine", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                SegmentedControl(
                    options = listOf("Cloud (Groq)", "On-device"),
                    selected = if (prefs.engine == "local") 1 else 0,
                    onSelect = { vm.setEngine(if (it == 1) "local" else "groq") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    engineStatusText(prefs, downloadStates),
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                if (prefs.engine == "local") {
                    Spacer(Modifier.height(12.dp))
                    Text("Models", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    ModelCatalog.all.forEach { model ->
                        val state = downloadStates[model.id] ?: ModelDownloadState.NotDownloaded
                        ModelRow(
                            model = model,
                            state = state,
                            selected = prefs.localModelId == model.id,
                            onSelect = { vm.setLocalModel(model.id) },
                            onDownload = { vm.downloadModel(model.id) },
                            onPause = { vm.pauseDownload(model.id) },
                            onCancel = { vm.cancelDownload(model.id) },
                            onDelete = { vm.deleteModel(model.id) }
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        "Models download once and live on this phone only. On-device dictation works offline and never uses Groq quota.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            }
        }
        AnimItem(8) {
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
        AnimItem(9) { SectionLabel("Appearance") }
        AnimItem(10) {
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
        AnimItem(11) {
            AppCard(onClick = onAbout) {
                Text("About OpenFlow", fontWeight = FontWeight.Bold)
                Text(
                    "Free, always. Audio is processed by Groq or on your device. We never see or store it.",
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

/** Result states the bubble preview can show after a dictation. */
enum class BubblePreviewResult { NONE, INSERTED, COPIED }

/** One row in the Settings model list: name, size, state, and its action. */
@Composable
private fun ModelRow(
    model: WhisperModel,
    state: ModelDownloadState,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.background)
            .clickable(enabled = state is ModelDownloadState.Downloaded) { onSelect() }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(model.name, fontWeight = FontWeight.SemiBold)
                    if (selected) {
                        Text(
                            "  ·  In use",
                            fontSize = 13.sp, color = Accent, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Text(
                    "${model.sizeMb} MB · ${model.blurb}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            when (state) {
                is ModelDownloadState.NotDownloaded -> ModelAction(text = "Download", onClick = onDownload)
                is ModelDownloadState.Downloading -> ModelAction(text = "Pause", onClick = onPause)
                is ModelDownloadState.Paused -> {
                    ModelAction(text = "Resume", onClick = onDownload)
                    Spacer(Modifier.width(12.dp))
                    ModelAction(text = "Cancel", onClick = onCancel)
                }
                is ModelDownloadState.Downloaded -> {
                    if (!selected) ModelAction(text = "Delete", onClick = onDelete)
                }
                is ModelDownloadState.Failed -> ModelAction(text = "Retry", onClick = onDownload)
            }
        }
        when (state) {
            is ModelDownloadState.Downloading -> {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = state.fraction,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${(state.fraction * 100).toInt()}%",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            is ModelDownloadState.Paused -> {
                Text(
                    "Paused — ${state.partialBytes / (1024 * 1024)} MB downloaded",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            is ModelDownloadState.Failed -> {
                Text(
                    state.message,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            else -> { }
        }
    }
}

@Composable
private fun ModelAction(text: String, onClick: () -> Unit) {
    Text(
        text, color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        modifier = Modifier.clickable { onClick() }.padding(4.dp)
    )
}

/** Status card line under the engine switcher: what is active right now. */
private fun engineStatusText(prefs: Prefs, downloadStates: Map<String, ModelDownloadState>): String {
    if (prefs.engine != "local") {
        return "Active: Cloud (Groq) — whisper-large-v3-turbo with your key."
    }
    val model = ModelCatalog.byId(prefs.localModelId)
    return when (downloadStates[model.id]) {
        ModelDownloadState.Downloaded ->
            "Active: On-device · ${model.name} (downloaded, ready offline)."
        is ModelDownloadState.Downloading ->
            "Active: On-device · ${model.name} (still downloading — Groq covers dictation until it lands)."
        is ModelDownloadState.Paused ->
            "Active: On-device · ${model.name} (download paused — Groq covers dictation until it lands)."
        else ->
            "Active: On-device · ${model.name} (not downloaded yet — Groq covers dictation until it lands)."
    }
}

@Composable
fun BubblePreview(prefs: Prefs, recording: Boolean, result: BubblePreviewResult = BubblePreviewResult.NONE, transcribing: Boolean = false) {
    val colors = BUBBLE_COLORS[prefs.bubbleColor]
    val size = prefs.bubbleSize.dp
    // The listening and thinking pills are longer (2.6x) so the
    // eleven-bar trace and the thinking dots have room to breathe.
    val width = when {
        recording || transcribing -> size * 2.6f
        result == BubblePreviewResult.COPIED || prefs.bubbleShape == "pill" -> size * 1.9f
        else -> size
    }
    // Breathing glow + gentle pulse while thinking, on a ~2.4 s cycle.
    // The values are only read when transcribing, so the animation
    // costs nothing in the other states.
    val glowTransition = rememberInfiniteTransition(label = "thinkingGlow")
    val glowAlpha by glowTransition.animateFloat(
        initialValue = 0.82f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    val pulse by glowTransition.animateFloat(
        initialValue = 1f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    Box(
        modifier = Modifier
            .size(width, size)
            .graphicsLayer {
                alpha = if (transcribing) glowAlpha else 1f
                scaleX = if (transcribing) pulse else 1f
                scaleY = if (transcribing) pulse else 1f
            }
            .clip(RoundedCornerShape(size / 2))
            .background(
                if (recording) Brush.linearGradient(listOf(Red, Red))
                else if (result == BubblePreviewResult.INSERTED) Brush.linearGradient(listOf(Green, Green))
                else Brush.linearGradient(listOf(Color(colors.first), Color(colors.second)))
            ),
        contentAlignment = Alignment.Center
    ) {
        if (result == BubblePreviewResult.INSERTED) {
            Image(
                painter = painterResource(R.drawable.ic_check), contentDescription = null,
                modifier = Modifier.size(size * 0.45f),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White)
            )
        } else if (result == BubblePreviewResult.COPIED) {
            Text("Copied", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        } else if (transcribing) {
            // Thinking dots: three dots bobbing on staggered beats.
            // Deliberately not bars — the bars mean "I can hear you",
            // the dots mean "I'm thinking".
            val transition = rememberInfiniteTransition(label = "thinking")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                (0..2).forEach { i ->
                    val offset by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = -6f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 900, delayMillis = i * 180, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dotOffset$i"
                    )
                    val dotAlpha by transition.animateFloat(
                        initialValue = 0.45f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 900, delayMillis = i * 180, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dotAlpha$i"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .offset(y = offset.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = dotAlpha))
                    )
                }
            }
        } else if (recording && prefs.showWaveform) {
            // Eleven-bar voice trace: newest bar brightest, older bars
            // fading with age as they scroll left.
            val transition = rememberInfiniteTransition(label = "waveform")
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(6, 9, 12, 16, 20, 24, 26, 24, 20, 16, 12).forEachIndexed { i, h ->
                    val scale by transition.animateFloat(
                        initialValue = 0.45f,
                        targetValue = 1.6f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 700, delayMillis = i * 90, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "waveBar$i"
                    )
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height((h * scale).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.35f + 0.65f * (i / 10f)))
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

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(22.dp)) {
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
                                                vm.recordDictation(result.text, seconds.toInt(), vm.lastTranscribeUsedGroq)
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
                                            is TranscribeResult.LocalModelMissing -> {
                                                bubbleState = 0
                                                fieldHint = "Type a message"
                                                onToast("On-device model not downloaded. Download one in Settings.")
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
                            recording = bubbleState == 1,
                            transcribing = bubbleState == 2
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
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(22.dp)) {
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
                Text("Version 2.3.0", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }
        AnimItem(2) {
            AppCard {
                Text("Free, always. Audio is processed by Groq or on your device. We never see or store it.", fontSize = 16.sp)
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
