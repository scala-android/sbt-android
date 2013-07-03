name := "android-sdk-plugin"

version := "0.7.3"

organization := "com.hanhuy.sbt"

sourceDirectories in Compile <<= baseDirectory(b => Seq(b / "src"))

scalacOptions ++= Seq("-deprecation","-Xlint")

scalaSource in Compile <<= baseDirectory(_ / "src")

scalaSource in Test <<= baseDirectory(_ / "test")

unmanagedBase <<= baseDirectory(_ / "libs")

resourceDirectory in Compile <<= baseDirectory(_ / "resources")

libraryDependencies ++= Seq(
  "net.sf.proguard" % "proguard-base" % "4.9",
  "com.android.tools.build" % "builder" % "0.4.2"
)

sbtPlugin := true

publishTo <<= (version) { version =>
  val scalasbt = "http://scalasbt.artifactoryonline.com/scalasbt/"
  val (name, url) = if (version contains "-SNAPSHOT")
    ("scala-sbt-snapshots", scalasbt + "sbt-plugin-snapshots")
  else
    ("scala-sbt-releases", scalasbt + "sbt-plugin-releases")
  Some(Resolver.url(name, new URL(url))(Resolver.ivyStylePatterns))
}

publishMavenStyle := false
