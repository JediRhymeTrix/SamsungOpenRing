package io.github.thevellichor.samsungopenring.core

import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

internal class RingConnection(
    private val context: Context,
    private val connectionCallback: ConnectionCallback,
) {
    companion object {
        private const val TAG = "OpenRing.Connection"
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val DISABLE_BEFORE_DISCONNECT_TIMEOUT_MS = 700L
    }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var txCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var gestureListener: GestureListener? = null
    @Volatile private var closing = false
    @Volatile private var gesturesDesired = false  // true = we want gestures on
    @Volatile private var disableRequested = false // true = WE sent the disable
    @Volatile private var disconnectAfterDisableAck = false
    private var device: BluetoothDevice? = null
    private var reconnectAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingSubscriptions = mutableListOf<BluetoothGattCharacteristic>()
    private val subscribedNotifyChars = mutableListOf<BluetoothGattCharacteristic>()
    private val pendingUnsubscriptions = mutableListOf<BluetoothGattCharacteristic>()
    private val importantNotifyChars = mutableSetOf<Pair<UUID, UUID>>()
    private val disconnectAfterDisableTimeout = Runnable {
        if (disconnectAfterDisableAck) {
            emit("Gesture disable ACK timeout — disconnecting anyway")
            disconnectNow()
        }
    }
    private var lastRawLogAtMs = 0L
    private var gestureEnableAckSeen = false
    private var notifyPruned = false
    private var notifyPruneInProgress = false

    val isConnected: Boolean get() = gatt != null && txCharacteristic != null

    fun connect(device: BluetoothDevice) {
        this.device = device
        this.closing = false
        this.reconnectAttempts = 0
        emit("BLE connecting to ${device.name} (${device.address})")
        try {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            emit("BLE connect FAILED: ${e.message}")
            mainHandler.post { connectionCallback.onError(OpenRingError.PermissionDenied) }
        }
    }

    fun disconnect() {
        if (gatt != null && txCharacteristic != null && gesturesDesired) {
            disableGesturesAndDisconnect()
            return
        }
        disconnectNow()
    }

    private fun disableGesturesAndDisconnect() {
        closing = true
        gesturesDesired = false
        disableRequested = true
        disconnectAfterDisableAck = true
        emit("Disabling gestures before disconnect")
        writeCommand(RingProtocol.CMD_DISABLE_GESTURES, "DISABLE_GESTURE")
        mainHandler.removeCallbacks(disconnectAfterDisableTimeout)
        mainHandler.postDelayed(disconnectAfterDisableTimeout, DISABLE_BEFORE_DISCONNECT_TIMEOUT_MS)
    }

    private fun disconnectNow() {
        closing = true
        disconnectAfterDisableAck = false
        mainHandler.removeCallbacks(disconnectAfterDisableTimeout)
        emit("BLE disconnecting (user-initiated)")
        mainHandler.removeCallbacksAndMessages(null)
        val g = gatt
        if (g != null) {
            try {
                g.disconnect()
                // close() will be called in onConnectionStateChange -> STATE_DISCONNECTED
            } catch (e: SecurityException) {
                emit("BLE disconnect error: ${e.message}")
                cleanupGatt(g)
            }
        } else {
            gatt = null
            txCharacteristic = null
        }
    }

    fun enableGestures(listener: GestureListener) {
        gestureListener = listener
        gesturesDesired = true
        disableRequested = false
        writeCommand(RingProtocol.CMD_ENABLE_GESTURES, "ENABLE_GESTURE")
    }

    fun disableGestures() {
        gesturesDesired = false
        disableRequested = true
        writeCommand(RingProtocol.CMD_DISABLE_GESTURES, "DISABLE_GESTURE")
        gestureListener = null
    }

    private fun writeCommand(data: ByteArray, label: String) {
        val tx = txCharacteristic
        val g = gatt
        if (tx == null || g == null) {
            emit("WRITE FAILED ($label): not connected")
            return
        }

        val hex = data.joinToString(" ") { "%02x".format(it) }
        emit("WRITE -> $label [$hex] on ${tx.uuid.toString().substring(0, 8)}")

        try {
            tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            g.writeCharacteristic(tx, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } catch (e: SecurityException) {
            emit("WRITE SecurityException: ${e.message}")
        }
    }

    private fun cleanupGatt(g: BluetoothGatt) {
        try {
            g.close()
        } catch (_: Exception) {}
        gatt = null
        txCharacteristic = null
    }

    private fun attemptReconnect() {
        if (closing) return
        val dev = device ?: return

        reconnectAttempts++
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            emit("Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up")
            mainHandler.post { connectionCallback.onError(OpenRingError.NotConnected) }
            return
        }

        val delay = RECONNECT_DELAY_MS * reconnectAttempts.coerceAtMost(3)
        emit("Reconnecting in ${delay / 1000}s (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")

        mainHandler.postDelayed({
            if (closing) return@postDelayed
            emit("Reconnecting to ${dev.name}...")
            try {
                gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                emit("Reconnect FAILED: ${e.message}")
                attemptReconnect()
            }
        }, delay)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val stateStr = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                else -> "UNKNOWN($newState)"
            }
            emit("BLE state: $stateStr (gatt_status=$status)")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        emit("Connection reported CONNECTED but status=$status, treating as failure")
                        cleanupGatt(g)
                        attemptReconnect()
                        return
                    }
                    reconnectAttempts = 0
                    emit("Starting GATT service discovery...")
                    try {
                        g.discoverServices()
                    } catch (e: SecurityException) {
                        emit("discoverServices failed: ${e.message}")
                        cleanupGatt(g)
                        attemptReconnect()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    cleanupGatt(g)
                    if (closing) {
                        emit("Clean disconnect complete")
                        mainHandler.post { connectionCallback.onDisconnected() }
                    } else {
                        emit("Unexpected disconnect")
                        mainHandler.post { connectionCallback.onDisconnected() }
                        attemptReconnect()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emit("Service discovery FAILED (status=$status)")
                cleanupGatt(g)
                attemptReconnect()
                return
            }

            emit("Service discovery OK — ${g.services.size} services found")

            var foundTx: BluetoothGattCharacteristic? = null
            for (service in g.services) {
                val svcShort = service.uuid.toString().substring(0, 8)
                val charCount = service.characteristics.size
                emit("  SVC $svcShort ($charCount chars)")

                for (char in service.characteristics) {
                    val charShort = char.uuid.toString().substring(0, 8)
                    val props = mutableListOf<String>()
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) props.add("R")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) props.add("W")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) props.add("WNR")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) props.add("N")
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) props.add("I")
                    emit("    CHAR $charShort [${props.joinToString(",")}]")

                    val svcId = service.uuid.toString()
                    if ((svcId.startsWith("00001b1b") || svcId.startsWith("00001b1a"))
                        && char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                        foundTx = char
                    }
                }
            }

            if (foundTx == null) {
                emit("TX characteristic NOT FOUND")
                mainHandler.post { connectionCallback.onError(OpenRingError.CharacteristicNotFound) }
                return
            }

            txCharacteristic = foundTx
            emit("TX selected: ${foundTx.uuid.toString().substring(0, 8)} on svc ${foundTx.service.uuid.toString().substring(0, 8)}")

            val notifyChars = g.services
                .flatMap { it.characteristics }
                .filter { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }

            emit("Subscribing to ${notifyChars.size} NOTIFY characteristics...")

            synchronized(pendingSubscriptions) {
                pendingSubscriptions.clear()
                pendingSubscriptions.addAll(notifyChars)
            }
            synchronized(subscribedNotifyChars) {
                subscribedNotifyChars.clear()
            }
            synchronized(pendingUnsubscriptions) {
                pendingUnsubscriptions.clear()
            }
            importantNotifyChars.clear()
            gestureEnableAckSeen = false
            notifyPruned = false
            notifyPruneInProgress = false
            subscribeNext(g)
        }

        private fun subscribeNext(g: BluetoothGatt) {
            val char: BluetoothGattCharacteristic?
            synchronized(pendingSubscriptions) {
                char = if (pendingSubscriptions.isNotEmpty()) pendingSubscriptions.removeAt(0) else null
            }

            if (char == null) {
                emit("All CCCD subscriptions complete — connection ready")
                try {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                } catch (e: SecurityException) {
                    emit("Connection priority request failed: ${e.message}")
                }
                mainHandler.post { connectionCallback.onConnected() }
                return
            }

            try {
                g.setCharacteristicNotification(char, true)
            } catch (e: SecurityException) {
                emit("  CCCD SecurityException: ${e.message}")
                subscribeNext(g)
                return
            }

            val cccd = char.getDescriptor(RingProtocol.CCCD_UUID)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            } else {
                subscribeNext(g)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int
        ) {
            val charShort = desc.characteristic.uuid.toString().substring(0, 8)

            if (notifyPruneInProgress) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    emit("  CCCD OFF OK: $charShort")
                } else {
                    emit("  CCCD OFF FAIL: $charShort (status=$status)")
                }
                unsubscribeNext(g)
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                emit("  CCCD OK: $charShort")
                synchronized(subscribedNotifyChars) {
                    subscribedNotifyChars.add(desc.characteristic)
                }
            } else {
                emit("  CCCD FAIL: $charShort (status=$status)")
            }
            subscribeNext(g)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, char: BluetoothGattCharacteristic, value: ByteArray
        ) {
            val hex = value.joinToString(" ") { "%02x".format(it) }
            val charShort = char.uuid.toString().substring(0, 8)
            val channelName = if (value.size >= 2) decodeChannel(value[0], value[1]) else null

            if (RingProtocol.isGestureNotification(value)) {
                val id = RingProtocol.parseGestureId(value)
                emit("RECV <- GESTURE DETECTED id=$id [$hex] ch=$channelName char=$charShort")
                rememberImportantNotifyChar(char)
                pruneNotifyCharsAfterGesture(g)
                val event = GestureEvent(gestureId = id)
                val listener = gestureListener
                if (listener != null) {
                    mainHandler.post { listener.onGesture(event) }
                }
            } else if (RingProtocol.isGestureEnableResponse(value)) {
                val success = RingProtocol.isResponseSuccess(value)
                emit("RECV <- GESTURE_ENABLE_ACK success=$success [$hex] char=$charShort")
                if (success) {
                    gestureEnableAckSeen = true
                    rememberImportantNotifyChar(char)
                }
            } else if (RingProtocol.isGestureDisableResponse(value)) {
                val success = RingProtocol.isResponseSuccess(value)
                rememberImportantNotifyChar(char)
                if (disableRequested) {
                    emit("RECV <- GESTURE_DISABLE_ACK success=$success [$hex] char=$charShort (our request)")
                    disableRequested = false
                    if (disconnectAfterDisableAck) {
                        disconnectNow()
                    }
                } else if (gesturesDesired) {
                    emit("RECV <- GESTURE_DISABLE_ACK success=$success [$hex] char=$charShort (EXTERNAL — Samsung's app disabled us)")
                    emit("Re-enabling gestures (overriding Samsung's disable)...")
                    writeCommand(RingProtocol.CMD_ENABLE_GESTURES, "RE-ENABLE_GESTURE")
                } else {
                    emit("RECV <- GESTURE_DISABLE_ACK success=$success [$hex] char=$charShort")
                }
            } else if (channelName != null) {
                emitThrottledRaw("RECV <- $channelName [$hex] char=$charShort")
            } else {
                emitThrottledRaw("RECV <- RAW [$hex] char=$charShort")
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, char: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val value = char.value ?: return
            onCharacteristicChanged(g, char, value)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int
        ) {
            val charShort = char.uuid.toString().substring(0, 8)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                emit("WRITE OK on $charShort")
            } else {
                emit("WRITE FAILED on $charShort (status=$status)")
            }
        }
    }

    private fun rememberImportantNotifyChar(char: BluetoothGattCharacteristic) {
        importantNotifyChars.add(char.service.uuid to char.uuid)
    }

    private fun pruneNotifyCharsAfterGesture(g: BluetoothGatt) {
        if (notifyPruned || !gestureEnableAckSeen) return

        val keep = importantNotifyChars.toSet()
        if (keep.isEmpty()) {
            emit("Skipping notify prune: no important notify characteristics observed")
            return
        }

        val toDisable = synchronized(subscribedNotifyChars) {
            subscribedNotifyChars
                .filterNot { keep.contains(it.service.uuid to it.uuid) }
                .toList()
        }

        if (toDisable.isEmpty()) {
            notifyPruned = true
            emit("Notify prune skipped: all subscribed characteristics are important")
            return
        }

        notifyPruned = true
        notifyPruneInProgress = true
        emit("Pruning ${toDisable.size} unused NOTIFY characteristics after gesture channel confirmed")
        synchronized(pendingUnsubscriptions) {
            pendingUnsubscriptions.clear()
            pendingUnsubscriptions.addAll(toDisable)
        }
        unsubscribeNext(g)
    }

    private fun unsubscribeNext(g: BluetoothGatt) {
        val char: BluetoothGattCharacteristic?
        synchronized(pendingUnsubscriptions) {
            char = if (pendingUnsubscriptions.isNotEmpty()) pendingUnsubscriptions.removeAt(0) else null
        }

        if (char == null) {
            notifyPruneInProgress = false
            emit("Notify prune complete")
            return
        }

        val charShort = char.uuid.toString().substring(0, 8)
        try {
            g.setCharacteristicNotification(char, false)
        } catch (e: SecurityException) {
            emit("  Notify off SecurityException: ${e.message}")
            unsubscribeNext(g)
            return
        }

        val cccd = char.getDescriptor(RingProtocol.CCCD_UUID)
        if (cccd != null) {
            emit("  CCCD OFF: $charShort")
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        } else {
            emit("  Notify off no CCCD: $charShort")
            unsubscribeNext(g)
        }
    }

    private fun decodeChannel(b0: Byte, b1: Byte): String? {
        if (b0 != b1) return null
        return when (b0.toInt() and 0xFF) {
            0x0a -> "CH10:Health"
            0x0b -> "CH11:Settings"
            0x0c -> "CH12:Debug"
            0x14 -> "CH20:Logging"
            0x15 -> "CH21:FOTA"
            0x16 -> "CH22:Gesture"
            0x17 -> "CH23:Heartbeat"
            0x1f -> "CH31:FindDevice"
            0x20 -> "CH32:Text"
            else -> "CH${b0.toInt() and 0xFF}"
        }
    }

    private fun emit(message: String) {
        Log.d(TAG, message)
        OpenRing.logger?.log(message)
    }

    private fun emitThrottledRaw(message: String) {
        Log.d(TAG, message)
        val now = System.currentTimeMillis()
        if (now - lastRawLogAtMs >= 30_000L) {
            lastRawLogAtMs = now
            OpenRing.logger?.log(message)
        }
    }
}
