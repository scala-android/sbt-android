lazy val root = project.in(file(".")).settings(androidBuild:_*)
lazy val flavor1 = android.Plugin.flavorOf(root, "flavor1",
  debugIncludesTests in Android := true,
  libraryDependencies ++=
    "com.android.support.test" % "runner" % "0.2" ::
      "com.android.support.test.espresso" % "espresso-core" % "2.1" ::
      Nil,
  instrumentTestRunner in Android :=
    "android.support.test.runner.AndroidJUnitRunner",
  packagingOptions in Android := PackagingOptions(excludes = Seq("LICENSE.txt"))
)

debugIncludesTests in Android := false

autoScalaLibrary := false
