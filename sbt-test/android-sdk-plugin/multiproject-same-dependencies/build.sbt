javacOptions in Global ++= List("-source", "1.7", "-target", "1.7")

val b = project.settings(androidBuildAar:_*).settings(platformTarget := "android-23")

val c = project.settings(androidBuildAar:_*).settings(platformTarget := "android-23")

val d = project.settings(androidBuildAar:_*).settings(platformTarget := "android-23")

val a = project.androidBuildWith(b,c,d).settings(platformTarget := "android-23")

libraryDependencies in b += "com.android.support" % "appcompat-v7" % "23.1.1"

libraryDependencies in c += "com.android.support" % "appcompat-v7" % "23.1.1"

libraryDependencies in d += "com.android.support" % "appcompat-v7" % "23.1.1"

