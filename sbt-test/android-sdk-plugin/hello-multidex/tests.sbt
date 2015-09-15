TaskKey[Unit]("check-list") := {
  val main = (dexMainClassesConfig in Android).value
  println(IO.read(main))
}
