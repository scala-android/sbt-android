package com.example.proguardcache

import android.app.Activity
import android.os.Bundle

class MainActivity extends Activity with TypedFindView {
    override def onCreate(b: Bundle) {
        super.onCreate(b)
        setContentView(R.layout.main)
        findView(TR.text).setText("Hello again, world!")
        findView(TR.text)
    }
}
