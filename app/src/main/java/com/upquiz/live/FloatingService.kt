package com.upquiz.live

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.core.app.NotificationCompat
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.CompletionListener
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.Message
import org.json.JSONObject

class FloatingService : Service() {

    companion object {
        var isRunning = false
        const val ABLY_KEY     = "wBfoUA.YHn4eA:CcalxKVSdtFhXiGdj45eNfAiSVn8PDxSLLzW8djRsDY"
        const val CHANNEL_NAME = "upquiz-live"
        const val VOTE_CH_NAME = "upquiz-votes"
        const val NOTIF_ID     = 101
        const val NOTIF_CH     = "upquiz_service"
    }

    // ── Views ──
    private lateinit var wm: WindowManager
    private var floatRoot: View? = null
    private var bubbleRoot: View? = null

    // ── Ably ──
    private var ably: AblyRealtime? = null
    private var channel: Channel? = null
    private var voteChannel: Channel? = null

    // ── State ──
    private var playerName = "Student"
    private var currentQNum = 0
    private var hasVoted = false
    private var correctAns = -1
    private var score = Score()
    private val handler = Handler(Looper.getMainLooper())
    private var revealRunnable: Runnable? = null
    private var timerRunnable: Runnable? = null
    private var timeLeft = 15

    data class Score(var correct: Int = 0, var wrong: Int = 0,
                     var skipped: Int = 0, var streak: Int = 0, var total: Int = 0)

