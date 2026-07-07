package com.example.documenttransfer

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okio.BufferedSink
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.net.NetworkInterface
import java.net.Inet4Address
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

interface HealthApi {
    @GET("ping")
    fun ping(): Call<ResponseBody>
}

interface UploadApi {
    @Multipart
    @POST("upload")
    fun upload(
        @Part filePart: MultipartBody.Part,
        @Header("x-client-mode") clientMode: String
    ): Call<ResponseBody>
}

class NetworkManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)

    // Build OkHttpClient with short timeouts for connection detection
    private val pingClient = OkHttpClient.Builder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1200, TimeUnit.MILLISECONDS)
        .writeTimeout(1200, TimeUnit.MILLISECONDS)
        .build()

    // Build standard OkHttpClient for file uploads (longer timeouts for large images)
    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Tries to find the active server automatically by pinging:
     * 1. http://127.0.0.1:8000 (USB Mode via ADB Reverse)
     * 2. http://<configuredIp>:8000 (Wi-Fi Mode)
     *
     * Invokes onResult callback on the UI thread with:
     * - pair of (baseUrl, modeName) if found
     * - null if both checks fail
     */
    fun detectActiveServer(
        configuredIp: String,
        onResult: (activeUrl: String?, mode: String?) -> Unit
    ) {
        scope.launch {
            // 1. Try configured IP via Wi-Fi if IP is not empty
            val cleanIp = configuredIp.trim()
            if (cleanIp.isNotEmpty()) {
                val wifiUrl = when {
                    cleanIp.startsWith("http://") || cleanIp.startsWith("https://") -> {
                        if (cleanIp.endsWith("/")) cleanIp else "$cleanIp/"
                    }
                    cleanIp.contains(":") -> {
                        "http://$cleanIp/"
                    }
                    else -> {
                        "http://$cleanIp:8000/"
                    }
                }

                val wifiAvailable = async { pingServer(wifiUrl) }
                if (wifiAvailable.await()) {
                    withContext(Dispatchers.Main) {
                        onResult(wifiUrl, "wifi")
                    }
                    return@launch
                }
            }

            // 3. Fallback: Try a general local emulator target in case we are in an emulator
            val emulatorUrl = "http://10.0.2.2:8000/"
            val emulatorAvailable = async { pingServer(emulatorUrl) }
            if (emulatorAvailable.await()) {
                withContext(Dispatchers.Main) {
                    onResult(emulatorUrl, "emulator")
                }
                return@launch
            }

            // 4. Fallback: Auto Scan Subnet!
            // If USB and configured IP both failed, scan the local Wi-Fi subnet!
            val localIp = getLocalIpAddress()
            if (localIp != null) {
                val lastDot = localIp.lastIndexOf('.')
                if (lastDot > 0) {
                    val prefix = localIp.substring(0, lastDot + 1)
                    val discoveredUrl = scanSubnet(prefix)
                    if (discoveredUrl != null) {
                        withContext(Dispatchers.Main) {
                            onResult(discoveredUrl, "wifi")
                        }
                        return@launch
                    }
                }
            }

            // All checks failed
            withContext(Dispatchers.Main) {
                onResult(null, null)
            }
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        if (ip != null && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172."))) {
                            return ip
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return null
    }

    private suspend fun scanSubnet(prefix: String): String? = withContext(Dispatchers.IO) {
        val channel = Channel<String?>(1)
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        
        for (i in 1..254) {
            val job = launch {
                val testUrl = "http://$prefix$i:8000/"
                if (pingServer(testUrl)) {
                    channel.trySend(testUrl)
                }
            }
            jobs.add(job)
        }
        
        val timerJob = launch {
            delay(2200) // 2.2 seconds timeout
            channel.trySend(null)
        }
        
        val resultUrl = channel.receive()
        
        // Cleanup to prevent leaking coroutines
        jobs.forEach { it.cancel() }
        timerJob.cancel()
        
        resultUrl
    }

    private fun pingServer(baseUrl: String): Boolean {
        return try {
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(pingClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            
            val api = retrofit.create(HealthApi::class.java)
            val response = api.ping().execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Uploads the file with a custom request body that tracks upload percentage.
     */
    fun uploadImage(
        file: File,
        serverUrl: String,
        clientMode: String,
        onProgress: (Int) -> Unit,
        onComplete: (success: Boolean, message: String?) -> Unit
    ) {
        scope.launch {
            try {
                val retrofit = Retrofit.Builder()
                    .baseUrl(serverUrl)
                    .client(uploadClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()

                val api = retrofit.create(UploadApi::class.java)
                
                // Create custom request body with progress tracking callback
                val requestBody = ProgressRequestBody(file, "image/jpeg".toMediaTypeOrNull()) { progress ->
                    mainHandler.post {
                        onProgress(progress)
                    }
                }

                val filePart = MultipartBody.Part.createFormData("image", file.name, requestBody)
                val call = api.upload(filePart, clientMode)
                
                val response = call.execute()
                
                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        val bodyString = response.body()?.string()
                        val filename = try {
                            JSONObject(bodyString ?: "").optString("filename", file.name)
                        } catch (e: Exception) {
                            file.name
                        }
                        onComplete(true, filename)
                    } else {
                        onComplete(false, "Server error code: ${response.code()}")
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    onComplete(false, "Network error: ${e.localizedMessage}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onComplete(false, "Error: ${e.localizedMessage}")
                }
            }
        }
    }

    /**
     * Custom OkHttp RequestBody subclass that intercepts stream writes to count bytes sent.
     */
    private class ProgressRequestBody(
        private val file: File,
        private val contentType: okhttp3.MediaType?,
        private val progressListener: (progress: Int) -> Unit
    ) : RequestBody() {

        override fun contentType() = contentType

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val fileLength = file.length()
            val buffer = ByteArray(2048)
            val fileInputStream = FileInputStream(file)
            var totalBytesUploaded: Long = 0

            fileInputStream.use { fis ->
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    sink.write(buffer, 0, read)
                    totalBytesUploaded += read
                    
                    val progress = ((totalBytesUploaded * 100) / fileLength).toInt()
                    progressListener(progress)
                }
            }
        }
    }
}
