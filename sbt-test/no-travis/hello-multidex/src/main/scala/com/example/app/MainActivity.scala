package com.example.app

import android.app.Activity
import android.os.Bundle
import android.util.Log

class MainActivity extends Activity {
  override def onCreate(bundle: Bundle):Unit = {
    super.onCreate(bundle)
    setContentView(R.layout.hello)
    val foo = "foo"
    Log.v("MainActivity", s"interpolationTest=$foo")
  }
}