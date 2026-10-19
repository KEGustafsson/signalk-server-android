package fi.pocketsk.bridge

import fi.pocketsk.bridge.BridgeProtocol.Device

/**
 * The serial devices of every kind ([UsbSerial], [BluetoothSerial]) as one list: the plugin is
 * told it ([send], a `devices` line) whenever it changes, and the Dock is shown it ([show]) every
 * time, byte counts and all. The plugin hears of the devices the owner chose only: a paired
 * Bluetooth headset the owner never picked is the Dock's business, not Signal K's. Pure, so it is
 * unit-tested; the sources call it from their own threads.
 */
internal class DeviceList(private val send: (String) -> Unit, private val show: (List<Device>) -> Unit) {
    private val byKind = LinkedHashMap<String, List<Device>>()
    /** The line the plugin was last sent, so it hears of changes only; null after [resend]. */
    private var told: String? = null

    /** The devices of [kind] are now [devices]. */
    @Synchronized
    fun update(kind: String, devices: List<Device>) {
        byKind[kind] = devices
        publish()
    }

    /** A plugin has just connected: it hears the list, which is otherwise sent on change only. */
    @Synchronized
    fun resend() {
        told = null
        publish()
    }

    private fun publish() {
        val all = byKind.values.flatten()
        val line = BridgeProtocol.devices(all.filter { it.chosen })
        if (line != told) {
            told = line
            send(line)
        }
        show(all)
    }
}
