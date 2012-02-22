import scala.collection.JavaConversions._

import com.android.sdklib.{IAndroidTarget,SdkManager}

import java.util.Properties
import java.io.{File,FilenameFilter,FileInputStream}

import scala.xml.{XML,Elem}

import sbt._
import sbt.Keys._
import AndroidKeys._

object AndroidSdkPlugin extends Plugin {

    private def using[A <: { def close() }](closeable: => A)(f: A => Unit) {
        var c: Option[A] = None
        try {
            c = Option(closeable)
            c foreach f
        } finally
            c foreach(_.close)
    }

    // android build steps
    // * handle library dependencies (android.library.reference.N)
    // * ndk
    // * aidl
    // * renderscript
    // * aapt
    // * compile
    // * obfuscate
    // * dex
    // * png crunch
    // * package resources
    // * package apk
    // * sign

    lazy val androidSdkBuildSettings: Seq[Setting[_]] =
    inConfig(Compile) (Seq(
        scalaSource       <<= baseDirectory (_ / "src"),
        javaSource        <<= baseDirectory (_ / "src"),
        unmanagedBase     <<= baseDirectory (_ / "libs"),
        classDirectory    <<= (binPath in Android) (_ / "classes"),
        sourceGenerators  <+= aaptTask in Android,
        packageConfiguration in packageBin <<= (
                packageConfiguration in packageBin,
                libraryProject in Android,
                classesJar in Android) map { (c, l, j) =>
            if (l) new Package.Configuration(c.sources, j, c.options) else c
        },
        Keys.`package`    <<= Keys.`package` dependsOn(pngCrunch in Android),
        javacOptions      <<= (androidJar in Android) (j =>
            Seq("-bootclasspath", j, "-deprecation")
        ),
        scalacOptions     <<= (androidJar in Android) map (j =>
            Seq("-bootclasspath", j, "-javabootclasspath", j)
        )
    )) ++ inConfig(Android) (Seq(
        binPath           <<= baseDirectory (_ / "bin"),
        classesJar        <<= binPath (_ / "classes.jar"),
        aaptTask          <<= aaptTaskDef,
        pngCrunch         <<= pngCrunchTaskDef,
        pngCrunch         <<= pngCrunch dependsOn(compile in Compile),
        genPath           <<= baseDirectory (_ / "gen"),
        libraryProjects   <<= projectProperties { p =>
            p.stringPropertyNames.collect {
                case k if k.startsWith("android.library.reference") => k
            }.toList.sortWith { (a,b) => a < b } map { k => p(k) }
        },
        libraryProject    <<= projectProperties { p =>
            Option(p.getProperty("android.library")) map {
                    _.equals("true") } getOrElse false },
        aaptPath          <<= androidTarget (
                // .AAPT is deprecated, replacement?
                _.getPath(IAndroidTarget.AAPT)),
        androidJar        <<= androidTarget (
                _.getPath(IAndroidTarget.ANDROID_JAR)),
        manifestPath      <<= baseDirectory (_ / "AndroidManifest.xml"),
        projectProperties <<= baseDirectory (b => loadProperties(b)),
        androidManifest   <<= manifestPath { m => XML.loadFile(m) },
        androidPackage    <<= androidManifest { m =>
            m.attribute("package") get (0) text
        },
        sdkPath           <<= projectProperties (_("sdk.dir") + File.separator),
        sdkManager        <<= sdkPath (p => SdkManager.createManager(p, null)),
        androidTarget     <<= (sdkManager, projectProperties) { (m, p) =>
            m.getTargetFromHashString(p("target"))
        }
    )) ++ Seq(
        cleanFiles <+= binPath in Android,
        cleanFiles <+= genPath in Android
    )

    private def aaptTaskDef = (manifestPath, baseDirectory, androidPackage,
            androidJar, genPath, aaptPath, libraryProjects) map {
        (m, b, p, j, g, a, l) =>
        g.mkdirs()
        val libraryResources = for (r <- l;
                arg <- Seq("-S", (b/r/"res").getCanonicalPath))
                    yield arg
        // TODO handle the generated dependency file?
        // TODO fail on error
        Seq(a, "package",
            "--auto-add-overlay", // what's this for? from android-plugin
            "-m", // make package directories in gen
            "--generate-dependencies",
            "--custom-package", p,
            "-M", m.absolutePath,
            "-S", (b / "res").absolutePath,
            "-I", j,
            "-J", g.absolutePath) ++ libraryResources !

        g ** "R.java" get
    }

    private def pngCrunchTaskDef = (aaptPath, binPath, baseDirectory) map {
        (a, bin, base) =>
        Seq(a, "crunch", "-v",
            "-S", (base / "res").absolutePath,
            "-C", (bin / "res").absolutePath) !

        ()
    }

    private def loadProperties(path: File): Properties = {
        val p = new Properties
        (path * "*.properties" get) foreach { f =>
            using(new FileInputStream(f)) { in => p.load(in) }
        }
        p
    }
}

object AndroidKeys {
    val pngCrunch = TaskKey[Unit]("png-crunch", "optimize png files")
    val androidPackage = SettingKey[String]("package-name",
            "android package name")
    val manifestPath = SettingKey[File]("manifest-path",
            "android manifest file path")
    val androidManifest = SettingKey[Elem]("manifest",
            "android manifest xml object")
    val classesJar = SettingKey[File]("classes-jar",
            "generated classes.jar file if in a library project")
    val libraryProjects = SettingKey[Seq[String]]("library-projects",
            "android library projects to reference, must be built separately")
    val libraryProject = SettingKey[Boolean]("library-project",
            "flag indicating whether or not a library project")
    val binPath = SettingKey[File]("bin-path",
            "android compiled bin path")
    val genPath = SettingKey[File]("gen-path",
            "android generated code path")
    val aaptPath = SettingKey[String]("aapt-path", "path to aapt")
    val projectProperties = SettingKey[Properties]("properties",
            "Properties loaded from the project's .property files")
    val sdkPath = SettingKey[String]("sdk-path",
            "Path to the Android SDK")
    val sdkManager = SettingKey[SdkManager]("sdk-manager",
            "Android SdkManager object")
    val androidTarget = SettingKey[IAndroidTarget]("platform",
            "IAndroidTarget object representing a target API level")
    val androidJar = SettingKey[String]("platform-jar", "Path to android.jar")
    val aaptTask = TaskKey[Seq[File]]("aapt", "android aapt task")

    val Android = config("android")
}
