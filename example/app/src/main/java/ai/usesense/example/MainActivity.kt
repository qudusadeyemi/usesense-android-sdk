package ai.usesense.example

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usesense.sdk.EventType
import com.usesense.sdk.SessionType
import com.usesense.sdk.UseSense
import com.usesense.sdk.UseSenseCallback
import com.usesense.sdk.UseSenseConfig
import com.usesense.sdk.UseSenseEnvironment
import com.usesense.sdk.UseSenseError
import com.usesense.sdk.UseSenseResult
import com.usesense.sdk.VerificationRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PREFS_NAME = "usesense_example_prefs"
private const val KEY_API_KEY = "api_key"
private const val KEY_USE_PRODUCTION = "use_production"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MainScreen(activity = this)
            }
        }
    }
}

data class EventLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val type: String,
    val detail: String? = null,
    val isError: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(activity: ComponentActivity) {
    val context = LocalContext.current
    val prefs: SharedPreferences = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // API key + environment persist across launches via SharedPreferences.
    // Matches the iOS example's @AppStorage("apiKey") pattern so integrators
    // can clone, run, paste their key once, and not have to re-enter it on
    // every launch.
    var apiKey by remember { mutableStateOf(prefs.getString(KEY_API_KEY, "") ?: "") }
    var useProduction by remember {
        mutableStateOf(prefs.getBoolean(KEY_USE_PRODUCTION, false))
    }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var identityId by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<UseSenseResult?>(null) }
    var errorState by remember { mutableStateOf<UseSenseError?>(null) }
    val eventLog = remember { mutableStateListOf<EventLogEntry>() }
    val listState = rememberLazyListState()
    var sdkInitializedForKey by remember { mutableStateOf<String?>(null) }
    var eventUnsubscribe by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Persist API key and environment toggle as they change.
    LaunchedEffect(apiKey) {
        prefs.edit().putString(KEY_API_KEY, apiKey).apply()
    }
    LaunchedEffect(useProduction) {
        prefs.edit().putBoolean(KEY_USE_PRODUCTION, useProduction).apply()
    }

    // Auto-scroll event log to bottom
    LaunchedEffect(eventLog.size) {
        if (eventLog.isNotEmpty()) {
            listState.animateScrollToItem(eventLog.size - 1)
        }
    }

    // Clean up the event subscription when the screen is disposed so we
    // don't leak a callback into the SDK singleton across activity recreates.
    DisposableEffect(Unit) {
        onDispose { eventUnsubscribe?.invoke() }
    }

    fun ensureSdkInitialized(): Boolean {
        if (apiKey.isBlank()) return false
        if (sdkInitializedForKey == apiKey) return true
        UseSense.initialize(
            context = context.applicationContext,
            config = UseSenseConfig(
                apiKey = apiKey,
                environment = if (useProduction) {
                    UseSenseEnvironment.PRODUCTION
                } else {
                    UseSenseEnvironment.SANDBOX
                },
            ),
        )
        // Subscribe synchronously BEFORE returning, not via DisposableEffect,
        // so there's no frame gap between `UseSense.initialize()` and the
        // event listener being active. If the user taps Enroll immediately
        // after init, early events (SESSION_CREATED, PERMISSIONS_REQUESTED)
        // must already be wired to the event log; a recomposition-driven
        // subscription would miss them. If the API key has changed, unsub
        // the previous callback first so we don't accumulate stale listeners.
        eventUnsubscribe?.invoke()
        eventUnsubscribe = UseSense.onEvent { event ->
            eventLog.add(
                EventLogEntry(
                    type = event.type.name,
                    detail = event.data?.entries?.joinToString(", ") { "${it.key}=${it.value}" },
                    isError = event.type == EventType.ERROR,
                ),
            )
        }
        sdkInitializedForKey = apiKey
        return true
    }

    val callback = remember {
        object : UseSenseCallback {
            override fun onSuccess(useSenseResult: UseSenseResult) {
                result = useSenseResult
                eventLog.add(
                    EventLogEntry(
                        type = "RESULT",
                        detail = "Decision: ${useSenseResult.decision}",
                    )
                )
            }

            override fun onError(error: UseSenseError) {
                errorState = error
                eventLog.add(
                    EventLogEntry(
                        type = "ERROR",
                        detail = "${error.code}: ${error.message}",
                        isError = true,
                    )
                )
            }

            override fun onCancelled() {
                eventLog.add(EventLogEntry(type = "CANCELLED", detail = "Session cancelled by user"))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UseSense Example") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                // Vertical scroll so the result card + event log can't
                // be squeezed off-screen by the API key + config rows
                // above. weight(1f) on a child of a scrolling Column is
                // not valid, so the event log below is given a fixed
                // max height with its own internal LazyColumn scrolling.
                .verticalScroll(rememberScrollState()),
        ) {
            // API key input. Persisted via SharedPreferences across
            // launches. Masked by default; tap the eye icon to reveal.
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.trim() },
                label = { Text("API Key") },
                placeholder = { Text("Paste your sandbox or production key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (apiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Icon(
                            imageVector = if (apiKeyVisible) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                            contentDescription = if (apiKeyVisible) "Hide" else "Show",
                        )
                    }
                },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Environment toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Production",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = useProduction,
                    onCheckedChange = { useProduction = it },
                )
            }

            if (apiKey.isBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFD97706),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Enter your API key from watchtower.usesense.ai",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFD97706),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Identity ID input
            OutlinedTextField(
                value = identityId,
                onValueChange = { identityId = it },
                label = { Text("Identity ID (for authentication)") },
                placeholder = { Text("e.g., idn_abc123") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        if (!ensureSdkInitialized()) return@Button
                        result = null
                        errorState = null
                        eventLog.clear()
                        UseSense.startVerification(
                            activity = activity,
                            request = VerificationRequest(
                                sessionType = SessionType.ENROLLMENT,
                            ),
                            callback = callback,
                        )
                    },
                    enabled = apiKey.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.HowToReg, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enroll")
                }

                Button(
                    onClick = {
                        if (!ensureSdkInitialized()) return@Button
                        result = null
                        errorState = null
                        eventLog.clear()
                        UseSense.startVerification(
                            activity = activity,
                            request = VerificationRequest(
                                sessionType = SessionType.AUTHENTICATION,
                                identityId = identityId.ifBlank { null },
                            ),
                            callback = callback,
                        )
                    },
                    enabled = apiKey.isNotBlank() && identityId.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Login, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Authenticate")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Result card
            result?.let { res ->
                ResultCard(result = res)
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Event log
            Text(
                text = "Event Log",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (eventLog.isEmpty()) {
                Text(
                    text = "Start a session to see events here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Fixed-height card with internal LazyColumn scrolling.
                // weight(1f) would be cleaner but is invalid inside a
                // verticalScroll Column, which reports an "infinite"
                // height constraint to its children.
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 400.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.padding(8.dp),
                    ) {
                        items(eventLog) { entry ->
                            EventLogItem(entry = entry)
                        }
                    }
                }
            }
        }
    }

    // Error dialog
    errorState?.let { error ->
        AlertDialog(
            onDismissRequest = { errorState = null },
            title = { Text("Verification Error") },
            text = {
                Column {
                    Text(
                        text = "Code: ${error.code}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = error.message)
                    if (error.isRetryable) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "This error is retryable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { errorState = null }) {
                    Text("OK")
                }
            },
        )
    }
}

