package com.hanhuy.android.robolectric.sample;

import android.test.ActivityInstrumentationTestCase2;

/**
 * This is a simple framework for a test of an Application.  See
 * {@link android.test.ApplicationTestCase ApplicationTestCase} for more information on
 * how to write and extend Application tests.
 * <p/>
 * To run this test, you can type:
 * adb shell am instrument -w \
 * -e class com.hanhuy.android.robolectric.sample.MainActivityTest \
 * com.hanhuy.android.robolectric.sample.tests/android.test.InstrumentationTestRunner
 */
public class MainActivityTest extends ActivityInstrumentationTestCase2<MainActivity> {

    public MainActivityTest() {
        super("com.hanhuy.android.robolectric.sample", MainActivity.class);
    }

}
