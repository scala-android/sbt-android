android.Plugin.androidBuild

javacOptions in Compile ++= "-source" :: "1.7" :: "-target" :: "1.7" :: Nil

SettingKey[Option[File]]("sbt-structure-output-file") in Global := Some(baseDirectory.value / "structure.xml")

SettingKey[String]("sbt-structure-options") in Global := "download prettyPrint"
