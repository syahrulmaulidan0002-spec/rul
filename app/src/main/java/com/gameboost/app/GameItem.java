package com.gameboost.app;

import android.graphics.drawable.Drawable;

/** Satu aplikasi yang bisa dipilih sebagai game. */
final class GameItem {
    final String pkg;
    final String label;
    final Drawable icon;
    final boolean isGame;

    GameItem(String pkg, String label, Drawable icon, boolean isGame) {
        this.pkg = pkg;
        this.label = label;
        this.icon = icon;
        this.isGame = isGame;
    }
}
