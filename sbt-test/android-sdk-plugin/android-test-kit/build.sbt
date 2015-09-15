debugIncludesTests in Android := false

debugIncludesTests in (flavor1,Android) := true

instrumentTestRunner in (flavor1,Android) :=
  "android.support.test.runner.AndroidJUnitRunner"

libraryDependencies in flavor1 ++=
  "com.android.support.test" % "runner" % "0.2" ::
  "com.android.support.test.espresso" % "espresso-core" % "2.1" ::
  Nil

packagingOptions in (flavor1,Android) := PackagingOptions(excludes = Seq("LICENSE.txt"))

autoScalaLibrary := false
