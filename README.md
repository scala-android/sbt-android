# Android SDK Plugin for SBT #

## Description ##

This is a simple plugin for existing and newly created android projects.

The plugin supports normal android projects and projects that reference
library projects. 3rd party libraries can be included by placing them in
`libs` as in regular projects, or they can be added by using sbt's
`libraryDependencies` feature.

Features not support from the regular android build yet are compiling `AIDL`,
`RenderScript` and `NDK` code. Although `NDK` libraries will be picked up
from `libs` as in typical builds.

## Usage ##

1. Install sbt (https://github.com/harrah/xsbt)
2. `git clone https://pfn@github.com/pfn/android-sdk-plugin.git`
3. `cd android-sdk-plugin && sbt publish-local`
4. Create a new android project using `android create project` or Eclipse
   * Instead of creating a new project, one can also do
     `android update project` to make sure everything is up-to-date.
5. Create a directory named `project` within your project and name it
   `plugins.sbt`, in it, add the following line:
   * `addSbtPlugin("com.hanhuy.sbt" % "android-sdk-build" % "0.1.0")`
6. Create a file named `build.sbt` in the root of your project and add the
   following lines with a blank line between each:
   * `name := YOUR-PROJECT-NAME`
   * `seq(androidBuildSettings: _*)`
7. Now you will be able to run SBT, some available commands in sbt are:
   * `compile`
     * Compiles all the sources in the project, java and scala
     * Compile output is automatically processed through proguard if there
       are any Scala sources, otherwise; it can be enabled manually.
   * `android:package-release`
      * Builds a release APK and signs it with a release key if configured
   * `android:package-debug`
      * Builds a debug APK and signs it using the debug key
   * `android:package`
     * Builds an APK for the project of the last type selected, by default
       `debug`
   * Any task can be repeated continuously whenever any source code changes
     by prefixing the command with a `~`. `~ android:package-debug`
     will continuously build a debug build any time one of the project's
     source files is modified.
8. If you want android-sdk-plugin to automatically sign release packages
   add the following lines to `local.properties` (or any file.properties of
   your choice that you do not check in to source control):
   * `key.alias: YOUR-KEY-ALIAS`
   * `key.store: /path/to/your/.keystore`
   * `key.store.password: YOUR-KEY-PASSWORD`
   * `key.store.type: pkcs12` (optional, defaults to `jks`)

## Advanced Usage ##

* Multi-project builds
  * I'm still working on figuring this out for my own project... it kinda
    works already.
* Configuring `android-sdk-build` by editing build.sbt
  * `import AndroidKeys._` at the top to make sure you can use the plugin's
    configuration options
  * Add configuration options according to the sbt style:
    * `useProguard in Android := true` to enable proguard
  * Configurable keys can be discovered by typing `android:<tab>` at the
    sbt shell
* Configuring proguard, some options are available
  * `proguardOptions in Android += Seq("-dontobfuscate", "-dontoptimize")` -
    will tell proguard not to obfuscute nor optimize code (any valid proguard
    option is usable here)
 * `proguardConfig in Android ...` can be used to replace the entire
   proguard config included with android-sdk-plugin
* Scala applications on android build faster if they're using scala 2.8.1,
  I have found. Set the scala version in `build.sbt` by entering
  `scalaVersion := "2.8.1"`

### TODO ###

* Figure out multi-project support better (kinda works, kinda doesn't) -- it
  works fine for `compile`, but not so much for `package` and the
  `android:package*` tasks
* Implement the missing `AIDL`, `RenderScript` and optionally NDK build
  processes
* Find somewhere to publish the plugin so that one does not need to clone
  and `publish-local` to use it.
