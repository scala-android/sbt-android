resolvers += "typesafe" at "http://repo.typesafe.com/typesafe/releases"

// 1.0:
//libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value
// 0.13:
libraryDependencies += "org.scala-sbt" % "scripted-plugin" % sbtVersion.value

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.7.0")
