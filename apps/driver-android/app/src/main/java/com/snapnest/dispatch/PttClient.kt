package com.snapnest.dispatch

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import kotlinx.coroutines.*

class PttClient(
    context: Context,
    private val sessionStore: SessionStore,
    private val onState: (State) -> Unit
) {
    enum class State { CONNECTING, READY, BUSY, TALKING, OFFLINE }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var room: Room? = null
    private var leaseToken: String? = null
    private var heartbeatJob: Job? = null
    @Volatile private var wantsToTalk = false

    fun connect() {
        scope.launch {
            runCatching { ensureConnected() }
                .onFailure { onState(State.OFFLINE) }
        }
    }

    fun beginTalk() {
        wantsToTalk = true
        scope.launch {
            try {
                val activeRoom = ensureConnected()
                val lease = DriverApi.acquirePttFloor(sessionStore)
                if (!wantsToTalk) {
                    if (lease.granted && lease.leaseToken.isNotBlank()) DriverApi.releasePttFloor(sessionStore, lease.leaseToken)
                    return@launch
                }
                if (!lease.granted || lease.leaseToken.isBlank()) {
                    onState(State.BUSY)
                    return@launch
                }

                leaseToken = lease.leaseToken
                val enabled = activeRoom.localParticipant.setMicrophoneEnabled(true)
                if (!enabled) {
                    DriverApi.releasePttFloor(sessionStore, lease.leaseToken)
                    leaseToken = null
                    onState(State.OFFLINE)
                    return@launch
                }
                onState(State.TALKING)
                startHeartbeat(lease.leaseToken)
            } catch (_: Throwable) {
                leaseToken?.let { runCatching { DriverApi.releasePttFloor(sessionStore, it) } }
                leaseToken = null
                onState(State.OFFLINE)
            }
        }
    }

    fun endTalk() {
        wantsToTalk = false
        scope.launch {
            heartbeatJob?.cancel()
            heartbeatJob = null
            runCatching { room?.localParticipant?.setMicrophoneEnabled(false) }
            leaseToken?.let { token -> runCatching { DriverApi.releasePttFloor(sessionStore, token) } }
            leaseToken = null
            onState(if (room != null) State.READY else State.OFFLINE)
        }
    }

    private suspend fun ensureConnected(): Room {
        room?.let { return it }
        onState(State.CONNECTING)
        val connection = withContext(Dispatchers.IO) { DriverApi.pttConnection(sessionStore) }
        if (!connection.enabled || connection.serverUrl.isBlank() || connection.participantToken.isBlank()) {
            throw IllegalStateException("PTT is not configured yet.")
        }
        val connected = LiveKit.create(appContext)
        connected.connect(connection.serverUrl, connection.participantToken)
        connected.localParticipant.setMicrophoneEnabled(false)
        room = connected
        onState(State.READY)
        return connected
    }

    private fun startHeartbeat(token: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && wantsToTalk) {
                delay(5_000)
                val lease = runCatching { DriverApi.heartbeatPttFloor(sessionStore, token) }.getOrNull()
                if (lease?.granted != true) {
                    wantsToTalk = false
                    runCatching { room?.localParticipant?.setMicrophoneEnabled(false) }
                    leaseToken = null
                    onState(State.BUSY)
                    break
                }
            }
        }
    }

    fun close() {
        wantsToTalk = false
        heartbeatJob?.cancel()
        val token = leaseToken
        leaseToken = null
        scope.launch {
            runCatching { room?.localParticipant?.setMicrophoneEnabled(false) }
            if (!token.isNullOrBlank()) runCatching { DriverApi.releasePttFloor(sessionStore, token) }
            room?.disconnect()
            room = null
            onState(State.OFFLINE)
        }.invokeOnCompletion { scope.cancel() }
    }
}
