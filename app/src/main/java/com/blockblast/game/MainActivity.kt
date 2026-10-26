package com.blockblast.game

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Bundle

class MainActivity : Activity() {
    private lateinit var gameView: GameView

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(GameView.BG_BOTTOM))
        window.statusBarColor = GameView.BG_TOP
        window.navigationBarColor = GameView.BG_BOTTOM
        gameView = GameView(this)
        setContentView(gameView)
    }

    override fun onPause() {
        super.onPause()
        gameView.saveState()
    }
}
