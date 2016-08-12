package %s;

import android.support.test.runner.AndroidJUnit4;
import android.support.test.rule.ActivityTestRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.Assert;

@RunWith(AndroidJUnit4.class)
public class Junit4MainActivityTest {

    @Rule
    public ActivityTestRule<MainActivity> activityRule =
            new ActivityTestRule<MainActivity>(MainActivity.class);

    @Test public void getActivity() {
        Assert.assertNotNull(activityRule.getActivity());
        Assert.assertTrue(activityRule.getActivity() instanceof MainActivity);
    }
}
