# Build Android Gradle Projects Using SBT #

[![Join the chat at https://gitter.im/scala-android/sbt-android](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/scala-android/sbt-android?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Current version is 1.2.2

NOTE: 1.2.0 is the last version published using
`addSbtPlugin("com.hanhuy.sbt" % "android-gradle-build" % "1.2.0")`,
all future updates can be accessed by using
`addSbtPlugin("org.scala-android" % "sbt-android-gradle" % VERSION)`

## Description ##

Automatically import all settings from an Android project's `build.gradle` into
a generated `00-gradle-generated.sbt` file. Dependencies, flavors, build
types and all Android-related configurations are retained. Any changes to
`build.gradle` are automatically reflected in `00-gradle-generated.sbt`
whenever changes occur.

## Usage ##

1. Download & install SBT 0.13.6+ (google it)
2. From your Android project root, wherever the base `build.gradle` is located,
   load `sbt-android-gradle`:
   * `mkdir project`
   * `echo 'addSbtPlugin("org.scala-android" % "sbt-android-gradle" % "1.2.2")' > project/plugins.sbt`
3. Run `sbt`
   * The initial load will be slow as gradle will run to extract all
     project settings and export them into sbt
   * Once fully loaded, the full power of `sbt-android` is available
   * Typical android projects created by Android Studio have an `app` project,
     so in order to run any any build tasks, they must generally be prefixed by
     `app/`, e.g. `app/android:package`
   * Build types and flavors can be loaded using the `variant` command
     (`variant[/PROJECT] [BUILD-TYPE] [FLAVOR]`)  
     * Build variants can be auto-loaded by adding a line into `build.sbt` such
       as `android.Plugin.withVariant("PROJECT-NAME (e.g. app)", Some("BUILD-TYPE"), Some("FLAVOR"))`
       replace `Some(...)` with `None` if only a build-type or flavor is desired.
     * By default, the first found build type and flavor will be loaded.
     * To select an alternative flavor/buildType at sbt file generation,
       set `build.flavor` and/or `build.type` properties in a properties file
       of your choosing.
     * Gradle command line options can be passed by setting `gradle.options` in
       any properties file of your choosing
4. Load other SBT plugins such as [sbt-android-protify](https://github.com/scala-android/sbt-android-protify) to
   further enhance the build experience


### Limitations ###

* Custom tasks defined in gradle will not be executed; they will need to be
  re-implemented in SBT if required.
* Inline function calls to populate android settings in gradle will be detected
  and loaded, but will not be updated in SBT until `build.gradle` itself is
  modified
* Only settings defined by the gradle android plugin proper will be imported.
  Certain settings, such as `dexOptions` are not exported to tooling and will
  be unavailable. `proguardFiles` is similarly unavailable due to
  [bug 195881](https://code.google.com/p/android/issues/detail?id=195881).
  Settings from plugins such as `retrolambda`, `android-apt`, etc. will not be
  imported.
* Only Android projects will be imported, any non-android gradle projects will
  be ignored
