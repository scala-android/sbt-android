import android.Keys._

TaskKey[Unit]("check-test-dex") <<= ( sdkParser in Android
                               , projectLayout in Android
                               ) map {
  (p,layout) =>
  val tools = p.getBuildTools.getLocation
  val dexdump = tools / "dexdump"
  val lines = Seq(
    dexdump.getAbsolutePath,
    (layout.bin / "classes-test.dex").getAbsolutePath).lines
  val hasJunit = lines exists { l =>
    l.trim.startsWith("Class descriptor") && l.trim.endsWith("junit/Assert;'")}
  if (!hasJunit)
    error("JUnit not found\n" + (lines mkString "\n"))
}
