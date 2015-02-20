package android

import java.util.Properties

import android.Dependencies.LibraryProject
import com.android.ide.common.process.BaseProcessOutputHandler.BaseProcessOutput
import com.android.ide.common.process._
import sbt._
import sbt.Keys._

import com.android.builder.core.AndroidBuilder
import com.android.builder.sdk.DefaultSdkLoader
import com.android.sdklib.{AndroidTargetHash, IAndroidTarget, SdkManager}
import com.android.sdklib.repository.FullRevision
import com.android.SdkConstants
import com.android.utils.ILogger

import java.io.{PrintWriter, File}

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
    allPluginSettings
  }

  @deprecated("Use Project.androidBuildWith(subprojects) instead", "1.3.3")
  def androidBuild(projects: Project*): Seq[Setting[_]]= {
    androidBuild ++
      (projects flatMap { p =>
        Seq(
          collectResources in Android <<=
            collectResources in Android dependsOn (compile in Compile in p),
          compile in Compile <<= compile in Compile dependsOn(
            packageT in Compile in p)
        )
      }) :+ (localProjects in Android := projects map { p =>
        LibraryProject(p.base)
      })
  }

  lazy val androidBuildAar: Seq[Setting[_]] = androidBuildAar()
  lazy val androidBuildApklib: Seq[Setting[_]] = androidBuildApklib()
  def androidBuildAar(projects: Project*): Seq[Setting[_]] = {
    androidBuild(projects:_*) ++ buildAar
  }
  def androidBuildApklib(projects: Project*): Seq[Setting[_]] = {
    androidBuild(projects:_*) ++ buildApklib
  }

  def buildAar = Seq(libraryProject in Android := true) ++
      addArtifact(aarArtifact in Android, packageAar in Android)

  def buildApklib = Seq(libraryProject in Android := true) ++
    addArtifact(apklibArtifact in Android, packageApklib in Android)

  private lazy val allPluginSettings: Seq[Setting[_]] = inConfig(Compile) (Seq(
    update                     <<= (update, state) map { (u, s) =>
      UpdateChecker.checkCurrent(s.log)
      u
    },
    sourceManaged              <<= (projectLayout in Android) (_.gen),
    unmanagedSourceDirectories <<= (projectLayout in Android) (l =>
      Set(l.sources, l.javaSource, l.scalaSource).toSeq),
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
    scalaSource       <<= (projectLayout in Android) (_.scalaSource),
    javaSource        <<= (projectLayout in Android) (_.javaSource),
    resourceDirectory <<= (projectLayout in Android) (_.res),
    unmanagedJars     <<= unmanagedJarsTaskDef,
    // doesn't work properly yet, not for intellij integration
    //managedClasspath  <<= managedClasspathTaskDef,
    unmanagedClasspath <+= classDirectory map Attributed.blank,
    classDirectory    <<= (binPath in Android) (_ / "classes"),
    sourceGenerators  <+= (rGenerator in Android
                          , typedResourcesGenerator in Android
                          , apklibs in Android
                          , autolibs in Android
                          , aidl in Android
                          , buildConfigGenerator in Android
                          , renderscript in Android
                          , debugTestsGenerator in Android
                          , cleanForR in Android
                          ) map {
      (a, tr, apkl, autos, aidl, bcg, rs, deb, _) =>
      val autosrcs = (apkl ++ autos) map { l =>
        (l.layout.javaSource ** "*.java" get) ++
          (l.layout.scalaSource ** "*.scala" get)
      } flatten

      a ++ tr ++ aidl ++ bcg ++ rs ++ autosrcs ++ deb
    },
    copyResources      := { Seq.empty },
    packageT          <<= packageT dependsOn compile,
    javacOptions      <<= ( javacOptions
                          , builder in Android
                          , apkbuildDebug in Android
                          , retrolambdaEnable in Android) map {
      (o,bldr, debug, re) =>
      // users will want to call clean before compiling if changing debug
      val debugOptions = if (debug()) Seq("-g") else Seq.empty
      val bcp = bldr.getBootClasspath mkString File.pathSeparator
      // make sure javac doesn't create code that proguard won't process
      // (e.g. people with java7) -- specifying 1.5 is fine for 1.6, too
      o ++ (if (!re) Seq("-bootclasspath" , bcp) else
        Seq("-Xbootclasspath/a:" + bcp)) ++ debugOptions
    },
    scalacOptions     <<= (scalacOptions, builder in Android) map { (o,bldr) =>
      // scalac has -g:vars by default
      val bcp = bldr.getBootClasspath mkString File.pathSeparator
      o ++ Seq("-bootclasspath", bcp, "-javabootclasspath", bcp)
    }
  )) ++ inConfig(Test) (Seq(
    exportJars         := false,
    managedClasspath <++= (platform in Android) map { t =>
      (Option(t.getOptionalLibraries) map {
        _ map ( j => Attributed.blank(file(j.getJarPath)) )
      } getOrElse Array.empty).toSeq
    },
    scalacOptions in console    := Seq.empty
  )) ++ inConfig(Android) (Classpaths.configSettings ++ Seq(
    // support for android:test
    classDirectory              := (classDirectory in Test).value,
    sourceDirectory            <<= (projectLayout in Android) (_.testSources),
    managedSources              := Nil,
    unmanagedSourceDirectories <<= (projectLayout in Android) (l =>
      Set(l.testSources, l.testJavaSource, l.testScalaSource).toSeq),
    unmanagedSources           <<= Defaults.collectFiles(
      unmanagedSourceDirectories,
      includeFilter in (Compile,unmanagedSources),
      excludeFilter in (Compile,unmanagedSources)),
    scalacOptions               := (scalacOptions in Compile).value,
    javacOptions                :=  (javacOptions in Compile).value,
    compile := {
      def exported(w: PrintWriter, command: String): Seq[String] => Unit =
        args => w.println((command +: args).mkString(" "))
      val s = streams.value
      val ci = (compileInputs in compile).value
      val reporter = (TaskKey[Option[xsbti.Reporter]]("compilerReporter") in (Compile,compile)).value
      lazy val x = s.text(CommandStrings.ExportStream)
      def onArgs(cs: Compiler.Compilers) =
        cs.copy(scalac = cs.scalac.onArgs(exported(x, "scalac")),
          javac = cs.javac.onArgs(exported(x, "javac")))
      val i = ci.copy(compilers = onArgs(ci.compilers))

      try reporter match {
        case Some(r) => Compiler(i, s.log, r)
        case None           => Compiler(i, s.log)
      }
      finally x.close() // workaround for #937
    },
    compileIncSetup := {
      Compiler.IncSetup(
        Defaults.analysisMap(dependencyClasspath.value),
        definesClass.value,
        (skip in compile).value,
        // TODO - this is kind of a bad way to grab the cache directory for streams...
        streams.value.cacheDirectory / compileAnalysisFilename.value,
        compilerCache.value,
        incOptions.value)
    },
    compileInputs in compile := {
      val cp = classDirectory.value +: Attributed.data(dependencyClasspath.value)
      Compiler.inputs(cp, sources.value, classDirectory.value, scalacOptions.value, javacOptions.value, maxErrors.value, sourcePositionMappers.value, compileOrder.value)(compilers.value, compileIncSetup.value, streams.value.log)
    },
    compileAnalysisFilename := {
      // Here, if the user wants cross-scala-versioning, we also append it
      // to the analysis cache, so we keep the scala versions separated.
      val extra =
        if (crossPaths.value) s"_${scalaBinaryVersion.value}"
        else ""
      s"inc_compile${extra}"
    },
    sources <<= Classpaths.concat(unmanagedSources, managedSources),
      // productX := Nil is a necessity to use Classpaths.configSettings
    exportedProducts         := Nil,
    products                 := Nil,
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
      cp filterNot { _.get(moduleID.key) exists { m =>
          m.organization != "org.scala-lang" &&
            (pvd exists (p => m.organization == p.organization &&
              m.name == p.name))
        }
      }
    },
    // end for Classpaths.configSettings
    transitiveAndroidLibs    := true,
    transitiveAndroidWarning := true,
    autolibs                <<= autolibsTaskDef,
    apklibs                 <<= apklibsTaskDef,
    localAars                := Nil,
    aars                    <<= aarsTaskDef,
    aarArtifact             <<= name { n => Artifact(n, "aar", "aar") },
    apklibArtifact          <<= name { n => Artifact(n, "apklib", "apklib") },
    packageAar              <<= packageAarTaskDef,
    packageApklib           <<= packageApklibTaskDef,
    install                 <<= installTaskDef,
    uninstall               <<= uninstallTaskDef,
    test                    <<= testTaskDef,
    test                    <<= test dependsOn (compile in Android, install),
    testOnly                <<= testOnlyTaskDef,
    run                     <<= runTaskDef( install
                                          , sdkPath
                                          , projectLayout
                                          , packageName),
    cleanForR               <<= (rGenerator
                                , genPath
                                , classDirectory in Compile
                                , streams
                                ) map {
      (_, g, d, s) =>
      FileFunction.cached(s.cacheDirectory / "clean-for-r",
          FilesInfo.hash, FilesInfo.exists) { in =>
        if (in.nonEmpty) {
          s.log.info("Rebuilding all classes because R.java has changed")
          IO.delete(d)
        }
        in
      }(Set(g ** "R.java" get: _*))
      Seq.empty[File]
    },
    buildConfigGenerator    <<= buildConfigGeneratorTaskDef,
    buildConfigOptions       := Nil,
    binPath                 <<= projectLayout (_.bin),
    classesJar              <<= binPath (_ / "classes.jar"),
    rGenerator              <<= rGeneratorTaskDef,
    rGenerator              <<= rGenerator dependsOn renderscript,
    ndkJavah                <<= ndkJavahTaskDef,
    ndkBuild                <<= ndkBuildTaskDef,
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
      Option(p.getProperty("android.library")) exists { _.equals("true") } },
    dexInputs               <<= dexInputsTaskDef,
    dex                     <<= dexTaskDef,
    dexMaxHeap               := "1024m",
    dexMulti                 := false,
    dexMainFileClasses       := Seq.empty,
    dexMinimizeMainFile      := false,
    dexAdditionalParams      := Seq.empty,
    dexMainFileClassesConfig <<= dexMainFileClassesConfigTaskDef,
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
    mergeManifests           := true,
    manifestPlaceholders     := Map.empty,
    processManifest         <<= processManifestTaskDef,
    manifest                <<= manifestPath { m =>
      if (!m.exists)
        sys.error("cannot find AndroidManifest.xml: " + m)
      XML.loadFile(m)
    },
    versionCode              := None,
    versionName              := None,
    packageForR             <<= manifest { m =>
      m.attribute("package") get 0 text
    },
    packageName             <<= manifest { m =>
      m.attribute("package") get 0 text
    },
    targetSdkVersion        <<= (manifest, minSdkVersion) { (m, min) =>
      val usesSdk = m \ "uses-sdk"
      if (usesSdk.isEmpty) "1" else
        usesSdk(0).attribute(ANDROID_NS, "targetSdkVersion") map {
            _(0) text } getOrElse min
    },
    minSdkVersion        <<= manifest { m =>
      val usesSdk = m \ "uses-sdk"
      if (usesSdk.isEmpty) "1" else
        usesSdk(0).attribute(ANDROID_NS, "minSdkVersion") map {
          _(0) text } getOrElse "1"
    },
    proguardCache            := Seq(ProguardCache("scala") % "org.scala-lang"),
    proguardLibraries        := Seq.empty,
    proguardOptions          := Seq.empty,
    proguardConfig          <<= proguardConfigTaskDef,
    proguardConfig          <<= proguardConfig dependsOn packageResources,
    proguard                <<= proguardTaskDef,
    proguardInputs          <<= proguardInputsTaskDef,
    proguardInputs          <<= proguardInputs dependsOn (packageT in Compile),
    proguardScala           <<= (scalaSource in Compile) {
      s => (s ** "*.scala").get.size > 0
    },
    retrolambdaEnable       <<= (javaSource in Compile) {
      s => (s ** "*.java").get.size > 0 && RetrolambdaSupport.isAvailable
    },
    typedResources          <<= proguardScala,
    typedResourcesIgnores    := Seq.empty,
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
    apkbuildExcludes         := Seq.empty,
    apkbuildPickFirsts       := Seq.empty,
    apkbuildDebug            := MutableSetting(true),
    apkbuild                <<= apkbuildTaskDef,
    apkbuild                <<= apkbuild dependsOn (managedResources in Compile),
    apkSigningConfig        <<= properties { p =>
      (Option(p.getProperty("key.alias")),
        Option(p.getProperty("key.store")),
        Option(p.getProperty("key.store.password"))) match {
        case (Some(alias), Some(store), Some(passwd)) =>
          val c = PlainSigningConfig(file(store), passwd, alias)
          val c2 = Option(p.getProperty("key.store.type")) map { t =>
            c.copy(storeType = t) } getOrElse c
          Option(p.getProperty("key.alias.password")) map { p =>
            c.copy(keyPass = Some(p)) } orElse Some(c2)
        case _ => None
      }
    },
    signRelease             <<= signReleaseTaskDef,
    zipalign                <<= zipalignTaskDef,
    packageT                <<= zipalign,
    instrumentTestTimeout    := 180000,
    instrumentTestRunner     := "android.test.InstrumentationTestRunner",
    debugIncludesTests       := true,
    debugTestsGenerator     <<= (debugIncludesTests,projectLayout) map {
      (tests,layout) =>
      if (tests)
        (layout.testScalaSource ** "*.scala" get) ++
          (layout.testJavaSource ** "*.java" get)
      else Seq.empty
    },
    setDebug                 := { apkbuildDebug.value(true) },
    setRelease               := { apkbuildDebug.value(false) },
    // I hope packageXXX dependsOn(setXXX) sets createDebug before package
    // because of how packageXXX is implemented by using task.?
    packageDebug            <<= packageT.task.? {
      _ getOrElse sys.error("package failed")
    },
    packageDebug            <<= packageDebug dependsOn setDebug,
    packageRelease          <<= packageT.task.? {
      _ getOrElse sys.error("package failed")
    },
    packageRelease          <<= packageRelease dependsOn setRelease,
    sdkPath                 <<= (thisProject,properties) { (p,props) =>
      (Option(System getenv "ANDROID_HOME") orElse
        Option(props get "sdk.dir")) flatMap { p =>
            val f = file(p + File.separator)
            if (f.exists && f.isDirectory)
              Some(p + File.separator)
            else
              None
          } getOrElse sys.error(
            "set ANDROID_HOME or run 'android update project -p %s'"
              format p.base)
    },
    zipalignPath            <<= ( sdkPath
                                , sdkManager
                                , buildToolsVersion
                                , streams) map { (p, m, v, s) =>
      val bt = v map { version =>
        m.getBuildTool(FullRevision.parseRevision(version))
      } getOrElse {
        m.getLatestBuildTool
      }
      if (bt == null) {
        sys.error("Android SDK build-tools not found: " + v)
      }
      import SdkConstants._
      val pathInBt = bt.getLocation / FN_ZIPALIGN

      s.log.debug("checking zipalign at: " + pathInBt)

      if (pathInBt.exists)
        pathInBt.getAbsolutePath
      else {
        val zipalign = file(p + OS_SDK_TOOLS_FOLDER + FN_ZIPALIGN)
        if (!zipalign.exists)
          sys.error("zipalign not found at either %s or %s" format (
            pathInBt, zipalign))
        zipalign.getAbsolutePath
      }
    },
    ilogger                  := { l: Logger => SbtLogger(l) },
    buildToolsVersion        := None,
    sdkLoader               <<= ( sdkManager
                                , platformTarget
                                , buildToolsVersion
                                , ilogger
                                , streams) map {
      (m, t, v, l, s) =>
      val parser = DefaultSdkLoader.getLoader(file(m.getLocation))
      v map { version =>
        m.getBuildTool(FullRevision.parseRevision(version))
      } getOrElse {
        val tools = m.getLatestBuildTool
        s.log.debug("Using Android build-tools: " + tools)
        tools
      }
      parser
    },
    builder                 <<= ( sdkLoader
                                , sdkManager
                                , name
                                , ilogger
                                , buildToolsVersion
                                , platformTarget
                                , state) map {
      (ldr, m, n, l, v, t, s) =>

      val bldr = new AndroidBuilder(n, "android-sdk-plugin",
        new DefaultProcessExecutor(l(s.log)),
        new JavaProcessExecutor {
          override def execute(javaProcessInfo: JavaProcessInfo, processOutputHandler: ProcessOutputHandler) = {
            val options = ForkOptions(
              envVars = javaProcessInfo.getEnvironment map { case ((x,y)) => x -> y.toString } toMap,
              runJVMOptions = javaProcessInfo.getJvmArgs ++
                ("-cp" :: javaProcessInfo.getClasspath :: Nil))
            val r = Fork.java(options, (javaProcessInfo.getMainClass :: Nil) ++ javaProcessInfo.getArgs)
            new ProcessResult {
              override def assertNormalExitValue() = {
                if (r != 0) throw new ProcessException("error code: " + r)
                this
              }

              override def rethrowFailure() = this

              override def getExitValue = r
            }
          }
        }, new BaseProcessOutputHandler {
          override def handleOutput(processOutput: ProcessOutput) = {
            processOutput match {
              case p: BaseProcessOutput =>
                val stdout = p.getStandardOutputAsString
                if (!stdout.isEmpty)
                  s.log.info(stdout)
                val stderr = p.getErrorOutputAsString
                if (!stderr.isEmpty)
                  s.log.error(stderr)
            }
          }
        },
        l(s.log), false)
      val sdkInfo = ldr.getSdkInfo(l(s.log))
      val rev = v map { version =>
        FullRevision.parseRevision(version)
      } getOrElse {
        val tools = m.getLatestBuildTool
        if (tools == null) {
          s.log.info("Available build tools: \n  " +
            (m.getBuildTools mkString "\n  "))
          sys.error("Android SDK build-tools not found: " + v)
        }
        s.log.debug("Using Android build-tools: " + tools)
        tools.getRevision
      }
      val targetInfo = ldr.getTargetInfo(t, rev, l(s.log))
      bldr.setTargetInfo(sdkInfo, targetInfo)
      bldr
    },
    sdkManager              <<= (sdkPath,ilogger, streams) map { (p, l, s) =>
      SdkManager.createManager(p, l(s.log))
    },

    platformTarget          <<= properties { p =>
      Option(p.getProperty("target")) getOrElse sys.error(
        "configure project.properties or set 'platformTarget in Android'")
    },
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
    update                     <<= (update, state) map { (u, s) =>
      UpdateChecker.checkCurrent(s.log)
      u
    },
    cleanFiles        <+= binPath in Android,
    cleanFiles        <+= genPath in Android,
    exportJars         := true,
    unmanagedBase     <<= (projectLayout in Android) (_.libs)
  )

  override def buildSettings = androidCommands

  lazy val androidCommands: Seq[Setting[_]] = Seq(
    commands ++= Seq(genAndroid, genAndroidSbt, pidcat, logcat, adbLs, adbShell,
      devices, device, reboot, adbScreenOn, adbRunas, adbKill,
      adbWifi, adbPush, adbPull, adbCat, adbRm)
  )

  private def adbCat = Command(
    "adb-cat", ("adb-cat", "Cat a file from device"),
    "Cat a file from device to stdout"
  )(androidFileParser)(adbCatAction)

  private def adbRm = Command(
    "adb-rm", ("adb-rm", "Remove a file from device"),
    "Remove a file from device"
  )(androidFileParser)(adbRmAction)

  private def adbPull = Command(
    "adb-pull", ("adb-pull", "pull a file from device"),
    "Pull a file from device to the local system"
  )(adbPullParser)(adbPullAction)

  private def adbPush = Command(
    "adb-push", ("adb-push", "push a file to device"),
    "Push a file to device from the local system"
  )(adbPushParser)(adbPushAction)

  private def adbShell = Command(
    "adb-shell", ("adb-shell", "execute shell commands on device"),
    "Run a command on a selected android device using adb"
  )(stringParser)(shellAction)

  private def adbRunas = Command(
    "adb-runas", ("adb-runas", "execute shell commands on device as a debuggable package user"),
    "Run a command on a selected android device using adb with the permissions of the current package"
  )(stringParser)(runasAction)

  private def adbKill = Command(
    "adb-kill", ("adb-kill", "kill the current/specified package"),
    "Kills the process if it is not currently in the foreground"
  )(stringParser)(killAction)

  private def adbLs = Command(
    "adb-ls", ("adb-ls", "list device files"),
    "List files located on the selected android device"
  )(androidFileParser)(adbLsAction)

  private def logcat = Command(
    "logcat", ("logcat", "grab device logcat"),
    "Read logcat from device without blocking"
  )(stringParser)(logcatAction)

  private def pidcat = Command(
    "pidcat", ("pidcat", "grab device logcat for a package"),
    "Read logcat for a given package, defaults to project package if no arg"
  )(stringParser)(pidcatAction)

  private def genAndroid = Command(
    "gen-android", ("gen-android", "Create an android project"),
    "Create a new android project built using SBT"
  )(createProjectParser)(createProjectAction)

  private def genAndroidSbt = Command.command(
    "gen-android-sbt", "Create SBT files for existing android project",
    "Creates build.properties, build.scala, etc for an existing android project"
  )(createProjectSbtAction)

  private def device = Command(
    "device", ("device", "Select a connected android device"),
    "Select a device (when there are multiple) to apply actions to"
  )(deviceParser)(deviceAction)

  private def adbScreenOn = Command.command(
    "adb-screenon", "Turn on screen and unlock (if not protected)",
    "Turn the screen on and unlock the keyguard if it is not pin-protected"
  )(adbPowerAction)

  private def adbWifi = Command.command(
    "adb-wifi", "Enable/disable ADB-over-wifi for selected device",
    "Toggle ADB-over-wifi for the selected device"
  )(adbWifiAction)

  private def reboot = Command(
    "adb-reboot", ("adb-reboot", "Reboot selected device"),
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
      t.printStackTrace()
  }
}
object NullLogger extends ILogger {
  override def verbose(fmt: java.lang.String, args: Object*) = ()
  override def info(fmt: java.lang.String, args: Object*) = ()
  override def warning(fmt: java.lang.String, args: Object*) = ()
  override def error(t: Throwable, fmt: java.lang.String, args: Object*) = ()
}

