retrolambdaEnabled := true

libraryDependencies += "com.squareup.dagger" % "dagger-compiler" % "1.2.2" % "provided"

android.apkExclude("META-INF/services/javax.annotation.processing.Processor")
