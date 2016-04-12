import android.Keys._
import android.BuildOutput._

TaskKey[Unit]("check-test-dex") <<= ( TaskKey[Logger => com.android.builder.core.AndroidBuilder]("android-builder") in Android
                               , projectLayout in Android
                               , outputLayout in Android
                               , streams
                               ) map {
  (p,layout, o, s) =>
  implicit val output = o
  val tools = p(s.log).getTargetInfo.getBuildTools.getLocation
  val dexdump = tools / "dexdump"
  val lines = Seq(
    dexdump.getAbsolutePath,
    (layout.dex / "classes.dex").getAbsolutePath).lines
  val hasJunit = lines exists { l =>
    l.trim.startsWith("Class descriptor") && l.trim.endsWith("junit/Assert;'")}
  if (!hasJunit)
    error("JUnit not found\n" + (lines mkString "\n"))
}
