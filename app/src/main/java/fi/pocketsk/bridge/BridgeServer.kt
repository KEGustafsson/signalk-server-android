package fi.pocketsk.bridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import fi.pocketsk.net.Backoff
import fi.pocketsk.net.Waiter
import fi.pocketsk.net.transportThread
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's end of the bridge socket (docs/PLAN.md §7): a Unix stream socket at [path], in the
 * app's private files, so only this app's own processes (the server it runs, as the same user)
 * can reach it; each connection's peer is checked to be this app's user anyway, since the file
 * mode is the only other guard. One peer at a time: a new connection (a restarted server) takes
 * the place of the old one.
 *
 * Lines out go through a bounded queue drained by a writer thread, so a plugin that stops reading
 * costs the oldest readings, never a blocked sensor thread. Lines in are cut at
 * [BridgeProtocol.MAX_LINE]; a longer one closes the connection. Every callback runs on one of
 * this class's threads, never the main one.
 */
internal class BridgeServer(
    private val path: File,
    private val onConnected: (Peer) -> Unit,
    private val onLine: (Peer, String) -> Unit,
    private val onDisconnected: (Peer) -> Unit,
    private val note: (String) -> Unit,
) {
    @Volatile private var running = false
    private var loop: Thread? = null
    private val waiter = Waiter()

    // Guarded by `this`.
    private var server: LocalServerSocket? = null
    private var bound: LocalSocket? = null
    private var peer: Peer? = null

    @Synchronized
    fun start() {
        if (loop != null) return
        running = true
        loop = transportThread("bridge-accept", onError = { note("bridge stopped: $it") }, body = ::acceptLoop)
    }

    /** Closes the socket and the connection; returns once the accept thread has gone. */
    fun stop() {
        val thread = synchronized(this) {
            running = false
            loop.also { loop = null }
        } ?: return
        waiter.wake()
        // accept() does not return when its socket is closed under it, so a connection of our
        // own wakes it; the loop sees `running` false and closes everything.
        try {
            LocalSocket().use { it.connect(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM)) }
        } catch (_: IOException) {
            // Not listening (between binds): the waiter wake above ends the loop instead.
        }
        thread.join(JOIN_MS)
        closeAll()
    }

    private fun acceptLoop() {
        val backoff = Backoff(firstMs = 1_000, maxMs = 30_000)
        while (running) {
            val listening = try {
                bind()
            } catch (e: IOException) {
                note("bridge socket: cannot listen at $path: $e")
                closeAll()
                waiter.await(backoff.next())
                continue
            }
            backoff.reset()
            try {
                while (running) {
                    val s = listening.accept()
                    if (!running) {
                        s.close()
                        break
                    }
                    admit(s)
                }
            } catch (e: IOException) {
                if (running) note("bridge socket: $e")
            }
            closeAll()
            // A socket that keeps failing is bound again after a pause, not in a tight loop.
            if (running) waiter.await(backoff.next())
        }
    }

    private fun bind(): LocalServerSocket {
        path.delete()                 // a socket file left by a process that died is in the way
        val socket = LocalSocket(LocalSocket.SOCKET_STREAM)
        socket.bind(LocalSocketAddress(path.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        val listening = LocalServerSocket(socket.fileDescriptor)
        synchronized(this) {
            bound = socket
            server = listening
        }
        return listening
    }

    private fun admit(s: LocalSocket) {
        val uid = try {
            s.peerCredentials.uid
        } catch (e: IOException) {
            note("bridge socket: cannot tell who connected: $e")
            s.close()
            return
        }
        if (uid != Process.myUid()) {
            note("bridge socket: refused a connection from uid $uid")
            s.close()
            return
        }
        val next = Peer(s)
        val old = synchronized(this) { peer.also { peer = next } }
        old?.close()
        next.start()
    }

    private fun closeAll() {
        val (p, srv, b) = synchronized(this) {
            Triple(peer, server, bound).also {
                peer = null
                server = null
                bound = null
            }
        }
        p?.close()
        // Both hold the same FileDescriptor, which the first close invalidates: no double close.
        try { srv?.close() } catch (_: IOException) {}
        try { b?.close() } catch (_: IOException) {}
        path.delete()
    }

    /** One connected plugin. */
    inner class Peer(private val socket: LocalSocket) {
        private val queue = LinkedBlockingQueue<String>(QUEUE)
        private val closed = AtomicBoolean(false)
        private val open get() = !closed.get()
        @Volatile var dropped = 0
            private set

        /** Queues [line] for the plugin; with the queue full, the oldest line goes. */
        fun send(line: String) {
            if (!open) return
            while (!queue.offer(line)) {
                if (queue.poll() != null) dropped++
            }
        }

        fun start() {
            transportThread("bridge-write", onError = { close() }) {
                val out = socket.outputStream
                while (open) {
                    val line = queue.take()
                    if (!open) break
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
            transportThread("bridge-read", onError = { if (open) note("bridge read: $it"); close() }) {
                onConnected(this)
                val input = socket.inputStream
                val line = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                while (open) {
                    val n = input.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) {
                        val b = buf[i]
                        if (b == NL) {
                            if (line.size() > 0) onLine(this, line.toString(Charsets.UTF_8.name()))
                            line.reset()
                        } else {
                            line.write(b.toInt())
                            if (line.size() > BridgeProtocol.MAX_LINE) throw IOException("a line longer than ${BridgeProtocol.MAX_LINE} bytes")
                        }
                    }
                }
                close()
            }
        }

        /** Ends the connection; [onDisconnected] is told once, whichever thread gets here first. */
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            queue.clear()
            queue.offer(WAKE)          // the writer is waiting in take()
            try { socket.shutdownInput() } catch (_: IOException) {}
            try { socket.close() } catch (_: IOException) {}
            synchronized(this@BridgeServer) { if (peer === this) peer = null }
            onDisconnected(this)
        }
    }

    private companion object {
        const val QUEUE = 256
        const val JOIN_MS = 2_000L
        const val NL = '\n'.code.toByte()
        const val WAKE = ""
    }
}
