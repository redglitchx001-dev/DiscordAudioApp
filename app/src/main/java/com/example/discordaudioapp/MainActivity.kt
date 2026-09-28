package com.example.discordaudioapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.*
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val PICK_AUDIO_REQUEST = 1
    private val PERMISSION_REQUEST_CODE = 100

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        val fabSettings = findViewById<FloatingActionButton>(R.id.fabSettings)
        val bottomSheet = findViewById<android.widget.LinearLayout>(R.id.bottomSheet)
        val sheetBehavior = BottomSheetBehavior.from(bottomSheet)
        
        sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        fabSettings.setOnClickListener {
            if (sheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                sheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            } else {
                sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        val volumeBar = findViewById<SeekBar>(R.id.volumeBar)
        val echoBar = findViewById<SeekBar>(R.id.echoBar)
        val btnLoadMp3 = findViewById<Button>(R.id.btnLoadMp3)
        val btnStopMp3 = findViewById<Button>(R.id.btnStopMp3)

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

        btnStopMp3.setOnClickListener {
            webView.evaluateJavascript("if(window.stopMp3Stream) window.stopMp3Stream();", null)
            Toast.makeText(this, "Oprit MP3", Toast.LENGTH_SHORT).show()
        }

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            setupWebView()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            setupWebView()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            // Premium: Desktop Mode
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    request.grant(request.resources)
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAudioHook(view)
                // Also trigger it again slightly later just in case Discord reloads its modules
                view?.postDelayed({ injectAudioHook(view) }, 2000)
            }
            
            // Inject early as well
            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                if (url?.contains("discord.com") == true) {
                    injectAudioHook(view)
                }
            }
        }

        webView.loadUrl("https://discord.com/app")
    }

    private fun injectAudioHook(view: WebView?) {
        val js = """
            try {
                if (!window.audioHookInjected && navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
                    window.audioHookInjected = true;
                    console.log("Injecting Premium Audio Hook!");
                    
                    const origGetUserMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
                    
                    window.audioContext = new (window.AudioContext || window.webkitAudioContext)();
                    window.masterGain = window.audioContext.createGain();
                    window.echoDelay = window.audioContext.createDelay();
                    window.echoFeedback = window.audioContext.createGain();
                    window.destNode = window.audioContext.createMediaStreamDestination();
                    
                    window.echoDelay.delayTime.value = 0.3;
                    window.echoFeedback.gain.value = 0.0;
                    window.masterGain.gain.value = 1.0;
                    
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
                        if(window.masterGain) window.masterGain.gain.value = val;
                    };

                    window.setAudioEcho = function(val) {
                        if(window.echoFeedback) window.echoFeedback.gain.value = val;
                    };
                    
                    window.stopMp3Stream = function() {
                        if(window.mp3Source) {
                            window.mp3Source.stop();
                            window.mp3Source = null;
                        }
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
                }
            } catch(err) {
                console.error("Hook error:", err);
            }
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
                    Toast.makeText(this, "MP3 Incarcat cu succes! Premium!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Eroare MP3", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
