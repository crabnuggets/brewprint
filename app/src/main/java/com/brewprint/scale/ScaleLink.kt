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
import com.brewprint.data.ScaleStatus
import com.brewprint.data.scaleBrewRunning
import com.brewprint.data.scalesToHold
import com.brewprint.data.scaleToSwitchTo
import com.brewprint.timer.BrewTimerService
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * The packet size asked for before readings are switched on, as the TIMEMORE app does. At Android's default of 23 a
 * packet carries 20 bytes, barely more than one 17-byte reading, so anything else the scale sends leaves readings
 * waiting in the scale and the weight falls further and further behind.
 */
private const val PACKET_BYTES = 247
/** How long to wait for the scale's answer about the packet size before switching readings on anyway. */
private const val PACKET_MILLIS = 2_000L

/**
 * The Bluetooth connections to the paired scales. While the app is on screen every paired scale is connected, so the
 * app knows which are on; the scale in use is the one whose readings and commands count. A running scale brew holds
 * only its own scale, with the app on screen or in the background. Everything else is released, so the TIMEMORE app
 * can use the scales. Everything runs on the main thread.
 */
@SuppressLint("MissingPermission") // Every Bluetooth call happens after allowed() is checked in sync().
object ScaleLink {
    /** One paired scale's connection. [ready] once its readings are switched on. */
    private class Link(val address: String) {
        var gatt: BluetoothGatt? = null
        var ready = false
        var battery: Int? = null
        /** Switches readings on once the packet size is settled, or after [PACKET_MILLIS] without an answer. */
        var subscribe: Runnable? = null

        /** The connection dropped or was released: nothing is waiting to switch its readings on. */
        fun forget(handler: Handler) {
            subscribe?.let(handler::removeCallbacks)
            subscribe = null
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val links = LinkedHashMap<String, Link>()
    /** The address of the scale in use. */
    private var active: String? = null
    private var app: Context? = null
    private var brew: ScaleBrew? = null
    /** Frames waiting to be written to the scale in use, one GATT operation at a time. */
    private val pending = ArrayDeque<ByteArray>()
    private var writing = false
    private var retries = 0
    private val silence = Runnable { brew?.lose(System.currentTimeMillis()) }
    private var log: ScaleLog? = null
    private val timing = LinkTiming(log = ::note)
    private val arrivals = Arrivals<BluetoothGatt>(post = { handler.post(it) }) { source, at, value -> links.values.find { it.gatt == source }?.let { read(it, value, at) } }
    private val mutableStatus = MutableStateFlow<Map<String, ScaleStatus>>(emptyMap())
    /** Which paired scales are on, by address, with their battery where known. */
    val status: StateFlow<Map<String, ScaleStatus>> = mutableStatus.asStateFlow()

    /** One line for Android's log and for the scale log that "Export data" includes. */
    private fun note(line: String) {
        Log.i(LOG_TAG, line)
        log?.add(line)
    }

    /** A scale in the log: the end of its address, as Android's own log lines give it, and whether it is the one in use. */
    private fun Link.label() = "Scale " + address.takeLast(5) + if (address == active) " (in use)" else ""

    fun brew(context: Context): ScaleBrew = brew ?: ScaleBrew(BrewRepository.get(context), ::write).also {
        brew = it
        // Bluetooth turned on while the app is open should connect without another lifecycle event.
        val app = context.applicationContext
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> sync(app)
                    BluetoothAdapter.STATE_OFF -> { links.keys.toList().forEach(::close); sync(app) }
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    /** Nearby devices, needed to connect from Android 12. */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Connects or releases the scales to match the app: called when the app opens or closes, and when a brew or the pairing changes. */
    fun sync(context: Context) {
        val app = context.applicationContext
        this.app = app
        if (log == null) log = ScaleLog.get(app)
        val state = BrewRepository.get(app).state.value
        val inUse = state.scale
        val now = System.currentTimeMillis()
        val brewing = state.scaleBrewRunning(now)
        // The reader exists before any early return, so the watchdog can fall back to the clock and Bluetooth turning on is heard.
        if (inUse != null) brew(app)
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled && allowed(app) }
        val wanted = if (adapter == null) emptyList() else state.scalesToHold(BrewTimerService.appOpen, now).map { it.address }
        var changed = false
        links.keys.filter { it !in wanted }.forEach { close(it); changed = true }
        wanted.filter { it !in links }.forEach { address ->
            val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return@forEach
            val link = Link(address)
            links[address] = link
            // Automatic connection waits for the scale to come in range or be switched on, without scanning.
            link.gatt = device.connectGatt(app, true, callback(link), BluetoothDevice.TRANSPORT_LE)
            changed = true
        }
        if (inUse?.address != active) { use(inUse?.address); changed = true }
        if (changed) note("Scales held: " + links.values.joinToString { it.label() }.ifEmpty { "none" })
        // A brew whose scale is not there yet follows the clock after a short wait for it.
        if (changed && brewing && links[active]?.ready != true) {
            handler.removeCallbacks(silence)
            handler.postDelayed(silence, CONNECT_MILLIS)
        }
        publish()
        // The scale in use may have just changed to one that is off, as after forgetting the last one used.
        choose()
    }

    /** Makes [address] the scale in use: the brew hears that the old one is gone, then the new one if it is on. */
    private fun use(address: String?) {
        dropActive()
        active = address
        if (links[address]?.ready == true) brew?.connected(true, System.currentTimeMillis())
    }

    /** The scale in use is gone, or no longer the one in use: nothing queued or timed belongs to the next one. */
    private fun dropActive() {
        handler.removeCallbacks(silence)
        pending.clear()
        writing = false
        timing.reset()
        brew?.connected(false, System.currentTimeMillis())
    }

    private fun close(address: String) {
        val link = links.remove(address) ?: return
        // Its callbacks compare against this, so one already on its way is ignored instead of acting on a closed connection.
        val gatt = link.gatt
        link.gatt = null
        link.forget(handler)
        gatt?.run { disconnect(); close() }
        if (address == active) dropActive()
    }

    private fun publish() {
        mutableStatus.value = links.values.associate { it.address to ScaleStatus(it.ready, it.battery) }
    }

    /** With the scale in use off and another paired scale on, the app uses that one, with no question. */
    private fun choose() {
        val app = app ?: return
        val repository = BrewRepository.get(app)
        val next = repository.state.value.scaleToSwitchTo(links.values.filter { it.ready }.map { it.address }.toSet()) ?: return
        if (repository.useScale(next.address).isSuccess) sync(app)
    }

    private fun write(frame: ByteArray) {
        pending.addLast(frame)
        if (!writing) writeNext()
    }

    /** Starts the next queued write. A busy stack refuses it, so it is retried a few times before being dropped. */
    private fun writeNext() {
        val target = links[active]?.gatt
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

    /**
     * Readings carry the time they arrived, so a late pass on the main thread still times pours and steadiness right.
     * Only the scale in use is read; another paired scale that is on reports its battery and no more.
     */
    private fun read(link: Link, value: ByteArray, now: Long) {
        val brew = brew ?: return
        val inUse = link.address == active
        ScaleProtocol.parse(value).forEach { frame ->
            when (frame) {
                is ScaleProtocol.Reading -> if (inUse) {
                    handler.removeCallbacks(silence)
                    handler.postDelayed(silence, SILENCE_MILLIS)
                    timing.add(now, System.currentTimeMillis())
                    brew.reading(now, frame.grams, frame.flow, frame.overload)
                }
                is ScaleProtocol.Battery -> {
                    link.battery = frame.percent
                    publish()
                    if (inUse) brew.battery(frame.percent)
                }
            }
        }
    }

    private fun callback(link: Link) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(changed: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (changed != link.gatt) return@post
                note("${link.label()} " + (if (newState == BluetoothProfile.STATE_CONNECTED) "connected" else "disconnected") + ", status $status")
                if (newState == BluetoothProfile.STATE_CONNECTED) changed.discoverServices()
                else {
                    link.forget(handler)
                    link.ready = false
                    link.battery = null
                    if (link.address == active) dropActive()
                    publish()
                    // Wait for the scale to come back, as the first connection did.
                    changed.connect()
                    choose()
                }
            }
        }

        /** The packet size is asked for first, so that readings start in packets with room to spare. Android and the scale settle the size between them. */
        override fun onServicesDiscovered(discovered: BluetoothGatt, status: Int) {
            handler.post {
                if (discovered != link.gatt) return@post
                link.forget(handler)
                val subscribe = Runnable { link.subscribe = null; subscribe(discovered) }
                if (discovered.requestMtu(PACKET_BYTES)) {
                    link.subscribe = subscribe
                    handler.postDelayed(subscribe, PACKET_MILLIS)
                } else {
                    note("${link.label()} packet size request refused")
                    subscribe.run()
                }
            }
        }

        override fun onMtuChanged(changed: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (changed != link.gatt) return@post
                note("${link.label()} packet size $mtu, status $status")
                link.subscribe?.let { handler.removeCallbacks(it); it.run() }
            }
        }

