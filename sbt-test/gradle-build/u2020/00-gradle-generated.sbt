// AUTO-GENERATED SBT FILE, DO NOT MODIFY

val u2020 = Project(id = "u2020", base = file("C:\\Users\\pfnguyen\\src\\u2020")).settings(
  android.Plugin.androidBuild:_*).settings(
    SettingKey[android.Keys.PackagingOptions]("packagingOptions") in config("android") := android.Keys.PackagingOptions(Seq("LICENSE.txt","LICENSE"), Nil, Nil),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.jakewharton" % "process-phoenix" % "1.0.2" artifacts(Artifact("process-phoenix", "aar", "aar")) intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.android.support" % "appcompat-v7" % "23.0.1" artifacts(Artifact("appcompat-v7", "aar", "aar")) intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.android.support" % "recyclerview-v7" % "23.0.1" artifacts(Artifact("recyclerview-v7", "aar", "aar")) intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.mattprecious.telescope" % "telescope" % "1.5.0" artifacts(Artifact("telescope", "aar", "aar")) intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.f2prateek.rx.preferences" % "rx-preferences" % "1.0.0" artifacts(Artifact("rx-preferences", "aar", "aar")) intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.squareup.leakcanary" % "leakcanary-android" % "1.3.1" artifacts(Artifact("leakcanary-android", "aar", "aar")) intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.android.support" % "support-v4" % "23.0.1" artifacts(Artifact("support-v4", "aar", "aar")) intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "io.reactivex" % "rxandroid" % "1.0.1" artifacts(Artifact("rxandroid", "aar", "aar")) intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.jakewharton.rxbinding" % "rxbinding" % "0.2.0" artifacts(Artifact("rxbinding", "aar", "aar")) intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.jakewharton.timber" % "timber" % "4.0.1" artifacts(Artifact("timber", "aar", "aar")) intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.android.support" % "design" % "23.0.1" artifacts(Artifact("design", "aar", "aar")) intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.jakewharton.threetenabp" % "threetenabp" % "1.0.2" artifacts(Artifact("threetenabp", "aar", "aar")) intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.jakewharton.madge" % "madge" % "1.1.2" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.squareup.okhttp" % "okhttp" % "2.5.0" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.squareup.haha" % "haha" % "1.3" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.squareup.dagger" % "dagger" % "1.2.2" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.squareup.leakcanary" % "leakcanary-watcher" % "1.3.1" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.squareup.leakcanary" % "leakcanary-analyzer" % "1.3.1" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "org.threeten" % "threetenbp" % "1.3" classifier("no-tzdb") intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.squareup.okio" % "okio" % "1.6.0" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "javax.inject" % "javax.inject" % "1" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.squareup.retrofit" % "retrofit" % "2.0.0-beta2" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.squareup.retrofit" % "adapter-rxjava-mock" % "2.0.0-beta2" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.jakewharton.scalpel" % "scalpel" % "1.1.2" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.jakewharton.byteunits" % "byteunits" % "0.9.1" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.squareup.retrofit" % "adapter-rxjava" % "2.0.0-beta2" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.jakewharton" % "butterknife" % "7.0.1" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "io.reactivex" % "rxjava" % "1.0.14" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.squareup.moshi" % "moshi" % "1.0.0" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.squareup.retrofit" % "retrofit-mock" % "2.0.0-beta2" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.android.support" % "support-annotations" % "23.0.1" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.squareup.retrofit" % "converter-moshi" % "2.0.0-beta2" intransitive(),
    SettingKey[scala.collection.Seq[sbt.ModuleID]]("libraryDependencies") += "com.squareup.picasso" % "picasso" % "2.5.2" intransitive(),
    SettingKey[scala.collection.Seq[sbt.Resolver]]("resolvers") ++= List("https://repo1.maven.org/maven2/" at "https://repo1.maven.org/maven2/",
      "file:/C:/Users/pfnguyen/android-sdk-windows/extras/android/m2repository/" at "file:/C:/Users/pfnguyen/android-sdk-windows/extras/android/m2repository/",
      "file:/C:/Users/pfnguyen/android-sdk-windows/extras/google/m2repository/" at "file:/C:/Users/pfnguyen/android-sdk-windows/extras/google/m2repository/"),
    SettingKey[java.lang.String]("platformTarget") in config("android") := "android-23",
    SettingKey[java.lang.String]("name") := "u2020",
    TaskKey[scala.collection.Seq[java.lang.String]]("javacOptions") in config("compile") ++= List("-source",
      "1.8",
      "-target",
      "1.8"),
    SettingKey[Boolean]("debugIncludesTests") in config("android") := false,
    SettingKey[android.ProjectLayout]("projectLayout") in config("android") := 
      new ProjectLayout.Wrapped(ProjectLayout(baseDirectory.value)) {
        override def base = file("C:\\Users\\pfnguyen\\src\\u2020")
        override def resources = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\main\\resources")
        override def testSources = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\androidTest")
        override def sources = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\main")
        override def javaSource = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\main\\java")
        override def libs = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\main\\libs")
        override def gen = file("C:\\Users\\pfnguyen\\src\\u2020\\target\\android\\generated\\source")
        override def testRes = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\androidTest\\res")
        override def manifest = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\main\\AndroidManifest.xml")
        override def testManifest = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\main\\AndroidManifest.xml")
        override def scalaSource = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\main\\scala")
        override def aidl = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\main\\aidl")
        override def bin = file("C:\\Users\\pfnguyen\\src\\u2020\\target\\android")
        override def renderscript = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\main\\rs")
        override def testScalaSource = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\androidTest\\scala")
        override def testAssets = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\androidTest\\assets")
        override def jni = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\main\\jni")
        override def assets = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\main\\assets")
        override def testJavaSource = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\androidTest\\java")
        override def jniLibs = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\main\\jniLibs")
        override def res = file("C:\\Users\\pfnguyen\\src\\u2020\\src\\main\\res")
      },
    TaskKey[scala.collection.Seq[scala.Tuple3[java.lang.String,java.lang.String,java.lang.String]]]("buildConfigOptions") in config("android") ++= List(("String", "BUILD_TIME", "\"2015-11-16T19:34:50Z\""),
      ("String", "GIT_SHA", "\"90610dd\"")),
    TaskKey[scala.collection.Seq[scala.Tuple3[java.lang.String,java.lang.String,java.lang.String]]]("resValues") in config("android") ++= Nil,
    TaskKey[scala.collection.Seq[java.lang.String]]("proguardOptions") in config("android") ++= Nil,
    TaskKey[scala.collection.immutable.Map[java.lang.String,java.lang.String]]("manifestPlaceholders") in config("android") ++= Map(),
    TaskKey[scala.Option[Int]]("versionCode") in config("android") := Some(10000),
    TaskKey[scala.Option[java.lang.String]]("versionName") in config("android") := Some("1.0.0"),
    TaskKey[java.lang.String]("minSdkVersion") in config("android") := "15",
    TaskKey[java.lang.String]("targetSdkVersion") in config("android") := "23"
).settings(
  buildTypes += (("debug", List(
    TaskKey[scala.collection.Seq[scala.Tuple3[java.lang.String,java.lang.String,java.lang.String]]]("buildConfigOptions") in config("android") ++= Nil,
    TaskKey[scala.collection.Seq[scala.Tuple3[java.lang.String,java.lang.String,java.lang.String]]]("resValues") in config("android") ++= Nil,
    TaskKey[scala.collection.Seq[java.lang.String]]("proguardOptions") in config("android") ++= Nil,
    TaskKey[scala.collection.immutable.Map[java.lang.String,java.lang.String]]("manifestPlaceholders") in config("android") ++= Map(),
    SettingKey[android.Keys.MutableSetting[Boolean]]("apkbuildDebug") in config("android") := 
      {
        val debug = apkbuildDebug.value
        debug(true)
        debug
      }
        ,
    SettingKey[Int]("rsOptimLevel") in config("android") := 3,
    SettingKey[Boolean]("useProguardInDebug") in config("android") := false,
    TaskKey[java.lang.String]("applicationId") in config("android") := applicationId.value + ".debug",
    SettingKey[scala.collection.Seq[java.io.File]]("unmanagedSourceDirectories") in config("compile") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\debug\\java"),
    SettingKey[scala.collection.Seq[java.io.File]]("extraResDirectories") in config("android") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\debug\\res"),
    SettingKey[scala.collection.Seq[java.io.File]]("resourceDirectories") in config("compile") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\debug\\resources"),
    SettingKey[scala.collection.Seq[java.io.File]]("extraAssetDirectories") in config("android") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\debug\\assets"))))
      ).settings(
  buildTypes += (("release", List(
    TaskKey[scala.collection.Seq[scala.Tuple3[java.lang.String,java.lang.String,java.lang.String]]]("buildConfigOptions") in config("android") ++= Nil,
    TaskKey[scala.collection.Seq[scala.Tuple3[java.lang.String,java.lang.String,java.lang.String]]]("resValues") in config("android") ++= Nil,
    TaskKey[scala.collection.Seq[java.lang.String]]("proguardOptions") in config("android") ++= Nil,
    TaskKey[scala.collection.immutable.Map[java.lang.String,java.lang.String]]("manifestPlaceholders") in config("android") ++= Map(),
    SettingKey[android.Keys.MutableSetting[Boolean]]("apkbuildDebug") in config("android") := 
      {
        val debug = apkbuildDebug.value
        debug(false)
        debug
      }
        ,
    SettingKey[Int]("rsOptimLevel") in config("android") := 3,
    SettingKey[Boolean]("useProguard") in config("android") := false,
    SettingKey[scala.collection.Seq[java.io.File]]("unmanagedSourceDirectories") in config("compile") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\release\\java"),
    SettingKey[scala.collection.Seq[java.io.File]]("extraResDirectories") in config("android") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\release\\res"),
    SettingKey[scala.collection.Seq[java.io.File]]("resourceDirectories") in config("compile") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\release\\resources"),
    SettingKey[scala.collection.Seq[java.io.File]]("extraAssetDirectories") in config("android") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\release\\assets"))))
      ).settings(
  flavors += (("internal", List(
    TaskKey[scala.collection.Seq[scala.Tuple3[java.lang.String,java.lang.String,java.lang.String]]]("buildConfigOptions") in config("android") ++= Nil,
    TaskKey[scala.collection.Seq[scala.Tuple3[java.lang.String,java.lang.String,java.lang.String]]]("resValues") in config("android") ++= Nil,
    TaskKey[scala.collection.Seq[java.lang.String]]("proguardOptions") in config("android") ++= Nil,
    TaskKey[scala.collection.immutable.Map[java.lang.String,java.lang.String]]("manifestPlaceholders") in config("android") ++= Map(),
    TaskKey[java.lang.String]("applicationId") in config("android") := "com.jakewharton.u2020.internal",
    SettingKey[scala.collection.Seq[java.io.File]]("unmanagedSourceDirectories") in config("compile") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\internal\\java"),
    SettingKey[scala.collection.Seq[java.io.File]]("extraResDirectories") in config("android") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\internal\\res"),
    SettingKey[scala.collection.Seq[java.io.File]]("resourceDirectories") in config("compile") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\internal\\resources"),
    SettingKey[scala.collection.Seq[java.io.File]]("extraAssetDirectories") in config("android") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\internal\\assets"))))
      ).settings(
  flavors += (("production", List(
    TaskKey[scala.collection.Seq[scala.Tuple3[java.lang.String,java.lang.String,java.lang.String]]]("buildConfigOptions") in config("android") ++= Nil,
    TaskKey[scala.collection.Seq[scala.Tuple3[java.lang.String,java.lang.String,java.lang.String]]]("resValues") in config("android") ++= Nil,
    TaskKey[scala.collection.Seq[java.lang.String]]("proguardOptions") in config("android") ++= Nil,
    TaskKey[scala.collection.immutable.Map[java.lang.String,java.lang.String]]("manifestPlaceholders") in config("android") ++= Map(),
    TaskKey[java.lang.String]("applicationId") in config("android") := "com.jakewharton.u2020",
    SettingKey[scala.collection.Seq[java.io.File]]("unmanagedSourceDirectories") in config("compile") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\production\\java"),
    SettingKey[scala.collection.Seq[java.io.File]]("extraResDirectories") in config("android") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\production\\res"),
    SettingKey[scala.collection.Seq[java.io.File]]("resourceDirectories") in config("compile") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\production\\resources"),
    SettingKey[scala.collection.Seq[java.io.File]]("extraAssetDirectories") in config("android") += file("C:\\Users\\pfnguyen\\src\\u2020\\src\\production\\assets"))))
      )

android.Plugin.withVariant("u2020", Some("debug"), Some("internal"))
       