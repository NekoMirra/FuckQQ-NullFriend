package com.fuckqq.nullfriend.service

import com.fuckqq.nullfriend.data.DetectorRepository
import com.fuckqq.nullfriend.data.Prefs
import com.fuckqq.nullfriend.domain.AccountState
import com.fuckqq.nullfriend.domain.DetectionOutcome
import com.fuckqq.nullfriend.domain.DiffEngine
import com.fuckqq.nullfriend.domain.FriendSnapshot
import com.fuckqq.nullfriend.provider.FriendListProvider
import com.fuckqq.nullfriend.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class DetectionService(
    private val repository: DetectorRepository,
    private val provider: FriendListProvider,
    private val prefs: Prefs,
    private val notifier: Notifier
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val running = AtomicBoolean(false)
    private var periodicJob: Job? = null
    private var startupJob: Job? = null

    var lastOutcome: DetectionOutcome? = null
        private set

    fun scheduleStartupCheck() {
        startupJob?.cancel()
        val delaySec = prefs.startupDelaySec.coerceIn(0, 120)
        startupJob = scope.launch {
            delay(delaySec * 1000L)
            runCheck(trigger = "startup")
        }
    }

    fun reschedulePeriodic() {
        periodicJob?.cancel()
        val minutes = prefs.intervalMinutes
        if (minutes <= 0) return
        periodicJob = scope.launch {
            while (isActive) {
                delay(minutes * 60_000L)
                runCheck(trigger = "timer")
            }
        }
    }

    fun refreshAsync(onDone: (DetectionOutcome) -> Unit = {}) {
        scope.launch {
            val outcome = runCheck(trigger = "manual")
            withContext(Dispatchers.Main) { onDone(outcome) }
        }
    }

    suspend fun runCheck(trigger: String): DetectionOutcome = mutex.withLock {
        if (!running.compareAndSet(false, true)) {
            return DetectionOutcome.Skipped("already running").also { lastOutcome = it }
        }
        try {
            Log.i("Detection start trigger=$trigger")
            val result = provider.fetch()
            if (result.isFailure) {
                val reason = result.exceptionOrNull()?.message ?: "unknown error"
                Log.w("Fetch failed: $reason")
                provider.currentOwnerUin()?.let { repository.setLastError(it, reason) }
                return DetectionOutcome.Failed(reason).also { lastOutcome = it }
            }
            val list = result.getOrThrow()
            val now = System.currentTimeMillis()
            val existing = repository.getSnapshot(list.ownerUin)
            if (existing == null) {
                repository.putSnapshot(
                    FriendSnapshot(
                        ownerUin = list.ownerUin,
                        friends = list.friends,
                        updatedAt = now,
                        source = list.source
                    )
                )
                repository.upsertAccount(
                    AccountState(
                        ownerUin = list.ownerUin,
                        baselineAt = now,
                        lastCheckAt = now,
                        lastSource = list.source,
                        lastError = null
                    )
                )
                Log.i("Baseline created owner=${Log.maskUin(list.ownerUin)} count=${list.friends.size}")
                return DetectionOutcome.BaselineCreated(
                    ownerUin = list.ownerUin,
                    count = list.friends.size,
                    source = list.source
                ).also { lastOutcome = it }
            }

            val diff = DiffEngine.diff(existing, list)
            if (diff.removed.isNotEmpty()) {
                repository.insertRemovals(
                    ownerUin = list.ownerUin,
                    removed = diff.removed,
                    detectedAt = now,
                    checkSource = list.source
                )
                if (prefs.notifyEnabled) {
                    notifier.notifyRemovals(list.ownerUin, diff.removed)
                }
            }
            repository.putSnapshot(
                FriendSnapshot(
                    ownerUin = list.ownerUin,
                    friends = list.friends,
                    updatedAt = now,
                    source = list.source
                )
            )
            repository.upsertAccount(
                AccountState(
                    ownerUin = list.ownerUin,
                    baselineAt = repository.getAccount(list.ownerUin)?.baselineAt ?: now,
                    lastCheckAt = now,
                    lastSource = list.source,
                    lastError = null
                )
            )
            Log.i(
                "Checked owner=${Log.maskUin(list.ownerUin)} " +
                    "prev=${diff.previousCount} curr=${diff.currentCount} removed=${diff.removed.size}"
            )
            return DetectionOutcome.Checked(
                ownerUin = list.ownerUin,
                previousCount = diff.previousCount,
                currentCount = diff.currentCount,
                removed = diff.removed,
                source = list.source
            ).also { lastOutcome = it }
        } catch (t: Throwable) {
            Log.e("Detection error", t)
            return DetectionOutcome.Failed(t.message ?: "exception").also { lastOutcome = it }
        } finally {
            running.set(false)
        }
    }
}