trait AutoBuild extends Build {
  private def loadLibraryProjects(b: File, p: Properties): Seq[Project] = {
    (p.stringPropertyNames.collect {
      case k if k.startsWith("android.library.reference") => k
    }.toList.sortWith { (a,b) => a < b } map { k =>
      val layout = ProjectLayout(b/p(k))
      val pkg = pkgFor(layout.manifest)
      (Project(id=pkg, base=b/p(k)) settings(Plugin.androidBuild ++
        Seq(platformTarget in Android := target(b/p(k)),
          libraryProject in Android := true): _*) enablePlugins
            AndroidPlugin) +:
        loadLibraryProjects(b/p(k), loadProperties(b/p(k)))
    } flatten) distinct
  }
  private def target(basedir: File): String = {
    val props = loadProperties(basedir)
    val path = (Option(System getenv "ANDROID_HOME") orElse
      Option(props get "sdk.dir")) flatMap { p =>
      val f = file(p + File.separator)
      if (f.exists && f.isDirectory)
        Some(p + File.separator)
      else
        None
    } getOrElse {
      sys.error("set ANDROID_HOME or run 'android update project -p %s'"
        format basedir.getCanonicalPath): String
    }
    Option(props getProperty "target") getOrElse {
      val manager = SdkManager.createManager(path, NullLogger)
      val versions = (manager.getTargets map {
        _.getVersion
      } sorted) reverse

      AndroidTargetHash.getPlatformHashString(versions(0))
    }
  }
  private def pkgFor(manifest: File) =
    (XML.loadFile(manifest).attribute("package") get 0 text).replaceAll(
      "\\.", "-")

