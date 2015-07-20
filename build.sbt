import ScriptedPlugin._
import bintray.Keys._

name := "android-sdk-plugin"

version := "1.4.6"

organization := "com.hanhuy.sbt"

sourceDirectories in Compile <<= baseDirectory(b => Seq(b / "src"))

scalacOptions ++= Seq("-deprecation","-Xlint","-feature")

scalaSource in Compile <<= baseDirectory(_ / "src")

scalaSource in Test <<= baseDirectory(_ / "test")

unmanagedBase <<= baseDirectory(_ / "libs")

resourceDirectory in Compile <<= baseDirectory(_ / "resources")

libraryDependencies ++= Seq(
  "org.ow2.asm" % "asm-all" % "5.0.4",
  "org.javassist" % "javassist" % "3.20.0-GA",
  "net.sf.proguard" % "proguard-base" % "5.0",
  "io.argonaut" %% "argonaut" % "6.1",
  "com.android.tools.build" % "builder" % "1.2.3",
  "com.android.tools.build" % "gradle-core" % "1.2.3" excludeAll
    ExclusionRule(organization = "net.sf.proguard"),
  "com.android.tools.lint" % "lint" % "24.2.3",
  "net.orfjackal.retrolambda" % "retrolambda" % "2.0.5"
)

sbtPlugin := true

// build info plugin

buildInfoSettings

sourceGenerators in Compile <+= buildInfo

buildInfoKeys := Seq(name, version, scalaVersion, sbtVersion)

buildInfoPackage := "android"

bintrayPublishSettings

repository in bintray := "sbt-plugins"

publishMavenStyle := false

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

bintrayOrganization in bintray := None

// scripted-test settings
scriptedSettings

scriptedLaunchOpts ++= Seq("-Xmx1024m", "-XX:PermSize=512m")

//scriptedBufferLog := false

sbtTestDirectory <<= baseDirectory (_ / "sbt-test")

// TODO reorganize tests better, ditch android-sdk-plugin prefix
// group by test config type
scriptedDependencies <<= ( sbtTestDirectory
                         , streams
                         , organization
                         , name
                         , version
                         , sbtVersion) map {
  (dir,s, org, n, v, sbtv) =>
  val testBase = dir / "android-sdk-plugin"
  val tests = testBase.listFiles(DirectoryFilter) filter { d =>
    (d ** "*.sbt").get.size > 0 || (d / "project").isDirectory
  }
  tests foreach { test =>
    val project = test / "project"
    project.mkdirs()
    val pluginsFile = project / "auto_plugins.sbt"
    val propertiesFile = project / "build.properties"
    pluginsFile.delete()
    propertiesFile.delete()
    IO.write(pluginsFile,
      """addSbtPlugin("%s" %% "%s" %% "%s")""" format (org, n, v))
    IO.write(propertiesFile, """sbt.version=%s""" format sbtv)
  }
}

scriptedDependencies <<= scriptedDependencies dependsOn publishLocal
