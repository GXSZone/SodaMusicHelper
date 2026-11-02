package com.sodamusic.helper.automation.flow
import com.sodamusic.helper.automation.util.*
import com.sodamusic.helper.automation.FlowKeywords
import com.sodamusic.helper.automation.flow.*

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import com.sodamusic.helper.automation.AdClaimFlow
import com.sodamusic.helper.automation.AutomationRuntime
import com.sodamusic.helper.automation.AutomationUiState
import com.sodamusic.helper.automation.EnginePhase
import com.sodamusic.helper.automation.LogLevel
import com.sodamusic.helper.automation.NodeActions
import com.sodamusic.helper.automation.NodeScanner

import com.sodamusic.helper.automation.flow.WatchOutcome
import com.sodamusic.helper.settings.AppPreferences
import com.ven.assists.AssistsCore
import com.ven.assists.stepper2.Step
import com.ven.assists.stepper2.StepConfig
import com.ven.assists.stepper2.StepScope
import com.ven.assists.utils.AudioPlayerUtil
import com.ven.assists.utils.AssistsNodeClassNames
import com.ven.assists.utils.CoroutineWrapper
import com.ven.assists.window.AssistsWindowManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

internal sealed interface WatchOutcome {
    data object Finished : WatchOutcome
    data object Timeout : WatchOutcome
    data object Cancelled : WatchOutcome
    data object UserLeft : WatchOutcome
    data object Hijacked : WatchOutcome
    data object PromptFail : WatchOutcome
    data object NoAd : WatchOutcome
    data object LiveRoomAbandon : WatchOutcome
}
