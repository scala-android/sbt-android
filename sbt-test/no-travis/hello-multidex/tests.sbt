TaskKey[Unit]("check-list") := {
  val mainClasses = IO.readLines((dexMainClassesConfig in Android).value).toList
  if (mainClasses.isEmpty) android.Plugin.fail("No good")
}
