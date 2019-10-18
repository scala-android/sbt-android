import android.Keys._

android.Plugin.androidBuild

platformTarget in Android := "android-17"

minSdkVersion in Android := "8"

targetSdkVersion in Android := "19"

name := "hello-multidex"

val googleMaven = "google-maven-repo" at "https://maven.google.com/"

resolvers ++= Seq(Resolver.jcenterRepo, googleMaven)

libraryDependencies ++= Seq(
  aar("com.android.support" % "multidex" % "1.0.3"),
  aar("com.google.android.gms" % "play-services" % "4.0.30"),
  aar("com.android.support" % "support-v4" % "20.0.0"),
  "com.google.code.gson" % "gson" % "2.8.5"
)

useProguard in Android := false

useProguardInDebug in Android := false

proguardScala in Android := false

dexMulti in Android := true

dexMinimizeMain in Android := true

dexMaxProcessCount := 1

dexMainClasses in Android := Seq(
  "com/example/app/MultidexApplication.class",
  "android/support/multidex/BuildConfig.class",
  "android/support/multidex/MultiDex$V14.class",
  "android/support/multidex/MultiDex$V19.class",
  "android/support/multidex/MultiDex$V4.class",
  "android/support/multidex/MultiDex.class",
  "android/support/multidex/MultiDexApplication.class",
  "android/support/multidex/MultiDexExtractor$1.class",
  "android/support/multidex/MultiDexExtractor.class",
  "android/support/multidex/ZipUtil$CentralDirectory.class",
  "android/support/multidex/ZipUtil.class"
)

packagingOptions in Android := PackagingOptions(excludes = Seq(
  "META-INF/MANIFEST.MF",
  "META-INF/LICENSE.txt",
  "META-INF/LICENSE",
  "META-INF/NOTICE.txt",
  "META-INF/NOTICE"
))

javacOptions in Compile ++= Seq("-source", "1.6", "-target", "1.6")

showSdkProgress in Android := false
