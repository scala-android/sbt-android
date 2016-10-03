enablePlugins(AndroidApp)

scalaVersion := "2.11.7"

libraryDependencies += "org.scalatest" %% "scalatest" % "2.1.5" % "test"

showSdkProgress := false

debugIncludesTests := false

javacOptions ++= List("-source", "1.7", "-target", "1.7")
