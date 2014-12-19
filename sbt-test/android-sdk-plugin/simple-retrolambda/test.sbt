TaskKey[Unit]("check-jar") <<= ( dexInputs in Android) map {
  case ((inc, list)) =>
  //Seq("jar", "tf", list(0).getAbsolutePath) !
  ()
}

