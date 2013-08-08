package android
import sbt._
import sbt.Keys._

import com.android.builder.AndroidBuilder
import com.android.builder.DefaultSdkParser
import com.android.sdklib.{IAndroidTarget,SdkManager}
import com.android.sdklib.BuildToolInfo.PathId
import com.android.SdkConstants
import com.android.utils.ILogger

import java.io.File
import java.util.Properties

import scala.util.control.Exception._
import scala.collection.JavaConversions._
import scala.xml.XML

import Keys._
import Tasks._
import Commands._

object Plugin extends sbt.Plugin {

  // android build steps
  // * handle library dependencies (android.library.reference.N)
  // * ndk TODO
  // * aidl
  // * renderscript
  // * BuildConfig.java
  // * aapt
  // * compile
  // * obfuscate
  // * dex
  // * png crunch
  // * package resources
  // * package apk
  // * sign
  // * zipalign

  lazy val androidBuild: Seq[Setting[_]]= {
    // only set the property below if this plugin is actually used
    // this property is a workaround for bootclasspath messing things
    // up and causing full-recompiles
    System.setProperty("xsbt.skip.cp.lookup", "true")
    seq(allPluginSettings:_*)
  }

  def androidBuild(projects: Project*): Seq[Setting[_]]= {
    androidBuild ++
      (projects map { p =>
        Seq(
          collectResources in Android <<=
            collectResources in Android dependsOn (compile in Compile in p),
          compile in Compile <<= compile in Compile dependsOn(
            packageT in Compile in p)
        )
      }).flatten
  }

  lazy val androidBuildAar: Seq[Setting[_]]= androidBuildAar()
  lazy val androidBuildApklib: Seq[Setting[_]]= androidBuildApklib()
  def androidBuildAar(projects: Project*): Seq[Setting[_]]= {
    androidBuild(projects:_*) ++ buildAar
  }
  def androidBuildApklib(projects: Project*): Seq[Setting[_]]= {
    androidBuild(projects:_*) ++ buildApklib
  }

  def buildAar =
      addArtifact(aarArtifact in Android, packageAar in Android)

  def buildApklib =
    addArtifact(apklibArtifact in Android, packageApklib in Android)

