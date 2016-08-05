package sample;
import android.renderscript.RenderScript;
public class Rs {
    static {
        // don't actually care about running, just want to make sure the
        // source is there and will compile
        RenderScript rs = RenderScript.create(null);
        ScriptC_invert sc = new ScriptC_invert(rs);
    }
}
