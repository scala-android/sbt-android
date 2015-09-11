import android.Keys._
import android.BuildOutput._

TaskKey[Unit]("check-dex") <<= ( TaskKey[com.android.builder.core.AndroidBuilder]("android-builder") in Android
                               , projectLayout in Android
                               ) map {
  (p,layout) =>
  val tools = p.getTargetInfo.getBuildTools.getLocation
  val dexdump = tools / "dexdump"
  val lines = Seq(
    dexdump.getAbsolutePath,
    (layout.dex / "classes.dex").getAbsolutePath).lines
  val hasViewPager = lines exists { l =>
    l.trim.startsWith("Class descriptor") && l.trim.endsWith("ViewPager;'")}
  if (!hasViewPager)
    error("ViewPager not found")
}

TaskKey[Unit]("check-cache") <<= (TaskKey[ProguardInputs]("proguard-inputs") in Android) map { c =>
  import java.io._
  import java.util.zip._
  val in = new ZipInputStream(new FileInputStream(c.proguardCache.get))
  val pager = Stream.continually(in.getNextEntry) takeWhile (
    _ != null) exists { e => e.getName.endsWith("ViewPager.class") }
  if (!pager) error("ViewPager not found in cache")
  in.close()
}
