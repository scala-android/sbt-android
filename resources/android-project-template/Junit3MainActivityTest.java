package %s;

import android.support.test.rule.ActivityTestRule;

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
public class Junit3MainActivityTest extends ActivityTestRule<MainActivity> {

    public Junit3MainActivityTest() {
        super(MainActivity.class);
    }


    public void testSuccessful() {
    }
}
