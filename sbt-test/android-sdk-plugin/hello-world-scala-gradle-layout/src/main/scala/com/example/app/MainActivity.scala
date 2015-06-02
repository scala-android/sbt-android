package com.example.app

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

import TypedResource._

class MainActivity extends Activity with TypedViewHolder {
    override def onCreate(b: Bundle) {
        super.onCreate(b)
        setContentView(R.layout.hello)
        val layout: FrameLayout = getLayoutInflater.inflate(TR.layout.hello, null)
        findView(TR.test_textview).setText("Hello again, world!")
    }
}
