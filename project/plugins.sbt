resolvers += "typesafe" at "http://repo.typesafe.com/typesafe/releases"

libraryDependencies <+= sbtVersion ("org.scala-sbt" % "scripted-plugin" % _)
