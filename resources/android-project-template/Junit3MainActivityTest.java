package %s;

import android.test.ActivityInstrumentationTestCase2;

/**
 * This is a simple framework for a test of an Application.  See
 * {@link android.test.ApplicationTestCase ApplicationTestCase} for more information on
 * how to write and extend Application tests.
 * <p/>
 * To run this test, you can type:
 * adb shell am instrument -w \
 * -e class com.example.MainActivityTest \
 * com.example.tests/android.test.InstrumentationTestRunner
 */
public class Junit3MainActivityTest extends ActivityInstrumentationTestCase2<MainActivity> {

    public Junit3MainActivityTest() {
        super("com.example", MainActivity.class);
    }


    public void testSuccessful() {
    }
}
