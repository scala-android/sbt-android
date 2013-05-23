name := "android-sdk-plugin"

version := "0.5.0"

organization := "com.hanhuy.sbt"

sourceDirectories in Compile <<= baseDirectory(b => Seq(b / "src"))

scalacOptions ++= Seq("-deprecation","-Xlint")

scalaSource in Compile <<= baseDirectory(_ / "src")

scalaSource in Test <<= baseDirectory(_ / "test")

unmanagedBase <<= baseDirectory(_ / "libs")

resourceDirectory in Compile <<= baseDirectory(_ / "resources")

products in Compile <<= ( products in Compile
                        , unmanagedJars in Compile
                        , crossTarget
                        ) map { (p, u, t) =>
    val jars = t / "jars"
    val dep = jars / "com" / "android" / "AndroidConstants.class"
    if (!dep.exists) {
        u foreach { j => j map { f =>
            IO.unzip(f, jars, { n: String => !n.startsWith("META-INF") })
            f
        }}
    }
    jars +: p
}

libraryDependencies ++= Seq(
  "net.sf.proguard" % "proguard-base" % "4.7" % "compile",
  "com.google.guava" % "guava" % "13.0.1" % "compile",
  "org.bouncycastle" % "bcpkix-jdk15on" % "1.48",
  "org.bouncycastle" % "bcprov-jdk15on" % "1.48"
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