        private fun subscribe(discovered: BluetoothGatt) {
            if (discovered != link.gatt) return
            val notify = discovered.getService(SERVICE)?.getCharacteristic(NOTIFY)
            val config = notify?.getDescriptor(CONFIG)
            val started = notify != null && config != null && discovered.setCharacteristicNotification(notify, true) &&
                if (Build.VERSION.SDK_INT >= 33) discovered.writeDescriptor(config, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                else notifyBefore33(discovered, config)
            // Without readings the scale is of no use; dropping the link makes the automatic connection try again.
            if (!started) discovered.disconnect()
        }

        override fun onDescriptorWrite(source: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (source != link.gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { source.disconnect(); return@post }
                link.ready = true
                publish()
                note("${link.label()} ready")
                if (link.address == active) brew?.connected(true, System.currentTimeMillis()) else choose()
            }
        }

        override fun onCharacteristicWrite(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post { if (source == links[active]?.gatt) written(status == BluetoothGatt.GATT_SUCCESS) }
        }

        override fun onCharacteristicChanged(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            arrivals.add(source, value)
        }

        /**
         * The connection interval the phone and the scale settled on, each time it changes: what the link really runs
         * at, whatever was requested. Android calls this, though it is not in the SDK this app is built against, so it
         * can't be marked as an override.
         */
        @Suppress("unused")
        fun onConnectionUpdated(updated: BluetoothGatt, interval: Int, latency: Int, timeout: Int, status: Int) {
            handler.post {
                if (updated == link.gatt) note("${link.label()} connection interval ${interval * 1.25} ms, latency $latency, timeout ${timeout * 10} ms, status $status")
            }
        }

        @Deprecated("Called instead of the overload above before Android 13")
        override fun onCharacteristicChanged(source: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = characteristic.value ?: return
            if (Build.VERSION.SDK_INT < 33) arrivals.add(source, value.copyOf())
        }
    }
}
