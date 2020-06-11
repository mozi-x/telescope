package com.github.shadowsocks.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.util.Base64
import com.github.shadowsocks.Core
import com.github.shadowsocks.utils.printLog
import com.github.shadowsocks.utils.useCancellable
import kotlinx.coroutines.withTimeout
import SpeedUpVPN.VpnEncrypt
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.sql.SQLException
import java.util.*
import kotlin.collections.ArrayList

object SSRSubManager {

    @Throws(SQLException::class)
    fun createSSRSub(ssrSub: SSRSub): SSRSub {
        ssrSub.id = 0
        ssrSub.id = PrivateDatabase.ssrSubDao.create(ssrSub)
        return ssrSub
    }

    @Throws(SQLException::class)
    fun updateSSRSub(ssrSub: SSRSub) = check(PrivateDatabase.ssrSubDao.update(ssrSub) == 1)

    @Throws(IOException::class)
    fun getSSRSub(id: Long): SSRSub? = try {
        PrivateDatabase.ssrSubDao[id]
    } catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        null
    }

    @Throws(SQLException::class)
    fun delSSRSub(id: Long) {
        check(PrivateDatabase.ssrSubDao.delete(id) == 1)
    }

    @Throws(IOException::class)
    fun getAllSSRSub(): List<SSRSub> = try {
        PrivateDatabase.ssrSubDao.getAll()
    } catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        emptyList()
    }

    private suspend fun getResponse(url: String, mode: String = "") =
            withTimeout(10000L) {
                val connection = URL(url).openConnection() as HttpURLConnection
                val body = connection.useCancellable { inputStream.bufferedReader().use { it.readText() } }
                if(mode == "")
                    String(Base64.decode(body, Base64.DEFAULT))
                else
                    VpnEncrypt.aesDecrypt(body)
            }

    fun deletProfiles(ssrSub: SSRSub) {
        val profiles = ProfileManager.getAllProfilesByGroup(ssrSub.url_group)
        ProfileManager.deletProfiles(profiles)
    }

    suspend fun update(ssrSub: SSRSub, b: String = "") {
        var ssrSubMode=""
        if(ssrSub.isBuiltin())ssrSubMode="aes"
        val response = if (b.isEmpty()) getResponse(ssrSub.url,ssrSubMode) else b
        var profiles = Profile.findAllSSRUrls(response, Core.currentProfile?.first).toList()
        when {
            profiles.isEmpty() -> {
                deletProfiles(ssrSub)
                ssrSub.status = SSRSub.EMPTY
                updateSSRSub(ssrSub)
                return
            }
            ssrSub.url_group != profiles[0].url_group -> {
                ssrSub.status = SSRSub.NAME_CHANGED
                updateSSRSub(ssrSub)
                return
            }
            else -> {
                ssrSub.status = SSRSub.NORMAL
                updateSSRSub(ssrSub)
            }
        }

        val count = profiles.count()
        var limit = -1
        if (response.indexOf("MAX=") == 0) {
            limit = response.split("\n")[0].split("MAX=")[1]
                    .replace("\\D+".toRegex(), "").toInt()
        }

        var limitProfiles:ArrayList<Profile> = arrayListOf()

        if (limit != -1 && limit < count) {
            try {
                val uqid=VpnEncrypt.getUniqueID(Core.appName)
                Log.e("uqid",uqid.toString())
                val startPosition=uqid % count
                for (k in 0 until limit) {
                    var thePosition=startPosition+k
                    if(thePosition>=count) thePosition -= count
                    limitProfiles.add(profiles[thePosition])
                }
            }
            catch (ex: Exception) {
                limitProfiles.clear()
                limitProfiles.addAll(profiles.shuffled().take(limit))
            }
            if (limitProfiles.isEmpty())limitProfiles.addAll(profiles.shuffled().take(limit))
        }
        else
            limitProfiles.addAll(profiles)

        if (limitProfiles.isNotEmpty()) {
            deletProfiles(ssrSub)
            ProfileManager.createProfilesFromSub(limitProfiles, ssrSub.url_group)
        }
    }

    fun update(ssrSub: SSRSub, profiles : List<Profile>,limit:Int=-1) {
        when {
            profiles.isEmpty() -> {
                deletProfiles(ssrSub)
                ssrSub.status = SSRSub.EMPTY
                updateSSRSub(ssrSub)
                return
            }
            ssrSub.url_group != profiles[0].url_group -> {
                ssrSub.status = SSRSub.NAME_CHANGED
                updateSSRSub(ssrSub)
                return
            }
            else -> {
                ssrSub.status = SSRSub.NORMAL
                updateSSRSub(ssrSub)
            }
        }

        val count = profiles.count()

        var limitProfiles:ArrayList<Profile> = arrayListOf()

        if (limit != -1 && limit < count) {
            try {
                val uqid=VpnEncrypt.getUniqueID(Core.appName)
                Log.e("uqid",uqid.toString())
                val startPosition=uqid % count
                for (k in 0 until limit) {
                    var thePosition=startPosition+k
                    if(thePosition>=count) thePosition -= count
                    limitProfiles.add(profiles[thePosition])
                }
            }
            catch (ex: Exception) {
                limitProfiles.clear()
                limitProfiles.addAll(profiles.shuffled().take(limit))
            }
            if (limitProfiles.isEmpty())limitProfiles.addAll(profiles.shuffled().take(limit))
        }
        else
            limitProfiles.addAll(profiles)

        if (limitProfiles.isNotEmpty()) {
            //deletProfiles(ssrSub)
            ProfileManager.createProfilesFromSub(limitProfiles, ssrSub.url_group)
        }
    }

    suspend fun updateAll() {
        val ssrsubs = getAllSSRSub()
        ssrsubs.forEach {
            try {
                update(it)
            } catch (e: Exception) {
                it.status = SSRSub.NETWORK_ERROR
                updateSSRSub(it)
                printLog(e)
            }
        }
    }

    suspend fun create(url: String, mode: String = ""): SSRSub? {
        Log.println(Log.ERROR,"------","start create ssrsub...")
        if (url.isEmpty()) return null
        try {
            val response = getResponse(url,mode)
            val profiles=Profile.findAllUrls(response, Core.currentProfile?.first).toList()
            if (profiles.isNullOrEmpty() || profiles[0].url_group.isEmpty()) return null
            val new = SSRSub(url = url, url_group = profiles[0].url_group)
            getAllSSRSub().forEach {
                if (it.url_group == new.url_group) {
                    update(it, profiles)//android.view.ViewRootImpl$CalledFromWrongThreadException: Only the original thread that created a view hierarchy can touch its views.
                    Log.println(Log.ERROR,"------","ssrsub existed, update.")
                    return it
                }
            }
            createSSRSub(new)
            update(new, profiles)
            Log.println(Log.ERROR,"------","success create ssrsub.")
            return new
        } catch (e: Exception) {
            Log.e("------","failed create ssrsub",e)
            return null
        }
    }
    suspend fun createSSSub(url: String, group: String?=null): SSRSub? {
        if (url.isEmpty()) return null
        try {
            val response = if (VpnEncrypt.vpnGroupName==group)
                getResponse(url,"aes")
            else
                getResponse(url)

            var limit = -1
            if (response.indexOf("MAX=") == 0) {
                limit = response.split("\n")[0].split("MAX=")[1]
                        .replace("\\D+".toRegex(), "").toInt()
            }
            var profiles = Profile.findAllUrls(response, Core.currentProfile?.first)
            if (profiles.isNullOrEmpty()) return null

            val userCountry= Locale.getDefault().country
            Log.e("userCountry",userCountry)
            if (("TM" == userCountry || "UZ" == userCountry || "RU" == userCountry) && VpnEncrypt.vpnGroupName==group){ //builtin server for Russia and Turkmenistan,Uzbekistan
                val iterator = profiles.iterator()
                for (it in iterator){
                    if ( it.name!=null && ( it.name!!.endsWith("r")  || it.name!!.endsWith("rc") ) )
                        iterator.remove()
                }
            }
            val subGroup = group ?: profiles.first().url_group
            val new = SSRSub(url = url, url_group = subGroup)
            profiles.forEach { it.url_group = subGroup }
            getAllSSRSub().forEach {
                if (it.url_group == new.url_group) {
                    update(it, profiles.toList(),limit)//android.view.ViewRootImpl$CalledFromWrongThreadException: Only the original thread that created a view hierarchy can touch its views.
                    Log.println(Log.ERROR,"------","ssrsub existed, update.")
                    return it
                }
            }
            createSSRSub(new)
            update(new, profiles.toList(),limit)
            Log.println(Log.ERROR,"------","success create ssrsub.")
            return new
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("------","failed create ssrsub",e)
            return null
        }
    }
}