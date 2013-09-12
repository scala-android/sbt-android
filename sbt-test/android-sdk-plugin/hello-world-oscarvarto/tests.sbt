import android.Keys._

TaskKey[Unit]("check-dex") <<= ( sdkParser in Android
                               , projectLayout in Android
                               ) map {
  (p,layout) =>
  val tools = p.getBuildTools.getLocation
  val dexdump = tools / "dexdump"
  val lines = Seq(
    dexdump.getAbsolutePath,
    (layout.bin / "classes.dex").getAbsolutePath).lines
  val hasMainActivity = lines exists { l =>
    l.trim.startsWith("Class descriptor") && l.trim.endsWith("MainActivity;'")}
  if (!hasMainActivity)
    error("MainActivity not found\n" + (lines mkString "\n"))
}

TaskKey[Unit]("check-tr") <<= ( projectLayout in Android ) map { layout =>
  val tr = layout.gen / "com" / "example" / "app" / "TR.scala"
  val lines = IO.readLines(tr)
  val expected =
    "val `hello` = TypedLayout[android.widget.FrameLayout](R.layout.`hello`)"
  val hasTextView = lines exists (_.trim == expected)
  if (!hasTextView)
    error("Could not find TR.test_textview\n" + (lines mkString "\n"))
}
