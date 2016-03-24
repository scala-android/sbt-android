package com.example

import android.app.Activity
import android.os.Bundle
import TypedResource._

class MainActivity extends Activity with TypedFindView {
    lazy val textview = findView(TR.text)
    implicit val context = this

    /** Called when the activity is first created. */
    override def onCreate(savedInstanceState: Bundle): Unit = {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        textview.setText("Hello world, from " + TR.string.app_name.value)
    }
}
