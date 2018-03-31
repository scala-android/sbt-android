import ScriptedPlugin._

val pluginVersion = "1.8.0-SNAPSHOT"
val gradleBuildVersion = "1.4.0-SNAPSHOT"

val androidToolsVersion = "2.3.0"

// gradle-plugin and gradle-model projects
val model = project.in(file("gradle-model")).settings(
  name := "gradle-discovery-model",
  organization := "com.hanhuy.gradle",
  resolvers += Resolver.jcenterRepo,
  javacOptions ++= "-source" :: "1.6" :: "-target" :: "1.6" :: Nil,
  autoScalaLibrary := false,
  crossPaths := false,
  libraryDependencies ++=
    "com.android.tools.build" % "builder-model" % androidToolsVersion ::
    "org.gradle" % "gradle-tooling-api" % "2.6" % "provided" :: Nil
)

val gradle = project.in(file("gradle-plugin")).settings(
  name := "gradle-discovery-plugin",
  mappings in (Compile, packageBin) ++= (mappings in (Compile, packageBin) in model).value,
  bintrayRepository in bintray := "maven",
  organization := "com.hanhuy.gradle",
  bintrayOrganization in bintray := None,
  resolvers += Resolver.jcenterRepo,
  publishMavenStyle := true,
  autoScalaLibrary := false,
  crossPaths := false,
  sbtPlugin := false,
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  version := "0.5",
  javacOptions ++= "-source" :: "1.6" :: "-target" :: "1.6" :: Nil,
  javacOptions in doc := {
    (javacOptions in doc).value.foldRight(List.empty[String]) {
      (x, a) => if (x != "-target") x :: a else a.drop(1)
    }
  },
  libraryDependencies ++=
    "org.codehaus.groovy" % "groovy" % "2.4.4" % "provided" ::
    "com.android.tools.build" % "gradle" % androidToolsVersion ::
    "com.android.tools.build" % "builder-model" % androidToolsVersion ::
    "org.gradle" % "gradle-tooling-api" % "2.6" % "provided" ::
    "javax.inject" % "javax.inject" % "1" % "provided" ::
    Nil
).dependsOn(model % "compile-internal")

val gradlebuild = project.in(file("gradle-build")).enablePlugins(BuildInfoPlugin).settings(
  version := gradleBuildVersion,
  resolvers += Resolver.jcenterRepo,
  mappings in (Compile, packageBin) ++=
    (mappings in (Compile, packageBin) in model).value,
  name := "sbt-android-gradle",
  organization := "org.scala-android",
  scalacOptions ++= Seq("-deprecation","-Xlint","-feature"),
  libraryDependencies ++= Seq(
    "com.hanhuy.sbt"          %% "bintray-update-checker" % "0.2",
    "com.google.code.findbugs" % "jsr305"                 % "3.0.1"  % "compile-internal",
    "org.gradle"               % "gradle-tooling-api"     % "2.6"    % "provided",
    "org.slf4j"                % "slf4j-api"              % "1.7.10" // required by gradle-tooling-api
  ),
  // embed gradle-tooling-api jar in plugin since they don't publish on central
  products in Compile := {
    val p = (products in Compile).value
    val t = crossTarget.value
    val m = (managedClasspath in Compile).value
    val g = t / "gradle-tooling-api"
    val apiJar = m.collect {
      case j if j.get(moduleID.key).exists(_.organization == "org.gradle") &&
        j.get(moduleID.key).exists(_.name == "gradle-tooling-api") => j.data
    }.headOption
    FileFunction.cached(streams.value.cacheDirectory / "gradle-tooling-api", FilesInfo.lastModified) { in =>
      in foreach (IO.unzip(_, g, { n: String => !n.startsWith("META-INF") }))
      (g ** "*.class").get.toSet
    }(apiJar.toSet)
    g +: p
  },
  sbtPlugin := true,
  bintrayRepository in bintray := "sbt-plugins",
  publishMavenStyle := false,
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  bintrayOrganization in bintray := None,
  buildInfoKeys := Seq(name, version),
  buildInfoPackage := "android.gradle"
).settings(
  addSbtPlugin("org.scala-android" % "sbt-android" % pluginVersion)
).dependsOn(model % "compile-internal")

