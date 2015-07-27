TaskKey[Unit]("check-list") := {
  val main = (dexMainFileClassesConfig in Android).value
  println(IO.read(main))
}
