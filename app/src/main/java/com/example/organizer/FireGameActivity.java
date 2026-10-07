package com.example.organizer;

import android.app.Activity;
import android.os.Bundle;

// The game screen. All the real work happens inside FireGameView.
public class FireGameActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new FireGameView(this));
    }
}
