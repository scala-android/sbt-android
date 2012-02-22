sbtPlugin := true

name := "android-sdk-build"

version := "0.1"

organization := "com.hanhuy.sbt"

sourceDirectories in Compile <<= baseDirectory(b => Seq(b / "src"))

scalaSource in Compile <<= baseDirectory(_ / "src")

scalaSource in Test <<= baseDirectory(_ / "test")

unmanagedBase <<= baseDirectory(_ / "libs")

//managedSourceDirectories <<= baseDirectory(b => Seq(b / "gen"))
//managedSources <<= baseDirectory map (b => Seq(b / "gen" / "t.scala"))

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