  override def projects = {

    val projects = super.projects
    if (projects.isEmpty) {
      // TODO search subdirectories to find more complex project structures
      // e.g. root(empty) -> { main-android, library-android }
      val basedir = file(".")
      val layout = ProjectLayout(basedir)
      if (layout.manifest.exists) {

        val props = loadProperties(basedir)
        val libProjects = loadLibraryProjects(basedir, props)

        val project = Project(id=pkgFor(layout.manifest),
          base=basedir).settings(
            Plugin.androidBuild(libProjects: _*) :+
              (platformTarget in Android := target(basedir)):_*) enablePlugins
                AndroidPlugin
        project +: libProjects
      } else Nil
    } else {
      // TODO automatically apply androidBuild with all library/sub projects
      // for now, all main projects have to specify androidBuild(deps) manually
      projects map { p =>
        val layout = ProjectLayout(p.base)
        if (layout.manifest.exists) {
          val settings: Seq[Def.Setting[_]] = p.settings
          val prefix = settings.takeWhile(
            _.key.scope.config.toOption exists (_.name != Android.name))
          val tail = settings.dropWhile(
            _.key.scope.config.toOption exists (_.name != Android.name))
          val platform = platformTarget in Android := target(p.base)
          p.settings(prefix ++ Plugin.androidBuild ++ (platform +: tail): _*)
            .enablePlugins(AndroidPlugin)
        } else p
      }
    }
  }
}