name := "sbt-android"

organization := "org.scala-android"

version := pluginVersion

scalacOptions ++= Seq("-deprecation","-Xlint","-feature")

sourceDirectories in Compile := baseDirectory(b => Seq(b / "src")).value

scalaSource in Compile := baseDirectory(_ / "src").value

scalaSource in Test := baseDirectory(_ / "test").value

unmanagedBase := baseDirectory(_ / "libs").value

resourceDirectory in Compile := baseDirectory(_ / "resources").value

libraryDependencies ++= Seq(
  "org.ow2.asm" % "asm-all" % "5.0.4",
  "com.google.code.findbugs" % "jsr305" % "3.0.1" % "compile-internal",
  "org.javassist" % "javassist" % "3.20.0-GA",
  "com.hanhuy.sbt" %% "bintray-update-checker" % "0.2", // 1.0 missing
  "com.android.tools.build" % "builder" % androidToolsVersion,
  "org.bouncycastle" % "bcpkix-jdk15on" % "1.51",
  "com.android.tools.build" % "gradle-core" % androidToolsVersion excludeAll
    ExclusionRule(organization = "net.sf.proguard"),
  "com.android.tools.lint" % "lint" % "25.3.0",
//  "com.android.tools.external.com-intellij" % "uast" % "145.597.4", // because google didn't sync the correct version...
  "net.orfjackal.retrolambda" % "retrolambda" % "2.5.1"
)

aggregate := false

sbtPlugin := true

enablePlugins(BuildInfoPlugin)

// build info plugin

buildInfoKeys := Seq(name, version, scalaVersion, sbtVersion)

buildInfoPackage := "android"

// bintray

bintrayRepository in bintray := "sbt-plugins"

publishMavenStyle := false

licenses += ("MIT", url("http://opensource.org/licenses/MIT"))

bintrayOrganization in bintray := None

pomExtra :=
  <scm>
    <url>git@github.com:scala-android/sbt-android.git</url>
    <connection>scm:git:git@github.com:scala-android/sbt-android.git</connection>
  </scm>
    <developers>
      <developer>
        <id>pfnguyen</id>
        <name>Perry Nguyen</name>
        <url>https://github.com/pfn</url>
      </developer>
    </developers>

// scripted-test settings
scriptedSettings // remove for 1.0

scriptedLaunchOpts ++= Seq("-Xmx1024m", "-Dplugin.version=" + version.value)

//scriptedBufferLog := false
sbtTestDirectory := baseDirectory(_ / "sbt-test").value

// TODO reorganize tests better, ditch android-sdk-plugin prefix
// group by test config type
scriptedDependencies :=  {
  val dir  = sbtTestDirectory.value
  val s    = streams.value
  val org  = organization.value
  val n    = name.value
  val v    = version.value
  val sbtv = sbtVersion.value

  val testBases = List(dir / "android-sdk-plugin", dir / "no-travis")
  val tests = testBases.flatMap(_.listFiles(DirectoryFilter)) filter { d =>
    (d ** "*.sbt").get.nonEmpty || (d / "project").isDirectory
  }
  tests foreach { test =>
    val project = test / "project"
    project.mkdirs()
    val pluginsFile = project / "auto_plugins.sbt"
    val propertiesFile = project / "build.properties"
    pluginsFile.delete()
    propertiesFile.delete()
    IO.writeLines(pluginsFile,
      """addSbtPlugin("%s" %% "%s" %% "%s")""".format(org, n, v) ::
      Nil)
    IO.write(propertiesFile, """sbt.version=%s""" format sbtv)
  }
}

scriptedDependencies := (scriptedDependencies dependsOn publishLocal).value