  private lazy val allPluginSettings: Seq[Setting[_]] = inConfig(Compile) (Seq(
    unmanagedSourceDirectories <<= (projectLayout in Android) (l =>
      Seq(l.sources)),
    packageConfiguration in packageBin <<= ( packageConfiguration in packageBin
                                           , baseDirectory
                                           , libraryProject in Android
                                           , classesJar in Android
                                           ) map {
        (c, b, l, j) =>
        // remove R.java generated code from library projects
        val sources = if (l) {
          c.sources filter {
            case (f,n) => !f.getName.matches("R\\W+.*class")
          }
        } else {
          c.sources
        }
        new Package.Configuration(sources, j, c.options)
    },
    publishArtifact in packageBin := false,
    publishArtifact in packageSrc := false,
    scalaSource       <<= (projectLayout in Android) (_.scalaSource),
    javaSource        <<= (projectLayout in Android) (_.javaSource),
    resourceDirectory <<= (projectLayout in Android) (_.res),
    unmanagedJars     <<= unmanagedJarsTaskDef,
    classDirectory    <<= (binPath in Android) (_ / "classes"),
    sourceGenerators  <+= (rGenerator in Android
                          , typedResourcesGenerator in Android
                          , apklibs in Android
                          , aidl in Android
                          , buildConfigGenerator in Android
                          , renderscript in Android
                          , cleanForR in Android
                          ) map {
      (a, tr, apkl, aidl, bcg, rs, _) =>
      val apkls = apkl map { l =>
        (l.layout.javaSource ** "*.java" get) ++
          (l.layout.scalaSource ** "*.scala" get)
      } flatten

      a ++ tr ++ aidl ++ bcg ++ rs ++ apkls
    },
    copyResources      := { Seq.empty },
    packageT          <<= packageT dependsOn(compile),
    javacOptions      <<= ( javacOptions
                          , sdkParser in Android) map {
      case (o, p) =>
      // users will want to call clean before compiling if changing debug
      val debugOptions = if (createDebug) Seq("-g") else Seq.empty
      val bcp = AndroidBuilder.getBootClasspath(p) mkString File.pathSeparator
      // make sure javac doesn't create code that proguard won't process
      // (e.g. people with java7) -- specifying 1.5 is fine for 1.6, too
      // TODO sbt 0.12 expects javacOptions to be a Task, not Setting
      o ++ Seq("-bootclasspath" , bcp,
        "-source", "1.5" , "-target", "1.5") ++ debugOptions
    },
    scalacOptions     <<= ( scalacOptions
                          , sdkParser in Android) map {
      case (o, p) =>
      // scalac has -g:vars by default
      val bcp = AndroidBuilder.getBootClasspath(p) mkString File.pathSeparator
      o ++ Seq("-bootclasspath", bcp, "-javabootclasspath", bcp)
    }
  )) ++ inConfig(Test) (Seq(
    managedClasspath <++= (platform in Android) map { t =>
      (Option(t.getOptionalLibraries) map {
        _ map ( j => Attributed.blank(file(j.getJarPath)) )
      } getOrElse Array.empty).toSeq
    },
    scalacOptions in console    := Seq.empty,
    sourceDirectory            <<= (projectLayout in Android) (_.testSources),
    unmanagedSourceDirectories <<= (projectLayout in Android) (l =>
      Set(l.testSources, l.testJavaSource, l.testScalaSource).toSeq)
  )) ++ inConfig(Android) (Classpaths.configSettings ++ Seq(
    // productX := Nil is a necessity to use Classpaths.configSettings
    exportedProducts         := Nil,
    products                 := Nil,
    productDirectories       := Nil,
    classpathConfiguration   := config("compile"),
    // hack since it doesn't take in dependent project's libs
    dependencyClasspath     <<= ( dependencyClasspath in Compile
                                , libraryDependencies
                                , streams) map { (cp, d, s) =>
      s.log.debug("Filtering compile:dependency-classpath from: " + cp)
      val pvd = d filter { dep => dep.configurations exists (_ == "provided") }

      cp foreach { a =>
        s.log.debug("%s => %s: %s" format (a.data.getName,
          a.get(configuration.key), a.get(moduleID.key)))
      }
      // it seems internal-dependency-classpath already filters out "provided"
      // from other projects, now, just filter out our own "provided" lib deps
      // do not filter out provided libs for scala, we do that later
      cp filterNot {
        a =>
        (a.get(moduleID.key) map { m =>
          m.organization != "org.scala-lang" &&
            (pvd exists (p => m.organization == p.organization &&
              m.name == p.name))
        } getOrElse false)
      }
    },
    // end for Classpaths.configSettings
    apklibs                 <<= apklibsTaskDef,
    aars                    <<= aarsTaskDef,
    aarArtifact             <<= name { n => Artifact(n, "aar", "aar") },
    apklibArtifact          <<= name { n => Artifact(n, "apklib", "apklib") },
    packageAar              <<= packageAarTaskDef,
    packageApklib           <<= packageApklibTaskDef,
    install                 <<= installTaskDef,
    uninstall               <<= uninstallTaskDef,
    test                    <<= testTaskDef,
    test                    <<= test dependsOn (compile in Test, install),
    run                     <<= runTaskDef(install,
                                           sdkPath, manifest, packageName),
    cleanForR               <<= (rGenerator
                                , cacheDirectory
                                , genPath
                                , classDirectory in Compile
                                , streams
                                ) map {
      (_, c, g, d, s) =>
      (FileFunction.cached(c / "clean-for-r",
          FilesInfo.hash, FilesInfo.exists) { in =>
        if (!in.isEmpty) {
          s.log.info("Rebuilding all classes because R.java has changed")
          IO.delete(d)
        }
        in
      })(Set(g ** "R.java" get: _*))
      Seq.empty[File]
    },
    buildConfigGenerator    <<= buildConfigGeneratorTaskDef,
    binPath                 <<= projectLayout (_.bin),
    classesJar              <<= binPath (_ / "classes.jar"),
    classesDex              <<= binPath (_ / "classes.dex"),
    rGenerator              <<= rGeneratorTaskDef,
    rGenerator              <<= rGenerator dependsOn (renderscript),
    aidl                    <<= aidlTaskDef,
    renderscript            <<= renderscriptTaskDef,
    genPath                 <<= projectLayout (_.gen),
    localProjects           <<= (baseDirectory, properties) { (b,p) =>
      loadLibraryReferences(b, p)
    },
    libraryProjects         <<= ( baseDirectory
                                , localProjects
                                , apklibs
                                , aars) map {
      (b,local,a,aa) => a ++ aa ++ local
    },
    libraryProject          <<= properties { p =>
      Option(p.getProperty("android.library")) map {
        _.equals("true") } getOrElse false },
    dexInputs               <<= dexInputsTaskDef,
    dex                     <<= dexTaskDef,
    platformJars            <<= platform map { p =>
      (p.getPath(IAndroidTarget.ANDROID_JAR),
      (Option(p.getOptionalLibraries) map(_ map(_.getJarPath))).getOrElse(
        Array.empty).toSeq)
    },
    projectLayout           <<= baseDirectory (ProjectLayout.apply),
    manifestPath            <<= projectLayout { l =>
      l.manifest
    },
    properties              <<= baseDirectory (b => loadProperties(b)),
    processManifest         <<= processManifestTaskDef,
    manifest                <<= manifestPath { m => XML.loadFile(m) },
    versionCode             <<= manifest { m =>
      m.attribute(ANDROID_NS, "versionCode") flatMap { v =>
        catching(classOf[Exception]) opt { (v(0) text) toInt }
      }
    },
    versionName             <<= manifest { m =>
      m.attribute(ANDROID_NS, "versionName") map { _(0) text }},
    packageForR             <<= manifest { m =>
      m.attribute("package") get (0) text
    },
    packageName             <<= manifest { m =>
      m.attribute("package") get (0) text
    },
    targetSdkVersion        <<= (manifest, minSdkVersion) { (m, min) =>
      val usesSdk = (m \ "uses-sdk")
      if (usesSdk.isEmpty) -1 else
        usesSdk(0).attribute(ANDROID_NS, "targetSdkVersion") map { v =>
            (v(0) text).toInt } getOrElse min
    },
    minSdkVersion        <<= manifest { m =>
      val usesSdk = (m \ "uses-sdk")
      if (usesSdk.isEmpty) -1 else
        usesSdk(0).attribute(ANDROID_NS, "minSdkVersion") map { v =>
          (v(0) text).toInt } getOrElse 1
    },
    proguardLibraries        := Seq.empty,
    proguardOptions          := Seq.empty,
    proguardConfig          <<= proguardConfigTaskDef,
    proguardConfig          <<= proguardConfig dependsOn(packageResources),
    proguard                <<= proguardTaskDef,
    proguardInputs          <<= proguardInputsTaskDef,
    proguardInputs          <<= proguardInputs dependsOn (packageT in Compile),
    proguardScala           <<= (scalaSource in Compile) {
      s => (s ** "*.scala").get.size > 0
    },
    typedResources          <<= proguardScala,
    typedResourcesGenerator <<= typedResourcesGeneratorTaskDef,
    useProguard             <<= proguardScala,
    useSdkProguard          <<= proguardScala (!_),
    useProguardInDebug      <<= proguardScala,
    collectResources        <<= collectResourcesTaskDef,
    packageResources        <<= packageResourcesTaskDef,
    apkFile                 <<= (name, projectLayout) { (n,l) =>
      val apkdir = l.bin / ".build_integration"
      apkdir.mkdirs()
      apkdir / (n + "-BUILD-INTEGRATION.apk")
    },
    collectJni              <<= collectJniTaskDef,
    apkbuild                <<= apkbuildTaskDef,
    signRelease             <<= signReleaseTaskDef,
    zipalign                <<= zipalignTaskDef,
    packageT                <<= zipalign,
    setDebug                 := { _createDebug = true },
    setRelease               := { _createDebug = false },
    // I hope packageXXX dependsOn(setXXX) sets createDebug before package
    // because of how packageXXX is implemented by using task.?
    packageDebug            <<= packageT.task.? {
      _ getOrElse sys.error("package failed")
    },
    packageDebug            <<= packageDebug dependsOn(setDebug),
    packageRelease          <<= packageT.task.? {
      _ getOrElse sys.error("package failed")
    },
    packageRelease          <<= packageRelease dependsOn(setRelease),
    sdkPath                 <<= (thisProject,properties) { (p,props) =>
        (Option(props get "sdk.dir") orElse
          Option(System getenv "ANDROID_HOME")) flatMap { p =>
            val f = file(p + File.separator)
            if (f.exists && f.isDirectory)
              Some(p + File.separator)
            else
              None
          } getOrElse (
            sys.error("set ANDROID_HOME or run 'android update project -p %s'"
              format p.base))
    },
    zipalignPath            <<= sdkPath {
      import SdkConstants._
      _ + OS_SDK_TOOLS_FOLDER + FN_ZIPALIGN
    },
    ilogger                  := { l: Logger =>
      SbtLogger(l)
    },
    sdkParser               <<= ( sdkManager
                                , platformTarget
                                , ilogger
                                , streams) map {
      (m, t, l, s) =>
      val parser = new DefaultSdkParser(m.getLocation)

      parser.initParser(t, m.getLatestBuildTool.getRevision, l(s.log))
      parser
    },
    builder                 <<= (sdkParser, ilogger, streams) map {
      (parser, l, s) =>

      new AndroidBuilder(parser, "android-sdk-plugin", l(s.log), false)
    },
    sdkManager              <<= (sdkPath,ilogger, streams) map { (p, l, s) =>
      SdkManager.createManager(p, l(s.log))
    },

    platformTarget          <<= properties (_("target")),
    platform                <<= (sdkManager, platformTarget, thisProject) map {
      (m, p, prj) =>
      val plat = Option(m.getTargetFromHashString(p))
      plat getOrElse sys.error("Platform %s unknown in %s" format (p, prj.base))
    }
  )) ++ Seq(
    crossPaths      <<= (scalaSource in Compile) { src =>
      (src ** "*.scala").get.size > 0
    },
    resolvers        <++= (sdkPath in Android) { p => Seq(
      "google libraries" at (
        file(p) / "extras" / "google" / "m2repository").toURI.toString,
      "android libraries" at (
        file(p) / "extras" / "android" / "m2repository").toURI.toString)
    },
    cleanFiles        <+= binPath in Android,
    cleanFiles        <+= genPath in Android,
    exportJars         := true,
    unmanagedBase     <<= (projectLayout in Android) (_.libs)
  ) ++ androidCommands

