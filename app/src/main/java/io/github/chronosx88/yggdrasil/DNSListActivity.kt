package io.github.chronosx88.yggdrasil

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.chronosx88.yggdrasil.models.DNSInfo
import io.github.chronosx88.yggdrasil.models.config.SelectDNSInfoListAdapter
import io.github.chronosx88.yggdrasil.models.config.Utils.Companion.deserializeStringList2DNSInfoSet
import io.github.chronosx88.yggdrasil.models.config.Utils.Companion.ping
import io.github.chronosx88.yggdrasil.models.config.Utils.Companion.serializeDNSInfoSet2StringList
import kotlinx.coroutines.*
import java.net.InetAddress
import kotlin.concurrent.thread


class DNSListActivity : AppCompatActivity() {

    companion object {
        val allDNS = arrayListOf(
            DNSInfo(
            InetAddress.getByName("[301:2522::53]"),
            "CZ",
            "DNS implementation for Yggdrasil. https://github.com/Revertron/wyrd"),
            DNSInfo(InetAddress.getByName("[301:2923::53]"),
            "SK",
            "DNS implementation for Yggdrasil. https://github.com/Revertron/wyrd"),
            DNSInfo(InetAddress.getByName("[300:4523::53]"),
                "DE",
                "DNS implementation for Yggdrasil. https://github.com/Revertron/wyrd"),
            DNSInfo(InetAddress.getByName("[303:8b1a::53]"),
            "RU",
            "DNS implementation for Yggdrasil. https://github.com/Revertron/wyrd")
        )
    }

    var isLoading = true;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dns_list)
        setSupportActionBar(findViewById(R.id.toolbar))
        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { view ->
            addNewDNS()
        }
        var extras = intent.extras
        var dnsList = findViewById<ListView>(R.id.dnsList)
        var adapter = SelectDNSInfoListAdapter(this, arrayListOf(), mutableSetOf())
        dnsList.adapter = adapter
        var cd = deserializeStringList2DNSInfoSet(
            extras!!.getStringArrayList(MainActivity.DNS_LIST)!!
        )
        thread(start = true) {
            try {

                for (d in cd) {
                    var ping = ping(d.address, 53)
                    d.ping = ping
                }
                for (dns in allDNS) {
                    if (cd.contains(dns)) {
                        continue
                    }
                    var ping = ping(dns.address, 53)
                    dns.ping = ping
                    runOnUiThread(
                        Runnable
                        {
                            adapter.addItem(dns)
                            adapter.sort()
                        }
                    )
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
            runOnUiThread(
                Runnable
                {
                    var currentDNS = ArrayList(cd.sortedWith(compareBy { it.ping }))
                    adapter.addAll(0, currentDNS)
                    isLoading = false
                }
            )
        }

    }

    private fun addNewDNS() {
        val view: View = LayoutInflater.from(this).inflate(R.layout.new_dns_dialog, null)
        val countryCode: String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            this.resources.configuration.locales[0].country
        } else {
            this.resources.configuration.locale.country
        }

        var ccp = view.findViewById<com.hbb20.CountryCodePicker>(R.id.ccp)
        ccp.setCountryForNameCode(countryCode)
        val ab: AlertDialog.Builder = AlertDialog.Builder(this)
        ab.setCancelable(true).setView(view)
        var ad = ab.show()
        var addButton = view.findViewById<Button>(R.id.add)
        addButton.setOnClickListener{
            var ipInput = view.findViewById<TextView>(R.id.ipInput)
            var ccpInput = view.findViewById<com.hbb20.CountryCodePicker>(R.id.ccp)
            var ip = ipInput.text.toString().toLowerCase()
            var ccp = ccpInput.selectedCountryNameCode
            GlobalScope.launch {
                var di = DNSInfo(InetAddress.getByName("["+ip+"]"), ccp, "User DNS")
                try {
                    var ping = ping(di.address, 53)
                    di.ping = ping
                } catch(e: Throwable){
                    di.ping = Int.MAX_VALUE
                }
                withContext(Dispatchers.Main) {
                    var selectAdapter = (findViewById<ListView>(R.id.peerList).adapter as SelectDNSInfoListAdapter)
                    selectAdapter.addItem(0, di)
                    selectAdapter.notifyDataSetChanged()
                    ad.dismiss()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.save, menu)
        val item = menu.findItem(R.id.saveItem) as MenuItem
        item.setActionView(R.layout.menu_save)
        val saveButton = item
            .actionView.findViewById<Button>(R.id.saveButton)
        saveButton.setOnClickListener {
            if(isLoading){
                return@setOnClickListener
            }
            val result = Intent(this, MainActivity::class.java)
            var adapter = findViewById<ListView>(R.id.dnsList).adapter as SelectDNSInfoListAdapter
            val selectedDNS = adapter.getSelectedDNS()
            result.putExtra(MainActivity.DNS_LIST, serializeDNSInfoSet2StringList(selectedDNS))
            setResult(Activity.RESULT_OK, result)
            finish()        }
        return true
    }
}