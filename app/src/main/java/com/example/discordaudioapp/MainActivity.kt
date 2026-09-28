package com.example.discordaudioapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.*
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val PICK_AUDIO_REQUEST = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissions()

        webView = findViewById(R.id.webView)
        val volumeBar = findViewById<SeekBar>(R.id.volumeBar)
        val echoBar = findViewById<SeekBar>(R.id.echoBar)
        val btnLoadMp3 = findViewById<Button>(R.id.btnLoadMp3)

        setupWebView()

        volumeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val volume = progress / 100f
                webView.evaluateJavascript("if(window.setAudioVolume) window.setAudioVolume($volume);", null)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        echoBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val echo = progress / 100f
                webView.evaluateJavascript("if(window.setAudioEcho) window.setAudioEcho($echo);", null)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnLoadMp3.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "audio/*"
            startActivityForResult(intent, PICK_AUDIO_REQUEST)
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAudioHook(view)
            }
        }

        webView.loadUrl("https://discord.com/app")
    }

    private fun injectAudioHook(view: WebView?) {
        val js = """
            (function() {
                if (window.audioHookInjected) return;
                window.audioHookInjected = true;
                
                const origGetUserMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
                
                window.audioContext = new (window.AudioContext || window.webkitAudioContext)();
                window.masterGain = window.audioContext.createGain();
                window.echoDelay = window.audioContext.createDelay();
                window.echoFeedback = window.audioContext.createGain();
                window.destNode = window.audioContext.createMediaStreamDestination();
                
                window.echoDelay.delayTime.value = 0.3;
                window.echoFeedback.gain.value = 0.0; // start with 0 echo
                window.masterGain.gain.value = 1.0; // start with normal volume
                
                // Echo routing
                window.masterGain.connect(window.echoDelay);
                window.echoDelay.connect(window.echoFeedback);
                window.echoFeedback.connect(window.echoDelay);
                window.echoDelay.connect(window.destNode);
                window.masterGain.connect(window.destNode);

                navigator.mediaDevices.getUserMedia = async function(constraints) {
                    try {
                        const stream = await origGetUserMedia(constraints);
                        if (constraints.audio) {
                            const source = window.audioContext.createMediaStreamSource(stream);
                            source.connect(window.masterGain);
                            
                            // Return the processed stream instead of original
                            // We need to keep video track if present
                            const tracks = window.destNode.stream.getAudioTracks();
                            if(stream.getVideoTracks().length > 0) {
                                stream.getVideoTracks().forEach(track => window.destNode.stream.addTrack(track));
                            }
                            return window.destNode.stream;
                        }
                        return stream;
                    } catch(e) {
                        return Promise.reject(e);
                    }
                };

                window.setAudioVolume = function(val) {
                    window.masterGain.gain.value = val;
                };

                window.setAudioEcho = function(val) {
                    window.echoFeedback.gain.value = val;
                };

                window.playMp3Stream = function(base64data) {
                    fetch('data:audio/mp3;base64,' + base64data)
                    .then(res => res.arrayBuffer())
                    .then(buf => window.audioContext.decodeAudioData(buf))
                    .then(audioBuffer => {
                        if(window.mp3Source) {
                            window.mp3Source.stop();
                        }
                        window.mp3Source = window.audioContext.createBufferSource();
                        window.mp3Source.buffer = audioBuffer;
                        window.mp3Source.loop = true;
                        window.mp3Source.connect(window.masterGain);
                        window.mp3Source.start();
                    });
                };
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_AUDIO_REQUEST && resultCode == RESULT_OK && data != null) {
            val uri: Uri? = data.data
            uri?.let {
                try {
                    val inputStream: InputStream? = contentResolver.openInputStream(it)
                    val bytes = inputStream?.readBytes()
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    webView.evaluateJavascript("if(window.playMp3Stream) window.playMp3Stream('$base64');", null)
                } catch (e: Exception) {
                    Toast.makeText(this, "Failed to load audio", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 0)
        }
    }
}
