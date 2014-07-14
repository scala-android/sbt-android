import android.Keys._

TaskKey[Unit]("check-dex") <<= ( builder in Android
                               , projectLayout in Android
                               ) map {
  (p,layout) =>
  val tools = p.getTargetInfo.getBuildTools.getLocation
  val dexdump = tools / "dexdump"
  val lines = Seq(
    dexdump.getAbsolutePath,
    (layout.bin / "classes.dex").getAbsolutePath).lines
  val hasAbstractSuite = lines exists { l =>
    l.trim.startsWith("Class descriptor") && l.trim.endsWith("AbstractSuite$class;'")}
  if (!hasAbstractSuite)
    error("AbstractSuite not found")
}

TaskKey[Unit]("check-cache") <<= (proguardInputs in Android) map { c =>
  import java.io._
  import java.util.zip._
  val in = new ZipInputStream(new FileInputStream(c.proguardCache.get))
  val suite = Stream.continually(in.getNextEntry) takeWhile (
    _ != null) exists { e => e.getName.endsWith("AbstractSuite$class.class") }
  if (!suite) error("AbstractSuite not found in cache")
  in.close()
}
