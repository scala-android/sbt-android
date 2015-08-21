resolvers += "typesafe" at "http://repo.typesafe.com/typesafe/releases"

libraryDependencies <+= sbtVersion ("org.scala-sbt" % "scripted-plugin" % _)

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.2.5")
