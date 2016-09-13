enablePlugins(AndroidApp)

showSdkProgress := false

javacOptions in Compile ++= "-source" :: "1.7" :: "-target" :: "1.7" :: Nil
