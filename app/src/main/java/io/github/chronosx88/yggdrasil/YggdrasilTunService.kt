package io.github.chronosx88.yggdrasil

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.google.gson.Gson
import dummy.ConduitEndpoint
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mobile.Mobile
import mobile.Yggdrasil
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext
import kotlin.experimental.or


class YggdrasilTunService : VpnService() {

    /** Maximum packet size is constrained by the MTU, which is given as a signed short.  */
    private val MAX_PACKET_SIZE = Short.MAX_VALUE.toInt()

    companion object {
        private var isRunning: Boolean = false
    }
    private var tunInterface: ParcelFileDescriptor? = null
    private lateinit var yggConduitEndpoint: ConduitEndpoint
    private var tunInputStream: InputStream? = null
    private var tunOutputStream: OutputStream? = null
    private lateinit var readCoroutine: CoroutineContext
    private lateinit var writeCoroutine: CoroutineContext

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra("COMMAND") == "STOP") {
            stopVpn()
        }
        return Service.START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        setupTunInterface()
    }

    private fun setupTunInterface() {
        val builder = Builder()
        val ygg = Yggdrasil()
        var configJson = Mobile.generateConfigJSON()
        val gson = Gson()

        var config = gson.fromJson(String(configJson), Map::class.java).toMutableMap()
        config = fixConfig(config)
        configJson = gson.toJson(config).toByteArray()

        yggConduitEndpoint = ygg.startJSON(configJson)
        val address = ygg.addressString // hack for getting generic ipv6 string from NodeID

        tunInterface = builder
            .addAddress(address, 7)
            .addRoute("10.0.0.0", 8)
            .addRoute("172.16.0.0", 12)
            .addRoute("192.168.0.0", 16)
            .addRoute("0200::", 7)
            .setMtu(MAX_PACKET_SIZE)
            .establish()

        tunInputStream = FileInputStream(tunInterface!!.fileDescriptor)
        tunOutputStream = FileOutputStream(tunInterface!!.fileDescriptor)
        readCoroutine = GlobalScope.launch {
            // FIXME it will throw exception (bad file descriptor) when coroutine will be canceled
            while (true) {
                readPacketsFromTun()
            }
        }
        writeCoroutine = GlobalScope.launch {
            while (true) {
                writePacketsToTun()
            }
        }
    }

    private fun getNonVpnNetworks(Address: String): Array<Network> {
        val cm =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = cm.allNetworks
        for (network in networks) {
            val linkProperties = cm.getLinkProperties(network)
            val addresses = linkProperties.linkAddresses
            for (addr in addresses) {
                if (addr.toString().startsWith(Address)) {
                    return arrayOf(network)
                }
            }
        }
        return emptyArray<Network>()
    }

    private fun fixConfig(config: MutableMap<Any?, Any?>): MutableMap<Any?, Any?> {
        val peers = arrayListOf<String>();
        peers.add("tcp://194.177.21.156:5066")
        peers.add("tcp://46.151.26.194:60575")
        peers.add("tcp://188.226.125.64:54321")
        val whiteList = arrayListOf<String>()
        whiteList.add("")
        val blackList = arrayListOf<String>()
        blackList.add("")
        config["Peers"] = peers
        config["Listen"] = ""
        config["AdminListen"] = "tcp://localhost:9001"
        config["IfName"] = "tun0"
        //config["EncryptionPublicKey"] = "b15633cf66e63a04f03e9d1a5b2ac6411af819cde9e74175cf574d5599b1296c"
        //config["EncryptionPrivateKey"] = "a39e2da3ccbb5afc3854574a2e3823e881d2d720754d6fdc877f57b252d3b521"
        //config["SigningPublicKey"] = "4f248483c094aea370fba86f1630ba5099cb230aa1337ab6ef6ff0b132be2c2b"
        //config["SigningPrivateKey"] = "e4d56eb2e15e25d9098731e39d661a80c523f31d38b71cbd0ad25a5cde745eac4f248483c094aea370fba86f1630ba5099cb230aa1337ab6ef6ff0b132be2c2b"
        (config["SessionFirewall"] as MutableMap<Any, Any>)["Enable"] = false
        //(config["SessionFirewall"] as MutableMap<Any, Any>)["AllowFromDirect"] = true
        //(config["SessionFirewall"] as MutableMap<Any, Any>)["AllowFromRemote"] = true
        //(config["SessionFirewall"] as MutableMap<Any, Any>)["AlwaysAllowOutbound"] = true
        //(config["SessionFirewall"] as MutableMap<Any, Any>)["WhitelistEncryptionPublicKeys"] = whiteList
        //(config["SessionFirewall"] as MutableMap<Any, Any>)["BlacklistEncryptionPublicKeys"] = blackList

        (config["SwitchOptions"] as MutableMap<Any, Any>)["MaxTotalQueueSize"] = 4194304
        if (config["AutoStart"] == null) {
            val tmpMap = emptyMap<String, Boolean>().toMutableMap()
            tmpMap["WiFi"] = false
            tmpMap["Mobile"] = false
            config["AutoStart"] = tmpMap
        }
        return config
    }

    private fun readPacketsFromTun() {
        if(tunInputStream != null) {
            var packet: ByteArray = ByteArray(MAX_PACKET_SIZE)
            // Read the outgoing packet from the input stream.
            var length = tunInputStream!!.read(packet)

            if (length > 0) {
                var buffer = ByteBuffer.allocate(length);
                buffer.put(packet, 0, length)
                buffer.limit(length)
                yggConduitEndpoint.send(buffer.array())

            }
        }
    }

    private fun writePacketsToTun() {
        if(tunOutputStream != null) {
            val buffer = yggConduitEndpoint.recv()
            tunOutputStream!!.write(buffer)
        }
    }

    fun stopVpn() {
        isRunning = false
        readCoroutine.cancel()
        writeCoroutine.cancel()
        tunInterface!!.close()
        tunInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSelf()
    }
}
