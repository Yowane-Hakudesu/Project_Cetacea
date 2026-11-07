package com.whalechan.agent.core

import android.content.Context
import android.os.SystemClock
import com.whalechan.agent.access.AppsLauncher
import com.whalechan.agent.access.WhaleAccessibilityService
import com.whalechan.agent.data.AppSettings
import com.whalechan.agent.data.SettingsRepository
import com.whalechan.agent.data.log.ErrorCategory
import com.whalechan.agent.data.log.StepLogEntity
import com.whalechan.agent.data.log.StepLogRepository
import com.whalechan.agent.notify.NotificationBus
import com.whalechan.agent.service.PauseNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent 核心：感知 → 规划 → 执行 → 反馈 的闭环。
 *
 * v0.2 的三档终止策略（见 StepPolicy）：
 *  - LIMITED：达到 maxSteps 时**暂停**并推送“继续/放弃”，不再静默终止；
 *  - TIME_WINDOW：不看步数，到点结束；
 *  - UNLIMITED：只由完成信号、卡死检测、手动停止结束（需显式风险确认）。
 *
 * 每一步都会写结构化日志（Room），并由 WsHub 实时推给伴侣端。
 */
class AgentEngine(
    private val appContext: Context,
    private val settings: SettingsRepository,
    private val memory: MemoryStore,
    private val logs: StepLogRepository,
    private val cache: ActionCache
) {

    data class RunState(
        val running: Boolean = false,
        val paused: Boolean = false,
        val goal: String = "",
        val taskId: String = "",
        val step: Int = 0,
        /** 0 表示无限模式 / 不按步数终止 */
        val maxSteps: Int = 0,
        val mode: RunMode = RunMode.CHAT,
        val lastMessage: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val llm = LlmClient()
    private val executor = ActionExecutor(appContext)

    private val _state = MutableStateFlow(RunState())
    val state: StateFlow<RunState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(replay = 8, extraBufferCapacity = 64)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    @Volatile
    private var job: Job? = null

    @Volatile
    private var lastActionResult: String = ""

    /** 用户主动按了停止 → 不再从待处理信箱里自动取任务。 */
    @Volatile
    var userStopped: Boolean = false
        private set

    private val stopFlag = AtomicBoolean(false)

    @Volatile
    private var pauseContinuation: CompletableDeferred<Boolean>? = null

    fun isRunning(): Boolean = job?.isActive == true

    fun isPaused(): Boolean = pauseContinuation != null

    /**
     * 启动一个任务。
     * @return false 表示已有任务在跑（不排队，避免两个 Agent 抢同一块屏幕）。
     */
    fun start(goal: String, mode: RunMode = RunMode.ACT): Boolean {
        if (job?.isActive == true) return false
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) return false

        stopFlag.set(false)
        userStopped = false
        job = scope.launch {
            try {
                runTask(trimmed, mode)
            } catch (cancelled: CancellationException) {
                // 区分“用户主动停”和“被系统/服务回收”：后者以前会被误报成“已停下，听你的。”
                val stoppedByUser = stopFlag.get()
                val text = if (stoppedByUser) {
                    "已停下，听你的。"
                } else {
                    Persona.failureLine(
                        ErrorCategory.STUCK,
                        "任务被外部中断：服务或进程被系统回收（不是模型/界面判断）"
                    )
                }
                WhaleLog.w("任务被取消：stoppedByUser=$stoppedByUser")
                _state.update {
                    it.copy(
                        running = false,
                        paused = false,
                        lastMessage = if (stoppedByUser) "已停止" else "被系统中断"
                    )
                }
                emit(AgentEvent.Finished(false, text))
            } catch (t: Throwable) {
                WhaleLog.e("任务异常终止", t)
                emit(AgentEvent.Error(t.message ?: t.javaClass.simpleName))
                emit(AgentEvent.Finished(false, "出错了：${t.message ?: t.javaClass.simpleName}"))
                _state.update { it.copy(running = false, paused = false, lastMessage = "出错") }
            }
        }
        return true
    }

    fun cancel(reason: String = "用户停止") {
        stopFlag.set(true)
        userStopped = true
        pauseContinuation?.complete(false)
        job?.cancel(CancellationException(reason))
        _state.update { it.copy(running = false, paused = false, lastMessage = reason) }
    }

    /** 步数上限暂停后，用户点“继续”。 */
    fun continuePaused(): Boolean {
        val continuation = pauseContinuation ?: return false
        continuation.complete(true)
        return true
    }

    /** 步数上限暂停后，用户点“放弃”。 */
    fun abortPaused(): Boolean {
        val continuation = pauseContinuation ?: return false
        continuation.complete(false)
        return true
    }

    // ────────────────────────── 纯聊天 ──────────────────────────

    suspend fun chat(userText: String): String {
        val config = settings.load()
        val apiKey = settings.apiKey().orEmpty()
        if (config.baseUrl.isBlank() || config.modelId.isBlank()) {
            return "我还不会说话呢——先去设置页把 Base URL 和 Model ID 填好吧。"
        }

        val messages = buildList {
            add(ChatMessage("system", Persona.chatSystemPrompt(memory.summaryForPrompt(), config.personaExtra)))
            memory.recentChat(12).forEach { add(ChatMessage(it.role, it.text)) }
            add(ChatMessage("user", userText))
        }

        return try {
            val reply = llm.chat(config.toLlmConfig(apiKey), messages, maxTokens = 800).trim()
            memory.appendChat("user", userText)
            memory.appendChat("assistant", reply)
            reply.ifBlank { "……我刚刚走神了，你再说一遍？" }
        } catch (t: Throwable) {
            WhaleLog.w("聊天请求失败", t)
            "呜，我的脑子连不上（${t.message ?: "网络错误"}）。检查一下 API Key 和 Base URL 好不好？"
        }
    }

    // ────────────────────────── 主循环 ──────────────────────────

    private suspend fun runTask(goal: String, mode: RunMode) {
        val config = settings.load()
        val apiKey = settings.apiKey().orEmpty()

        if (config.baseUrl.isBlank() || config.modelId.isBlank()) {
            emit(AgentEvent.Finished(false, "先去设置页填写 Base URL 与 Model ID，我才能开始想事情。"))
            _state.update { it.copy(running = false, lastMessage = "未配置 LLM") }
            return
        }
        if (!WhaleAccessibilityService.isReady()) {
            emit(
                AgentEvent.Finished(
                    false,
                    "我看不见屏幕：请到「设置 → 无障碍 → 鲸鱼娘」打开服务，再让我试一次。"
                )
            )
            _state.update { it.copy(running = false, lastMessage = "无障碍未开启") }
            return
        }

        val policy = StepPolicy.from(config.stepPolicy)
        if (policy == StepPolicy.UNLIMITED && !config.unlimitedConsent) {
            emit(AgentEvent.Finished(false, "无限模式需要先在设置页勾选「我理解电量与发热风险」。"))
            _state.update { it.copy(running = false, lastMessage = "无限模式未确认") }
            return
        }
        if (apiKey.isBlank() && config.baseUrl.startsWith("https://", ignoreCase = true)) {
            emit(AgentEvent.Notice("API Key 是空的，多数云端端点会直接拒绝请求。"))
        }
        if (config.isEndpointCleartext()) {
            emit(AgentEvent.Notice("你用的是 http:// 明文端点：API Key 与界面摘要会在局域网里明文传输。"))
        }
        if (config.fullTrustMode) {
            emit(AgentEvent.Notice("完全信任模式已开启：不会再有任何操作前确认，只有事后日志。"))
        }

        val taskId = "T" + System.currentTimeMillis().toString(36)
        val startedAt = SystemClock.elapsedRealtime()
        val maxStepsForState = if (policy == StepPolicy.LIMITED) config.maxSteps else 0

        // 开放式指令 + 无限模式 → 自动驾驶：不轻易结束，允许自行探索
        val autonomous = policy == StepPolicy.UNLIMITED && isAutonomousGoal(goal)

        var stepBudget = if (policy == StepPolicy.LIMITED) config.maxSteps else Int.MAX_VALUE
        var step = 0
        var failureStreak = 0
        var unchangedStreak = 0
        var lastAfterFingerprint = ""
        var firstUnchangedAt = 0L
        var fallbackCount = 0
        var lastErrorCategory: ErrorCategory? = null
        var lastErrorDetail: String = ""
        val history = mutableListOf<StepRecord>()
        val recordedDecisions = mutableListOf<AgentDecision>()
        val errorRetries = mutableMapOf<String, Int>()
        val recentTexts = mutableListOf<String>()
        var repeatRejections = 0
        var currentEntity: String? = null
        val focusedTask = isFocusTask(goal)
        var outcome: Pair<Boolean, String>? = null
        lastActionResult = ""

        val initialFingerprint = WhaleAccessibilityService.instance
            ?.snapshot()?.signature().orEmpty()

        _state.update {
            it.copy(
                running = true,
                paused = false,
                goal = goal,
                taskId = taskId,
                step = 0,
                maxSteps = maxStepsForState,
                mode = mode,
                lastMessage = "准备中…"
            )
        }
        emit(AgentEvent.AssistantMessage(greeting(goal, mode, policy, config.maxSteps)))
        if (autonomous) {
            emit(AgentEvent.Notice("AUTONOMOUS_MODE：我会自己找事做，不会轻易停下。"))
        }

        // ── SMART_LAZY：屏幕指纹 + 目标命中时先试历史动作序列 ──
        var usedCache = false
        if (config.cacheReplayEnabled && mode == RunMode.ACT && initialFingerprint.isNotBlank()) {
            val plan = cache.lookup(goal, initialFingerprint)
            if (plan != null) {
                usedCache = true
                emit(AgentEvent.Notice("这个目标我以前做过，先试试回放 ${plan.decisions.size} 步。"))
                val replay = replayPlan(plan, taskId, goal, config)
                if (replay != null) {
                    outcome = replay
                } else {
                    cache.invalidate(plan)
                    usedCache = false
                    emit(AgentEvent.Notice("回放没成功，改成重新规划。"))
                }
            }
        }

        while (outcome == null) {
            currentCoroutineContext().ensureActive()
            if (stopFlag.get()) {
                outcome = false to "已停止"
                break
            }

            if (step >= HARD_STEP_GUARD) {
                outcome = false to "达到硬保护上限（$HARD_STEP_GUARD 步），先停下"
                break
            }

            if (policy == StepPolicy.TIME_WINDOW && config.maxRuntimeMs > 0) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed > config.maxRuntimeMs) {
                    outcome = false to "时间窗口用完了（${config.maxRuntimeMinutes} 分钟）"
                    break
                }
            }

            // 步数上限：暂停并询问，而不是静默终止
            if (policy == StepPolicy.LIMITED && step >= stepBudget) {
                val approved = awaitContinuation(step, taskId, config)
                if (!approved) {
                    outcome = false to "到达步数上限（$step），已暂停并放弃"
                    break
                }
                stepBudget += config.maxSteps.coerceAtLeast(1)
                _state.update { it.copy(maxSteps = stepBudget, paused = false) }
                emit(AgentEvent.Resumed)
            }

            step++
            _state.update { it.copy(step = step) }

            val snapshot = WhaleAccessibilityService.instance?.snapshot()
            if (snapshot == null) {
                failureStreak++
                lastActionResult = "抓不到窗口（可能锁屏或正在切换应用）"
                appendLog(
                    taskId, step, goal, "OBSERVE", "", lastAfterFingerprint, "",
                    "fail", ErrorCategory.PERMISSION_LOST, lastActionResult, ""
                )
                if (failureStreak >= 3) {
                    outcome = false to "无障碍抓不到窗口，可能掉线了。请重新检查无障碍权限。"
                    break
                }
                delay(1200)
                continue
            }

            val beforeFingerprint = snapshot.signature()
            _state.update {
                it.copy(lastMessage = "第 $step 步 · 观察 ${snapshot.packageName}")
            }
            emit(AgentEvent.Thinking(step, maxStepsForState))

            val notificationsText = NotificationBus.recentForPrompt(limit = 6)
            val hint = buildHint(unchangedStreak, failureStreak, lastActionResult, autonomous)
            val attachmentNote = AttachmentBus.note()
            val attachmentImage = if (config.useVision) AttachmentBus.takeImageForModel() else null
            val image = attachmentImage ?: if (config.useVision && step % 2 == 1) {
                WhaleAccessibilityService.instance?.screenshotBase64OrNull()
            } else {
                null
            }

            // 聊天页才提取好友名，避免把标题栏的其他文字误当成人名
            currentEntity = if (SocialContext.isChatScreen(snapshot)) {
                SocialContext.currentEntity(snapshot)
            } else {
                null
            }
            val recentActivity = runCatching {
                AgentRuntime.social.recentActivityText(minutes = 30, limit = 12)
            }.getOrDefault("")

            val prompt = PromptBuilder.buildUserPrompt(
                goal = goal,
                snapshot = snapshot,
                history = history,
                memoryText = memory.summaryForPrompt(),
                notificationsText = notificationsText,
                step = step,
                maxSteps = maxStepsForState,
                mode = mode,
                hint = hint,
                hasImage = image != null,
                attachmentNote = attachmentNote,
                currentEntity = currentEntity,
                focused = focusedTask
            )

            val raw = try {
                callWithRetry {
                    llm.complete(
                        cfg = config.toLlmConfig(apiKey),
                        systemPrompt = Persona.agentSystemPrompt(
                            memory.summaryForPrompt(),
                            config.personaExtra,
                            config.fullTrustMode,
                            autonomous,
                            recentActivity
                        ),
                        userPrompt = prompt,
                        maxTokens = 700,
                        imageBase64Jpeg = image
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                outcome = false to "调用 LLM 失败：${t.message ?: t.javaClass.simpleName}"
                break
            }

            // 人格里的 TIMEOUT_SIGNAL：模型自己认输也算卡住
            if (raw.contains(Persona.TIMEOUT_SIGNAL)) {
                emit(AgentEvent.Stuck(taskId, beforeFingerprint, "模型输出超时信号"))
                appendLog(
                    taskId, step, goal, "SIGNAL", raw.take(500), beforeFingerprint, beforeFingerprint,
                    "timeout", ErrorCategory.STUCK, Persona.TIMEOUT_SIGNAL, snapshot.render(800)
                )
                outcome = false to Persona.failureLine(
                    ErrorCategory.STUCK,
                    "模型输出了超时信号（${Persona.TIMEOUT_SIGNAL}）"
                )
                break
            }

            val decision = DecisionParser.parse(raw)
            emit(AgentEvent.Planned(step, decision.oneLine()))

            val newText = decision.text?.takeIf { it.isNotBlank() }

            // ── 复读熔断：与最近 3 条已发文本相似度 ≥ 80% 直接拦截 ──
            if (newText != null &&
                (decision.type == ActionType.INPUT || decision.type == ActionType.REPLY_QQ)
            ) {
                val similarity = TextSimilarity.maxSimilarity(newText, recentTexts.takeLast(3))
                if (similarity >= 0.8) {
                    repeatRejections++
                    failureStreak++
                    lastActionResult =
                        "ActionRejected: DuplicateText — 你刚刚已经说过" +
                            "「${recentTexts.lastOrNull()?.take(15)}」了（相似度 ${(similarity * 100).toInt()}%）。" +
                            "不要复读，换个话题或结束任务。"
                    history += StepRecord(step, decision.oneLine(), lastActionResult, snapshot.packageName)
                    appendLog(
                        taskId, step, goal, "DUPLICATE_TEXT", decision.raw.take(800),
                        beforeFingerprint, beforeFingerprint, "denied", ErrorCategory.UNKNOWN,
                        lastActionResult, snapshot.render(800)
                    )
                    emit(AgentEvent.Notice("这句话刚说过，我拦下了。"))
                    if (repeatRejections >= 3) {
                        outcome = false to Persona.failureLine(
                            ErrorCategory.STUCK,
                            "连续 3 次复读，模型卡在同一个话题上"
                        )
                        break
                    }
                    continue
                }
            }

            // ── 社交限流：FULL_TRUST 也拦，只拦频率，不做价值判断 ──
            val socialPlan = SocialActionClassifier.classify(decision, snapshot, currentEntity)
            if (socialPlan != null) {
                if (socialPlan.textHash.isNotBlank() &&
                    AgentRuntime.rateLimiter.isDuplicateCompletion(socialPlan.textHash)
                ) {
                    failureStreak++
                    lastActionResult =
                        "ActionRejected: DuplicateAction — 这个动作刚刚已经成功执行过" +
                            "（${socialPlan.summary}），不要原地重复。"
                    appendLog(
                        taskId, step, goal, "DUPLICATE_ACTION", decision.raw.take(800),
                        beforeFingerprint, beforeFingerprint, "denied", ErrorCategory.UNKNOWN,
                        lastActionResult, snapshot.render(800)
                    )
                    emit(AgentEvent.Notice("这步刚做过，不重复了。"))
                    continue
                }

                when (val verdict = AgentRuntime.rateLimiter.canExecute(socialPlan.type, socialPlan.targetKey)) {
                    is RateVerdict.Rejected -> {
                        failureStreak++
                        lastActionResult = verdict.message
                        history += StepRecord(step, decision.oneLine(), lastActionResult, snapshot.packageName)
                        appendLog(
                            taskId, step, goal, "RATE_LIMITED", decision.raw.take(800),
                            beforeFingerprint, beforeFingerprint, "denied", ErrorCategory.UNKNOWN,
                            verdict.message, snapshot.render(800)
                        )
                        emit(AgentEvent.Notice("先不发了：${verdict.message}"))
                        continue
                    }

                    RateVerdict.Allowed -> Unit
                }
            }

            when (decision.type) {
                ActionType.DONE -> {
                    outcome = true to (decision.summary?.takeIf { it.isNotBlank() } ?: "完成啦。")
                    break
                }

                ActionType.FAIL -> {
                    val summary = decision.summary?.takeIf { it.isNotBlank() } ?: "模型判断做不完。"
                    lastErrorCategory = ErrorCategory.UNKNOWN
                    lastErrorDetail = summary
                    appendLog(
                        taskId, step, goal, decision.type.name, decision.raw.take(1000),
                        beforeFingerprint, beforeFingerprint, "fail", ErrorCategory.UNKNOWN,
                        summary, snapshot.render(800)
                    )
                    // 无限模式下模型说“做不完”也不结束：自己兜底一次，把失败原因带回下一轮
                    if (policy == StepPolicy.UNLIMITED && fallbackCount < MAX_FALLBACKS) {
                        if (fallbackSwipe(taskId, step, goal, "模型返回 fail：$summary", snapshot)) {
                            fallbackCount++
                            lastActionResult = "模型想放弃（$summary），我已自行滑动并继续观察。"
                            continue
                        }
                    }
                    if (policy == StepPolicy.UNLIMITED) {
                        outcome = false to Persona.failureLine(
                            ErrorCategory.STUCK,
                            "兜底 $fallbackCount 次后模型仍无法继续：$summary"
                        )
                        break
                    }
                    outcome = false to Persona.failureLine(ErrorCategory.UNKNOWN, summary)
                    break
                }

                ActionType.ASK_USER -> {
                    if (policy == StepPolicy.UNLIMITED && fallbackCount < MAX_FALLBACKS) {
                        if (fallbackSwipe(taskId, step, goal, "模型要求追问，自动驾驶模式改为自行探索", snapshot)) {
                            fallbackCount++
                            lastActionResult = "模型想追问，我按自动驾驶模式先自己看看界面。"
                            continue
                        }
                    }
                    outcome = false to "需要你补充一点信息：${decision.question?.takeIf { it.isNotBlank() } ?: "请再说详细一点"}"
                    break
                }

                ActionType.UNKNOWN -> {
                    failureStreak++
                    lastActionResult = "上一轮输出不是可识别的动作，请严格按约定的 JSON 格式输出。"
                    history += StepRecord(step, "无法识别的动作", lastActionResult, snapshot.packageName)
                    appendLog(
                        taskId, step, goal, "UNKNOWN", decision.raw.take(1000),
                        beforeFingerprint, beforeFingerprint, "fail", ErrorCategory.LLM_INVALID_ACTION,
                        lastActionResult, snapshot.render(800)
                    )
                    // 非法指令：无限模式下不退出，执行安全兜底动作（swipe up）继续
                    if (policy == StepPolicy.UNLIMITED && fallbackCount < MAX_FALLBACKS) {
                        if (fallbackSwipe(taskId, step, goal, "模型输出无法识别：${decision.raw.take(120)}", snapshot)) {
                            fallbackCount++
                            continue
                        }
                    }
                    if (failureStreak >= 4 && policy != StepPolicy.UNLIMITED) {
                        outcome = false to Persona.failureLine(
                            ErrorCategory.LLM_INVALID_ACTION,
                            "连续 ${failureStreak} 次输出无法识别的动作"
                        )
                        break
                    }
                    if (policy == StepPolicy.UNLIMITED && fallbackCount >= MAX_FALLBACKS) {
                        outcome = false to Persona.failureLine(
                            ErrorCategory.STUCK,
                            "连续 $fallbackCount 次兜底滑动后模型仍输出非法动作"
                        )
                        break
                    }
                    continue
                }

                else -> Unit
            }

            // ── 安全闸门（FULL_TRUST 时 SafetyPolicy 直接返回 SAFE） ──
            val verdict = SafetyPolicy.evaluate(decision, snapshot, config, mode)
            when (verdict.risk) {
                SafetyPolicy.Risk.FORBIDDEN -> {
                    failureStreak++
                    lastActionResult = "动作被安全策略拒绝：${verdict.reason}"
                    history += StepRecord(step, decision.oneLine(), lastActionResult, snapshot.packageName)
                    emit(AgentEvent.Notice("这个动作我不做：${verdict.reason}"))
                    appendLog(
                        taskId, step, goal, decision.type.name, decision.raw.take(1000),
                        beforeFingerprint, beforeFingerprint, "denied", ErrorCategory.UNKNOWN,
                        verdict.reason, snapshot.render(800)
                    )
                    if (failureStreak >= 4 && policy != StepPolicy.UNLIMITED) {
                        outcome = false to Persona.failureLine(
                            ErrorCategory.UNKNOWN,
                            "连续被安全策略拦下：${verdict.reason}"
                        )
                        break
                    }
                    continue
                }

                SafetyPolicy.Risk.CONFIRM -> {
                    _state.update { it.copy(lastMessage = "等你确认：${decision.oneLine()}") }
                    val approved = ConfirmationGate.ask(appContext, decision, verdict)
                    if (!approved) {
                        lastActionResult = "用户拒绝了这个动作，或确认超时，未执行：${decision.oneLine()}"
                        history += StepRecord(step, decision.oneLine(), lastActionResult, snapshot.packageName)
                        emit(AgentEvent.Notice("好，这一步我不做。"))
                        appendLog(
                            taskId, step, goal, decision.type.name, decision.raw.take(1000),
                            beforeFingerprint, beforeFingerprint, "denied", ErrorCategory.UNKNOWN,
                            lastActionResult, snapshot.render(800)
                        )
                        continue
                    }
                }

                SafetyPolicy.Risk.SAFE -> Unit
            }

            // ── 执行 ──
            val result = try {
                withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                    executor.execute(decision, snapshot, config.allowCredentialEntry)
                } ?: ExecResult.fail(
                    "动作执行超时（${ACTION_TIMEOUT_MS / 1000} 秒）",
                    ErrorCategory.GESTURE_TIMEOUT
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                ExecResult.fail("执行异常：${t.message ?: t.javaClass.simpleName}", ErrorCategory.UNKNOWN)
            }

            recordedDecisions += decision
            history += StepRecord(
                step = step,
                action = decision.oneLine(),
                result = if (result.ok) "成功：${result.detail}" else "失败：${result.detail}",
                screen = snapshot.packageName
            )
            emit(AgentEvent.Acted(step, decision.oneLine(), result.ok, result.detail))
            _state.update {
                it.copy(lastMessage = "第 $step 步：${decision.oneLine()} → ${if (result.ok) "成功" else "失败"}")
            }

            delay(ACTION_SETTLE_MS)

            val afterSnapshot = WhaleAccessibilityService.instance?.snapshot()
            val afterFingerprint = afterSnapshot?.signature().orEmpty()

            if (result.ok) {
                appendLog(
                    taskId, step, goal, decision.type.name, decision.raw.take(1000),
                    beforeFingerprint, afterFingerprint, "success", null,
                    result.detail, snapshot.render(1200)
                )
                failureStreak = 0
                errorRetries.clear()
                repeatRejections = 0
                lastActionResult = "上一步成功：${result.detail}"

                if (newText != null) {
                    recentTexts += newText
                    if (recentTexts.size > 6) recentTexts.removeAt(0)
                }
                // reply_qq 由 QqReplyFlow 自己记账；评论/说说/转发在这里记账
                if (socialPlan != null && socialPlan.type != SocialActionType.REPLY_MESSAGE) {
                    runCatching {
                        AgentRuntime.rateLimiter.record(
                            type = socialPlan.type,
                            targetKey = socialPlan.targetKey,
                            summary = socialPlan.summary,
                            textHash = socialPlan.textHash
                        )
                    }
                }
            } else {
                appendLog(
                    taskId, step, goal, decision.type.name, decision.raw.take(1000),
                    beforeFingerprint, afterFingerprint, "fail", result.category ?: ErrorCategory.UNKNOWN,
                    result.detail, snapshot.render(1200)
                )
                lastErrorCategory = result.category ?: ErrorCategory.UNKNOWN
                lastErrorDetail = result.detail
                val key = lastErrorCategory?.name ?: ErrorCategory.UNKNOWN.name
                val retries = (errorRetries[key] ?: 0) + 1
                errorRetries[key] = retries
                failureStreak++

                lastActionResult = if (retries >= SAME_ERROR_RETRY_LIMIT) {
                    "上一步失败（同类错误第 $retries 次）：${result.detail}。" +
                        "必须换一种完全不同的策略：换节点、先 back、先 swipe，或改走另一条路径。"
                } else {
                    "上一步失败（同类错误第 $retries 次）：${result.detail}。请修正后重试。"
                }

                // 非无限模式：连续失败到阈值就停；无限模式只在真正的守卫上限才停
                if (policy != StepPolicy.UNLIMITED && failureStreak >= NON_UNLIMITED_FAILURE_LIMIT) {
                    outcome = false to Persona.failureLine(
                        lastErrorCategory ?: ErrorCategory.UNKNOWN,
                        "连续 $failureStreak 步失败：${result.detail}"
                    )
                    break
                }
                if (failureStreak >= UNLIMITED_FAILURE_GUARD) {
                    outcome = false to Persona.failureLine(
                        ErrorCategory.STUCK,
                        "连续 $failureStreak 步失败，判定卡住（最后错误：${result.detail}）"
                    )
                    break
                }
            }

            // ── 卡死检测：步数阈值 + 时间窗口（指纹只由 text/desc/className 构成） ──
            if (afterFingerprint.isNotBlank() && afterFingerprint == lastAfterFingerprint) {
                unchangedStreak++
                if (firstUnchangedAt == 0L) firstUnchangedAt = SystemClock.elapsedRealtime()
                val byCount = unchangedStreak >= config.stallLimit
                val byTime = firstUnchangedAt > 0L &&
                    SystemClock.elapsedRealtime() - firstUnchangedAt >= config.stallWindowSeconds * 1000L
                if (byCount || byTime) {
                    val why = if (byCount) {
                        "连续 ${config.stallLimit} 步界面指纹未变化"
                    } else {
                        "界面连续 ${config.stallWindowSeconds} 秒没有文本/节点变化"
                    }
                    emit(AgentEvent.Stuck(taskId, afterFingerprint, why))
                    appendLog(
                        taskId, step, goal, "STALL", decision.raw.take(500),
                        beforeFingerprint, afterFingerprint, "timeout", ErrorCategory.STUCK,
                        why, snapshot.render(1000)
                    )
                    outcome = false to Persona.failureLine(ErrorCategory.STUCK, why)
                    break
                }
            } else {
                unchangedStreak = 0
                firstUnchangedAt = 0L
            }
            lastAfterFingerprint = afterFingerprint
        }

        val (success, summary) = outcome ?: (
            false to Persona.failureLine(
                lastErrorCategory ?: ErrorCategory.STUCK,
                lastErrorDetail.ifBlank { "达到循环上限，没有收到明确的完成信号" }
            )
        )
        memory.recordTask(goal, summary, success)

        // 任务结束后不要停在别人的聊天窗口里
        leaveSocialAppIfNeeded(config)

        if (success && config.cacheReplayEnabled && !usedCache && recordedDecisions.isNotEmpty()) {
            cache.save(goal, initialFingerprint, recordedDecisions, summary)
        }
        if (mode == RunMode.CURIOUS && success) {
            val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
            memory.addFact("$stamp 自己逛手机时：${summary.take(80)}")
        }

        _state.update { it.copy(running = false, paused = false, lastMessage = summary.take(60)) }
        emit(AgentEvent.Finished(success, summary))
        WhaleLog.i("任务 $taskId 结束 success=$success：$summary")
    }

    /**
     * 回放缓存的动作序列。
     * @return null 表示回放失败（调用方应使缓存失效并回退到 LLM）；非 null 是最终结果。
     */
    private suspend fun replayPlan(
        plan: CachedPlan,
        taskId: String,
        goal: String,
        config: AppSettings
    ): Pair<Boolean, String>? {
        var step = 0
        for (raw in plan.decisions) {
            currentCoroutineContext().ensureActive()
            step++
            val snapshot = WhaleAccessibilityService.instance?.snapshot() ?: return null
            val decision = DecisionParser.parse(raw)
            if (decision.type == ActionType.DONE) {
                return true to (decision.summary?.takeIf { it.isNotBlank() } ?: plan.summary.ifBlank { "完成" })
            }
            if (decision.type == ActionType.FAIL) return null

            val verdict = SafetyPolicy.evaluate(decision, snapshot, config, RunMode.ACT)
            if (verdict.risk != SafetyPolicy.Risk.SAFE) return null

            val result = withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                executor.execute(decision, snapshot, config.allowCredentialEntry)
            } ?: return null

            if (!result.ok) {
                appendLog(
                    taskId, step, goal, decision.type.name, raw.take(1000),
                    snapshot.signature(), snapshot.signature(), "fail",
                    result.category ?: ErrorCategory.UNKNOWN,
                    "缓存回放失败：${result.detail}", snapshot.render(800)
                )
                return null
            }
            delay(ACTION_SETTLE_MS)
        }
        return null
    }

    // ────────────────────────── 暂停 ──────────────────────────

    private suspend fun awaitContinuation(step: Int, taskId: String, config: AppSettings): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pauseContinuation = deferred
        _state.update { it.copy(paused = true, lastMessage = "已到步数上限（$step），等你决定") }
        emit(AgentEvent.Paused(step, taskId))

        // 没有通知权限时没法弹“继续/放弃”，只能缩短等待并说明
        val canNotify = NotificationPermission.canPost(appContext)
        if (!canNotify) {
            emit(AgentEvent.Notice("没有通知权限，无法弹出「继续 / 放弃」；30 秒后按放弃处理。"))
        }
        PauseNotifier.notify(appContext, taskId, step)

        val approved = try {
            val timeout = if (canNotify) PAUSE_TIMEOUT_MS else 30_000L
            withTimeoutOrNull(timeout) { deferred.await() } ?: false
        } finally {
            pauseContinuation = null
            PauseNotifier.cancel(appContext)
            _state.update { it.copy(paused = false) }
        }
        return approved
    }

    // ────────────────────────── 日志与辅助 ──────────────────────────

    private fun appendLog(
        taskId: String,
        step: Int,
        goal: String,
        actionType: String,
        payload: String,
        before: String,
        after: String,
        label: String,
        category: ErrorCategory?,
        detail: String,
        uiSummary: String
    ) {
        val entity = if (category == null) {
            StepLogEntity.success(
                taskId = taskId,
                stepIndex = step,
                goal = goal,
                actionType = actionType,
                actionPayload = payload,
                before = before,
                after = after,
                llmRaw = payload,
                uiSummary = uiSummary
            )
        } else {
            StepLogEntity.failure(
                taskId = taskId,
                stepIndex = step,
                goal = goal,
                actionType = actionType,
                actionPayload = payload,
                before = before,
                after = after,
                llmRaw = payload,
                detail = detail,
                category = category,
                result = label,
                uiSummary = uiSummary
            )
        }
        logs.append(entity)
    }

    /**
     * 判断是不是开放式指令（“随便玩”“干你想干的”）。
     * 只有无限模式才会真正进入 AUTONOMOUS_MODE。
     */
    private fun isAutonomousGoal(goal: String): Boolean =
        AUTONOMOUS_KEYWORDS.any { goal.contains(it, ignoreCase = true) }

    /**
     * 安全兜底：模型给出非法/放弃/追问时，自己滑一下继续观察，而不是结束任务。
     * 只在无限模式下使用，并且调用方限制总次数（MAX_FALLBACKS）。
     */
    private suspend fun fallbackSwipe(
        taskId: String,
        step: Int,
        goal: String,
        reason: String,
        snapshot: com.whalechan.agent.access.UiSnapshot
    ): Boolean {
        val fallback = AgentDecision(
            type = ActionType.SWIPE,
            direction = "up",
            distance = 0.6f,
            reason = "AUTONOMOUS_FALLBACK",
            raw = """{"action":"swipe","direction":"up"}"""
        )
        val result = runCatching {
            withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                executor.execute(fallback, snapshot, false)
            }
        }.getOrNull() ?: return false
        if (!result.ok) return false

        delay(ACTION_SETTLE_MS)
        val after = WhaleAccessibilityService.instance?.snapshot()
        appendLog(
            taskId, step, goal, "SWIPE_FALLBACK", fallback.raw,
            snapshot.signature(), after?.signature().orEmpty(), "fallback",
            ErrorCategory.AUTONOMOUS_FALLBACK, reason, (after ?: snapshot).render(600)
        )
        emit(AgentEvent.Notice("模型没给出可用动作，我先自己滑一下继续看。"))
        lastActionResult = "我自行执行了一次兜底滑动（$reason），请观察新界面再决定。"
        return true
    }

    /** 任务结束后如果还停在 QQ/TIM，主动回桌面，避免“赖在聊天窗口里”。 */
    private suspend fun leaveSocialAppIfNeeded(config: AppSettings) {
        if (!config.leaveSocialAppOnFinish) return
        val service = WhaleAccessibilityService.instance ?: return
        val pkg = service.currentPackage()
        if (pkg !in AppsLauncher.QQ_PACKAGES) return
        service.performHome()
        delay(300)
        emit(AgentEvent.Notice("任务结束，我先回桌面，不在聊天界面停留。"))
    }

    /** 开放式“刷视频/看内容”任务：提示模型不要被通知打断。 */
    private fun isFocusTask(goal: String): Boolean =
        listOf("刷视频", "刷抖音", "看视频", "看直播", "刷快手", "刷B站", "刷b站", "看番", "刷剧")
            .any { goal.contains(it, ignoreCase = true) }

    private fun greeting(goal: String, mode: RunMode, policy: StepPolicy, maxSteps: Int): String =
        when (mode) {
            RunMode.CURIOUS -> "我自己逛逛，最多 6 步，只看看、不乱点。"
            else -> {
                val budget = when (policy) {
                    StepPolicy.LIMITED -> "最多 $maxSteps 步，到上限我会暂停问你"
                    StepPolicy.TIME_WINDOW -> "按时间跑，到点就停"
                    StepPolicy.UNLIMITED -> "无限模式，只有你叫停或卡住才会结束"
                }
                "收到～「${goal.take(30)}」。$budget。"
            }
        }

    private fun buildHint(
        unchangedStreak: Int,
        failureStreak: Int,
        lastResult: String,
        autonomous: Boolean = false
    ): String =
        buildString {
            if (autonomous) {
                appendLine("【AUTONOMOUS_MODE】不要输出 done / fail / ask_user；用 swipe / tap / back 继续探索，除非真的无事可做。")
            }
            if (failureStreak in 2..5) {
                appendLine("已经连续 $failureStreak 步没有成功。不要重复同一个动作：可以 back 回上一层、滑动找目标，或者换一个明显不同的路径。")
            }
            if (unchangedStreak >= 2) {
                appendLine("界面已经连续 $unchangedStreak 步没有变化，说明上一步多半没生效。换节点或换方式。")
            }
            if (lastResult.isNotBlank()) appendLine(lastResult)
        }

    private suspend fun callWithRetry(block: suspend () -> String): String {
        var lastError: Throwable? = null
        repeat(MAX_LLM_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: LlmException) {
                if (e.fatal) throw e
                lastError = e
            } catch (t: Throwable) {
                lastError = t
            }
            if (attempt < MAX_LLM_ATTEMPTS - 1) delay(1000L * (attempt + 1))
        }
        throw lastError ?: LlmException("LLM 调用失败")
    }

    private fun emit(event: AgentEvent) {
        _events.tryEmit(event)
    }

    private fun AppSettings.toLlmConfig(apiKey: String): LlmConfig =
        LlmConfig(
            baseUrl = baseUrl,
            model = modelId,
            apiKey = apiKey,
            temperature = temperature,
            jsonMode = jsonMode
        )

    companion object {
        private const val ACTION_TIMEOUT_MS = 45_000L
        private const val ACTION_SETTLE_MS = 700L
        private const val MAX_LLM_ATTEMPTS = 3
        private const val PAUSE_TIMEOUT_MS = 10 * 60 * 1000L

        /** 非无限模式：连续失败多少步就停。 */
        private const val NON_UNLIMITED_FAILURE_LIMIT = 6

        /** 无限模式：连续失败到这一步才认定真的卡死（不是一次 UI 失败就退出）。 */
        private const val UNLIMITED_FAILURE_GUARD = 20

        /** 同一类错误最多提示重试几次，之后要求模型彻底换策略。 */
        private const val SAME_ERROR_RETRY_LIMIT = 3

        /** 自动驾驶兜底滑动的最大次数，防止无脑滑动死循环。 */
        private const val MAX_FALLBACKS = 5

        /** 无限模式下的硬保护：不是步数策略，而是防止计数/内存无限增长。 */
        private const val HARD_STEP_GUARD = 100_000

        private val AUTONOMOUS_KEYWORDS = listOf(
            "随便", "想干", "干什么", "自己玩", "自由", "干你想", "爱干嘛",
            "你决定", "自由活动", "随便看看", "随便玩", "打发时间"
        )
    }
}
