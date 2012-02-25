name := "android-sdk-plugin"

version := "0.1.0"

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

libraryDependencies += "net.sf.proguard" % "proguard-base" % "4.6" % "compile"

sbtPlugin := true
