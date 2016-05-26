lazy val bintraySbt = RootProject(uri("git://github.com/eed3si9n/bintray-sbt#topic/sbt1.0.0-M4"))
lazy val root = (project in file(".")).
  dependsOn(bintraySbt)

libraryDependencies <+= sbtVersion ("org.scala-sbt" %% "scripted-plugin" % _)

addSbtPlugin("com.eed3si9n" %% "sbt-buildinfo" % "0.6.1")
