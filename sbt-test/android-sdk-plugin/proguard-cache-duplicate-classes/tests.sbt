import android.Keys._

TaskKey[Unit]("check-dex") <<= ( TaskKey[com.android.builder.core.AndroidBuilder]("builder") in Android
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

TaskKey[Unit]("check-deps") := {
  val deps = (dependencyClasspath in Android).value
  val deps2 = deps filterNot { f =>
    (proguardCache in Android).value exists (_.matches(f, state.value)) }
  val log = streams.value.log
  deps foreach { d =>
    val m = d.get(moduleID.key)
    log.info(s"BEFORE FILTER: $m => ${d.data.getCanonicalPath}")
  }
  deps2 foreach { d =>
    val m = d.get(moduleID.key)
    log.info(s"AFTER FILTER: $m => ${d.data.getCanonicalPath}")
  }
}

TaskKey[Unit]("check-cache") <<= (TaskKey[ProguardInputs]("proguard-inputs") in Android) map { c =>
  import java.io._
  import java.util.zip._
  val in = new ZipInputStream(new FileInputStream(c.proguardCache.get))
  val suite = Stream.continually(in.getNextEntry) takeWhile (
    _ != null) exists { e => e.getName.endsWith("AbstractSuite$class.class") }
  if (!suite) error("AbstractSuite not found in cache")
  in.close()
}
