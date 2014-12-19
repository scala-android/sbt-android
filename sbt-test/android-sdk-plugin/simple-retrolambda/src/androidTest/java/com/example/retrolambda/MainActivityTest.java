package com.example.retrolambda;

import android.test.ActivityInstrumentationTestCase2;
import com.google.common.collect.*;
import com.google.common.primitives.*;
import java.util.*;

/**
 * This is a simple framework for a test of an Application.  See
 * {@link android.test.ApplicationTestCase ApplicationTestCase} for more information on
 * how to write and extend Application tests.
 * <p/>
 * To run this test, you can type:
 * adb shell am instrument -w \
 * -e class com.example.retrolambda.MainActivityTest \
 * com.example.retrolambda.tests/android.test.InstrumentationTestRunner
 */
public class MainActivityTest extends ActivityInstrumentationTestCase2<MainActivity> {

    public MainActivityTest() {
        super("com.example.retrolambda", MainActivity.class);
    }

    public void testSimpleLambda() {
        List<Character> chars = Chars.asList("hello".toCharArray());
        assertEquals(Arrays.asList(104, 101, 108, 108, 111),
                MainActivity.toIntList(chars));
    }
}
