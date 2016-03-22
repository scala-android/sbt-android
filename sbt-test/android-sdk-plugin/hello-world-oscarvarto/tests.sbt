import android.Keys._
import java.util.zip._
import java.io._
import android.BuildOutput._

TaskKey[Unit]("check-dex") <<= ( TaskKey[com.android.builder.core.AndroidBuilder]("android-builder") in Android
                               , projectLayout in Android
                               , outputLayout in Android
                               ) map {
  (p,layout, o) =>
  implicit val output = o
  val tools = p.getTargetInfo.getBuildTools.getLocation
  val dexdump = tools / "dexdump"
  val lines = Seq(
    dexdump.getAbsolutePath,
    (layout.dex / "classes.dex").getAbsolutePath).lines
  val hasMainActivity = lines exists { l =>
    l.trim.startsWith("Class descriptor") && l.trim.endsWith("MainActivity;'")}
  if (!hasMainActivity)
    error("MainActivity not found\n" + (lines mkString "\n"))
}

TaskKey[Unit]("check-tr") <<= ( projectLayout in Android ) map { layout =>
  val tr = layout.gen / "com" / "example" / "app" / "TR.scala"
  val lines = IO.readLines(tr)
  val expected =
    "final val hello = TypedLayout[android.widget.FrameLayout](R.layout.hello)"
  val hasTextView = lines exists (_.trim == expected)
  if (!hasTextView)
    error("Could not find TR.test_textview\n" + (lines mkString "\n"))
}

TaskKey[Unit]("check-resource") <<= ( sbt.Keys.`package` in Android ) map { apk =>
  val zip = new ZipInputStream(new FileInputStream(apk))
  val names = Stream.continually(zip.getNextEntry()).takeWhile(_ != null).map {
    _.getName
  }
  val exists = names exists (_.endsWith("test.conf"))
  zip.close()
  if (!exists) {
    error("Could not find test.conf\n" + (names mkString "\n"))
  }
}