    // ─────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Quiz का इंतज़ार है..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        playerName = intent?.getStringExtra("player_name") ?: "Student"
        showBubble()
        connectAbly()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        removeAllViews()
        try { ably?.close() } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?) = null

    // ─────────────────────────────────────────
    // ABLY CONNECTION
    // ─────────────────────────────────────────
    private fun connectAbly() {
        try {
            val opts = ClientOptions(ABLY_KEY)
            opts.clientId = playerName
            ably = AblyRealtime(opts)

            channel = ably!!.channels.get(CHANNEL_NAME)
            voteChannel = ably!!.channels.get(VOTE_CH_NAME)

            // Enter presence so host sees viewer count
            try { voteChannel!!.presence.enter(playerName, null) } catch(e: Exception){}

            // Listen for new question
            channel!!.subscribe("question") { msg ->
                handler.post { onQuestionReceived(msg) }
            }

            // Listen for reveal
            channel!!.subscribe("reveal") { msg ->
                handler.post { onRevealReceived(msg) }
            }

            // Listen for end
            channel!!.subscribe("end") { _ ->
                handler.post { onQuizEnd() }
            }

            updateNotification("✅ Connected — Question का इंतज़ार")
        } catch (e: Exception) {
            updateNotification("❌ Connection Error — Restart करो")
        }
    }

    // ─────────────────────────────────────────
    // QUESTION RECEIVED → Show floating card
    // ─────────────────────────────────────────
    private fun onQuestionReceived(msg: Message) {
        val d = try { JSONObject(msg.data.toString()) } catch(e: Exception) { return }
        currentQNum = d.optInt("num", 0)
        hasVoted    = false
        correctAns  = -1
        timeLeft    = 15

        val qText  = d.optString("q", "")
        val hiText = d.optString("hi", "")
        val ch     = d.optInt("ch", 0)
        val chName = d.optString("chName", "Chapter $ch")
        val qNum   = d.optInt("num", 0)
        val total  = d.optInt("total", 0)
        val opts   = mutableListOf<String>()
        val optsArr = d.optJSONArray("opts")
        if (optsArr != null) for (i in 0 until optsArr.length()) {
            val v = optsArr.optString(i, "")
            if (v.isNotEmpty()) opts.add(v)
        }

        removeBubble()
        showQuestionCard(qText, hiText, ch, chName, qNum, total, opts)
        vibrateDevice()
        updateNotification("Q$qNum: $qText".take(60))
    }

    private fun showQuestionCard(
        qText: String, hiText: String,
        ch: Int, chName: String,
        qNum: Int, total: Int,
        opts: List<String>
    ) {
        removeCard()
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.floating_card, null)

        // Fill data
        view.findViewById<TextView>(R.id.tvChapter).text  = "📚 Ch.$ch — $chName"
        view.findViewById<TextView>(R.id.tvQNum).text     = "Q$qNum of $total  •  Score: ${score.correct}✓"
        view.findViewById<TextView>(R.id.tvQuestion).text = qText
        val tvHi = view.findViewById<TextView>(R.id.tvHindi)
        if (hiText.isNotEmpty()) { tvHi.text = hiText; tvHi.visibility = View.VISIBLE }
        else { tvHi.visibility = View.GONE }

        // Timer text
        val tvTimer = view.findViewById<TextView>(R.id.tvTimer)
        tvTimer.text = "⏱ 15"

        // Build option buttons dynamically
        val optContainer = view.findViewById<LinearLayout>(R.id.optContainer)
        val labels = listOf("A", "B", "C", "D", "E")
        opts.forEachIndexed { i, opt ->
            val btn = Button(this)
            btn.text = "${labels[i]}. $opt"
            btn.tag  = i
            btn.setBackgroundResource(R.drawable.opt_bg)
            btn.setTextColor(Color.WHITE)
            btn.textSize = 13f
            btn.setPadding(20, 16, 20, 16)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 0, 0, 10)
            btn.layoutParams = lp
            btn.setOnClickListener { castVote(i, opts, optContainer, labels) }
            optContainer.addView(btn)
        }

        // Close/skip button
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            cancelTimers()
            removeCard()
            showBubble()
        }

        // Add to window
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.y = 60

        floatRoot = view
        wm.addView(view, params)

        // Start countdown
        startCountdown(tvTimer, opts, optContainer, labels)
    }

    private fun startCountdown(
        tvTimer: TextView,
        opts: List<String>,
        container: LinearLayout,
        labels: List<String>
    ) {
        cancelTimers()
        timeLeft = 15
        timerRunnable = object : Runnable {
            override fun run() {
                if (timeLeft <= 0) {
                    // Time's up — skipped
                    if (!hasVoted) {
                        score.skipped++; score.streak = 0; score.total++
                        tvTimer.text = "⏰ समय खत्म!"
                        tvTimer.setTextColor(Color.parseColor("#FF4F6B"))
                    }
                    return
                }
                tvTimer.text = "⏱ $timeLeft"
                tvTimer.setTextColor(when {
                    timeLeft <= 5  -> Color.parseColor("#FF4F6B")
                    timeLeft <= 10 -> Color.parseColor("#FF8C42")
                    else           -> Color.parseColor("#A78BFA")
                })
                timeLeft--
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timerRunnable!!)
    }

    private fun castVote(
        idx: Int,
        opts: List<String>,
        container: LinearLayout,
        labels: List<String>
    ) {
        if (hasVoted) return
        hasVoted = true
        cancelTimers()

        // Publish vote
        try {
            voteChannel?.publish("vote", JSONObject().apply {
                put("name", playerName)
                put("ans", idx)
                put("qNum", currentQNum)
            }.toString(), null)
        } catch (e: Exception) {}

        // Visual feedback — dim others, highlight selected
        for (i in 0 until container.childCount) {
            val btn = container.getChildAt(i) as? Button ?: continue
            if (btn.tag as? Int == idx) {
                btn.setBackgroundColor(Color.parseColor("#7C3AED"))
            } else {
                btn.alpha = 0.4f
            }
        }

        // Update timer text
        floatRoot?.findViewById<TextView>(R.id.tvTimer)?.text = "📨 Vote भेजा!"
    }

    // ─────────────────────────────────────────
    // REVEAL RECEIVED
    // ─────────────────────────────────────────
    private fun onRevealReceived(msg: Message) {
        val d = try { JSONObject(msg.data.toString()) } catch(e: Exception) { return }
        correctAns = d.optInt("ans", -1)
        val exp    = d.optString("exp", "")

        val container = floatRoot?.findViewById<LinearLayout>(R.id.optContainer)
        val tvTimer   = floatRoot?.findViewById<TextView>(R.id.tvTimer)
        val tvExp     = floatRoot?.findViewById<TextView>(R.id.tvExp)

        // Score update
        if (!hasVoted) {
            score.skipped++; score.streak = 0; score.total++
        }

        // Color options
        if (container != null) {
            for (i in 0 until container.childCount) {
                val btn = container.getChildAt(i) as? Button ?: continue
                val idx = btn.tag as? Int ?: continue
                btn.alpha = 1f
                when {
                    idx == correctAns -> {
                        btn.setBackgroundColor(Color.parseColor("#059669"))
                        btn.text = "✓ " + btn.text
                    }
                    idx == (if (hasVoted) getMyVote() else -1) && idx != correctAns -> {
                        btn.setBackgroundColor(Color.parseColor("#DC2626"))
                        btn.text = "✗ " + btn.text
                    }
                    else -> btn.alpha = 0.35f
                }
            }
        }

        // Score update display
        if (hasVoted) {
            val myVote = getMyVote()
            if (myVote == correctAns) {
                score.correct++; score.streak++
                tvTimer?.text = "✅ सही! +1  (${score.correct}✓)"
                tvTimer?.setTextColor(Color.parseColor("#22D17A"))
            } else if (myVote != -1) {
                score.wrong++; score.streak = 0
                tvTimer?.text = "❌ गलत!  (${score.correct}✓)"
                tvTimer?.setTextColor(Color.parseColor("#FF4F6B"))
            }
        }

        // Explanation
        if (exp.isNotEmpty() && tvExp != null) {
            tvExp.text = "💡 $exp"
            tvExp.visibility = View.VISIBLE
        }

        // Auto close after 5s → show bubble
        revealRunnable = Runnable {
            removeCard()
            showBubble()
        }
        handler.postDelayed(revealRunnable!!, 5000)
        updateNotification("Score: ${score.correct}✓ ${score.wrong}✗ — अगला Question आ रहा है")
    }

    // Hack: track last voted index via view tag
    private var myVoteIdx = -1
    private fun getMyVote() = myVoteIdx

    private fun onQuizEnd() {
        removeCard()
        showBubble()
        updateNotification("Quiz खत्म! Score: ${score.correct}/${score.total}")
        Toast.makeText(this,
            "🏆 Quiz Complete!\n${score.correct}/${score.total} सही — ${
                if(score.total>0) "${score.correct*100/score.total}%" else "0%"
            } Accuracy",
            Toast.LENGTH_LONG).show()
    }

    // ─────────────────────────────────────────
    // BUBBLE (small icon when no question)
    // ─────────────────────────────────────────
    private fun showBubble() {
        removeBubble()
        val view = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)
        view.findViewById<TextView>(R.id.tvBubbleScore).text = "${score.correct}✓"
        view.setOnClickListener {
            // Tap bubble → open main app
            val i = Intent(this, MainActivity::class.java)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(i)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 16; params.y = 200

        // Make bubble draggable
        var ix = 0f; var iy = 0f
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { ix = e.rawX - params.x; iy = e.rawY - params.y; false }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (e.rawX - ix).toInt()
                    params.y = (e.rawY - iy).toInt()
                    wm.updateViewLayout(view, params); true
                }
                else -> false
            }
        }

        bubbleRoot = view
        wm.addView(view, params)
    }

    // ─────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────
    private fun removeCard()   { floatRoot?.let  { try { wm.removeView(it) } catch(e:Exception){} }; floatRoot  = null }
    private fun removeBubble() { bubbleRoot?.let { try { wm.removeView(it) } catch(e:Exception){} }; bubbleRoot = null }
    private fun removeAllViews() { removeCard(); removeBubble() }

    private fun cancelTimers() {
        timerRunnable?.let  { handler.removeCallbacks(it) }
        revealRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun vibrateDevice() {
        val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vib.vibrate(200)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CH, "UP Quiz Live", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Live Quiz Service"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent  = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, NOTIF_CH)
            .setContentTitle("UP Quiz Live 🎯")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
