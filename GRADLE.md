# Android Gradle Auto-Import Plugin for SBT #

Current version is 1.1.0

## Description ##

Automatically import all settings from an Android project's `build.gradle` into
a generated `00-gradle-generated.sbt` file. Dependencies, flavors, build
types and all Android-related configurations are retained. Any changes to
`build.gradle` are automatically reflected in `00-gradle-generated.sbt`
whenever changes occur.

## Usage ##

1. Download & install SBT 0.13.6+ (google it)
2. From your Android project root, wherever the base `build.gradle` is located,
   load `android-gradle-build`:
   * `mkdir project`
   * `echo 'addSbtPlugin("com.hanhuy.sbt" % "android-gradle-build" % "1.1.0")' > project/plugins.sbt`
   * `echo "object Build extends android.GradleBuild" > project/build.scala"`
3. Run `sbt`
   * The initial load will be slow as gradle will run to extract all
     project settings and export them into sbt
   * Once fully loaded, the full power of android-sdk-plugin is available
   * Typical android projects created by Android Studio have an `app` project,
     so in order to run any any build tasks, they must generally be prefixed by
     `app/`, e.g. `app/android:package`
   * Build types and flavors can be loaded using the `variant` command
     (`variant[/PROJECT] [BUILD-TYPE] [FLAVOR]`)  
     * Build variants can be auto-loaded by adding a line into `build.sbt` such
       as `android.Plugin.withVariant("PROJECT-NAME (e.g. app)", Some("BUILD-TYPE"), Some("FLAVOR"))`
       replace `Some(...)` with `None` if only a build-type or flavor is desired.
4. Load other SBT plugins such as [protify](https://github.com/pfn/protify) to
   further enhance the Android build experience


### Limitations ###

* Custom tasks defined in gradle will not be executed; they will need to be
  re-implemented in SBT if required.
* Inline function calls to populate android settings in gradle will be detected
  and loaded, but will not be updated in SBT until `build.gradle` itself is
  modified
