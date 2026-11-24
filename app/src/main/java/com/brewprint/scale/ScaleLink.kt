package com.brewprint.scale

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.brewprint.data.BrewRepository
import com.brewprint.data.LOG_TAG
import com.brewprint.data.ScaleProtocol
import com.brewprint.data.scaleBrewRunning
import com.brewprint.timer.BrewTimerService
import java.util.UUID

private fun uuid(short: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))
private val SERVICE = uuid(0xFFF0)
private val NOTIFY = uuid(0xFFF1)
private val WRITE = uuid(0xFFF2)
private val CONFIG = uuid(0x2902)

/** How long a connected scale may stay silent before a running scale brew follows the clock. */
private const val SILENCE_MILLIS = 1_000L
/** How long a running scale brew waits for a scale it is not connected to. */
private const val CONNECT_MILLIS = 5_000L
private const val WRITE_GAP_MILLIS = 150L
private const val WRITE_RETRIES = 3

/**
 * The Bluetooth connection to the paired scale. It is held while the app is on screen or a scale brew runs,
 * and released otherwise so the TIMEMORE app can use the scale. Everything runs on the main thread.
 */
@SuppressLint("MissingPermission") // Every Bluetooth call happens after allowed() is checked in sync().
object ScaleLink {
    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var brew: ScaleBrew? = null
    /** Frames waiting to be written, one GATT operation at a time. */
    private val pending = ArrayDeque<ByteArray>()
    private var writing = false
    private var retries = 0
    private val silence = Runnable { brew?.lose(System.currentTimeMillis()) }
    private val timing = LinkTiming { Log.i(LOG_TAG, it) }
    private val arrivals = Arrivals<BluetoothGatt>(post = { handler.post(it) }) { source, at, value -> if (source == gatt) read(value, at) }

    fun brew(context: Context): ScaleBrew = brew ?: ScaleBrew(BrewRepository.get(context), ::write).also {
        brew = it
        // Bluetooth turned on while the app is open should connect without another lifecycle event.
        val app = context.applicationContext
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> sync(app)
                    BluetoothAdapter.STATE_OFF -> { release(); sync(app) }
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    /** Nearby devices, needed to connect from Android 12. */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Connects or releases the scale to match the app: called when the app opens or closes, and when a brew or the pairing changes. */
    fun sync(context: Context) {
        val app = context.applicationContext
        val state = BrewRepository.get(app).state.value
        val paired = state.scale
        val brewing = state.scaleBrewRunning(System.currentTimeMillis())
        // The reader exists before any early return, so the watchdog can fall back to the clock and Bluetooth turning on is heard.
        if (paired != null) brew(app)
        val wanted = paired != null && allowed(app) && (BrewTimerService.appOpen || brewing)
        if (wanted && gatt != null && address == paired?.address) return
        release()
        if (brewing) handler.postDelayed(silence, CONNECT_MILLIS)
        if (!wanted || paired == null) return
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled } ?: return
        val device = runCatching { adapter.getRemoteDevice(paired.address) }.getOrNull() ?: return
        address = paired.address
        // Automatic connection waits for the scale to come in range or be switched on, without scanning.
        gatt = device.connectGatt(app, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun release() {
        handler.removeCallbacks(silence)
        gatt?.run { disconnect(); close() }
        gatt = null
        address = null
        pending.clear()
        writing = false
        timing.reset()
        brew?.connected(false, System.currentTimeMillis())
    }

    private fun write(frame: ByteArray) {
        pending.addLast(frame)
        if (!writing) writeNext()
    }

    /** Starts the next queued write. A busy stack refuses it, so it is retried a few times before being dropped. */
    private fun writeNext() {
        val target = gatt
        val characteristic = target?.getService(SERVICE)?.getCharacteristic(WRITE)
        val frame = pending.firstOrNull()
        if (target == null || characteristic == null || frame == null) { pending.clear(); writing = false; return }
        writing = true
        val started = if (Build.VERSION.SDK_INT >= 33) {
            target.writeCharacteristic(characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
        } else writeBefore33(target, characteristic, frame)
        if (started) return
        writing = false
        if (++retries > WRITE_RETRIES) { pending.removeFirst(); retries = 0 }
        handler.postDelayed({ if (!writing) writeNext() }, WRITE_GAP_MILLIS)
    }

    /** The previous write left the phone; the next one may start after a short gap. */
    /** The previous write finished. A failed one is tried again within the same limit as a refused one. */
    private fun written(success: Boolean) {
        writing = false
        if (success || ++retries > WRITE_RETRIES) { pending.removeFirstOrNull(); retries = 0 }
        if (pending.isNotEmpty()) handler.postDelayed({ if (!writing) writeNext() }, WRITE_GAP_MILLIS)
    }

    @Suppress("DEPRECATION")
    private fun writeBefore33(target: BluetoothGatt, characteristic: BluetoothGattCharacteristic, frame: ByteArray): Boolean {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        characteristic.value = frame
        return target.writeCharacteristic(characteristic)
    }

    @Suppress("DEPRECATION")
    private fun notifyBefore33(target: BluetoothGatt, config: BluetoothGattDescriptor): Boolean {
        config.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return target.writeDescriptor(config)
    }

    /** Readings carry the time they arrived, so a late pass on the main thread still times pours and steadiness right. */
    private fun read(value: ByteArray, now: Long) {
        val brew = brew ?: return
        ScaleProtocol.parse(value).forEach { frame ->
            when (frame) {
                is ScaleProtocol.Reading -> {
                    handler.removeCallbacks(silence)
                    handler.postDelayed(silence, SILENCE_MILLIS)
                    timing.add(now, System.currentTimeMillis())
                    brew.reading(now, frame.grams, frame.flow, frame.overload)
                }
                is ScaleProtocol.Battery -> brew.battery(frame.percent)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(changed: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (changed != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) changed.discoverServices()
                else {
                    pending.clear()
                    writing = false
                    timing.reset()
                    brew?.connected(false, System.currentTimeMillis())
                    // Wait for the scale to come back, as the first connection did.
                    changed.connect()
                }
            }
        }

        override fun onServicesDiscovered(discovered: BluetoothGatt, status: Int) {
            handler.post {
                if (discovered != gatt) return@post
                val notify = discovered.getService(SERVICE)?.getCharacteristic(NOTIFY)
                val config = notify?.getDescriptor(CONFIG)
                val started = notify != null && config != null && discovered.setCharacteristicNotification(notify, true) &&
                    if (Build.VERSION.SDK_INT >= 33) discovered.writeDescriptor(config, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                    else notifyBefore33(discovered, config)
                // Without readings the scale is of no use; dropping the link makes the automatic connection try again.
                if (!started) discovered.disconnect()
            }
        }

        override fun onDescriptorWrite(source: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (source != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { source.disconnect(); return@post }
                // Android's usual interval holds a reading back by up to 50 ms; the short one, by up to 15 ms.
                val short = source.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                Log.i(LOG_TAG, "Scale connected, short connection interval " + if (short) "requested" else "refused")
                brew?.connected(true, System.currentTimeMillis())
            }
        }

        override fun onCharacteristicWrite(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post { if (source == gatt) written(status == BluetoothGatt.GATT_SUCCESS) }
        }

        override fun onCharacteristicChanged(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            arrivals.add(source, value)
        }

        @Deprecated("Called instead of the overload above before Android 13")
        override fun onCharacteristicChanged(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = characteristic.value ?: return
            if (Build.VERSION.SDK_INT < 33) arrivals.add(source, value.copyOf())
        }
    }
}
