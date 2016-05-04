scalaVersion := "2.11.1"

libraryDependencies ++= Seq(
  "org.scaloid" %% "scaloid" % "3.4-10",
  "org.scalatest" %% "scalatest" % "2.2.1-M3",
  "org.mockito" % "mockito-core" % "1.9.5",
  "org.scala-lang" % "scala-reflect" % "2.11.1",
  "org.scala-lang.modules" %% "scala-xml" % "1.0.2",
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.1"
)

proguardOptions in Android ++= Seq(
  "-dontwarn org.scalatest.**",
  "-dontwarn org.mockito.**",
  "-dontwarn org.objenesis.**",
  "-dontnote org.scalatest.**",
  "-dontnote org.mockito.**",
  "-dontnote org.objenesis.**",
  "-keep class * extends org.scalatest.FunSuite"
)

proguardCache in Android ++=
  "org.scaloid" ::
    "org.scalatest" ::
    "org.scalautils" ::
    "org.mockito" ::
    "scala.reflect" ::
    "scala.xml" ::
    "scala.util.parsing.combinator" ::
    "scala.util.parsing.input" ::
    "scala.util.parsing.json" ::
    Nil


javacOptions in Compile ++= Seq("-source", "1.6", "-target", "1.6")

showSdkProgress in Android := false