  lazy val androidCommands: Seq[Setting[_]] = Seq(
    commands ++= Seq(devices, device, reboot, adbWifi)
  )

  private def device = Command(
    "device", ("device", "Select a connected android device"),
    "Select a device (when there are multiple) to apply actions to"
  )(deviceParser)(deviceAction)

  private def adbWifi = Command.command(
    "adb-wifi", "Enable/disable ADB-over-wifi for selected device",
    "Toggle ADB-over-wifi for the selected device"
  )(adbWifiAction)

  private def reboot = Command(
    "reboot-device", ("reboot-device", "Reboot selected device"),
    "Reboot the selected device into the specified mode"
  )(rebootParser)(rebootAction)

  private def devices = Command.command(
    "devices", "List connected and online android devices",
    "List all connected and online android devices")(devicesAction)
}

case class SbtLogger(lg: Logger) extends ILogger {
  override def verbose(fmt: java.lang.String, args: Object*) {
    lg.debug(String.format(fmt, args:_*))
  }
  override def info(fmt: java.lang.String, args: Object*) {
    lg.debug(String.format(fmt, args:_*))
  }
  override def warning(fmt: java.lang.String, args: Object*) {
    lg.warn(String.format(fmt, args:_*))
  }
  override def error(t: Throwable, fmt: java.lang.String, args: Object*) {
    // they don't end the build, so log as a warning
    lg.warn(String.format(fmt, args:_*))
    if (t != null)
      t.printStackTrace
  }
}