@Composable
fun ResultCard(result: UseSenseResult) {
    val (badgeColor, badgeText) = when {
        result.isApproved -> Color(0xFF16A34A) to "APPROVED"
        result.isRejected -> Color(0xFFDC2626) to "REJECTED"
        result.isPendingReview -> Color(0xFFD97706) to "MANUAL REVIEW"
        else -> MaterialTheme.colorScheme.outline to result.decision
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Decision badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(badgeColor)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = badgeText,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // Session details
            ResultRow(label = "Session ID", value = result.sessionId)
            result.sessionType?.let { ResultRow(label = "Session Type", value = it) }
            result.identityId?.let { ResultRow(label = "Identity ID", value = it) }
            ResultRow(label = "Timestamp", value = result.timestamp)

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Full scoring details are delivered to your backend via webhook.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun EventLogItem(entry: EventLogEntry) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val icon = eventIcon(entry.type)
    val color = if (entry.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row {
                Text(
                    text = timeFormat.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.type,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
            }
            entry.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

fun eventIcon(type: String): ImageVector = when (type) {
    "SESSION_CREATED" -> Icons.Default.PlayArrow
    "PERMISSIONS_REQUESTED", "PERMISSIONS_GRANTED", "PERMISSIONS_DENIED" -> Icons.Default.Security
    "CAPTURE_STARTED", "FRAME_CAPTURED", "CAPTURE_COMPLETED" -> Icons.Default.CameraAlt
    "AUDIO_RECORD_STARTED", "AUDIO_RECORD_COMPLETED" -> Icons.Default.Mic
    "CHALLENGE_STARTED", "CHALLENGE_COMPLETED" -> Icons.Default.HowToReg
    "UPLOAD_STARTED", "UPLOAD_PROGRESS", "UPLOAD_COMPLETED" -> Icons.Default.CloudUpload
    "DECISION_RECEIVED", "RESULT" -> Icons.Default.CheckCircle
    "ERROR" -> Icons.Default.Error
    else -> Icons.Default.Info
}
