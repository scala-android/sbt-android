package android

import java.io.File

import android.Keys.PackagingOptions
import com.android.builder.core.AndroidBuilder
import com.android.sdklib.BuildToolInfo

object Aggregate {
  private[android] case class Retrolambda(enable: Boolean,
                                          classpath: Seq[File],
                                          builder: AndroidBuilder)

  private[android] case class AndroidTest(debugIncludesTests: Boolean,
                                          instrumentTestRunner: String,
                                          instrumentTestTimeout: Int,
                                          apkbuildDebug: Boolean,
                                          dexMaxHeap: String,
                                          externalDependencyClassPathInTest: Seq[File],
                                          externalDependencyClasspathInCompile: Seq[File],
                                          packagingOptions: PackagingOptions,
                                          libraryProject: Boolean)

  private[android] case class Apkbuild(packagingOptions: PackagingOptions,
                                       apkbuildDebug: Boolean,
                                       dex: File,
                                       predex: Seq[(File,File)],
                                       collectJni: Seq[File],
                                       resourceShrinker: File)

  private[android] case class Manifest(applicationId: String,
                                       versionName: Option[String],
                                       versionCode: Option[Int],
                                       minSdkVersion: String,
                                       targetSdkVersion: String,
                                       placeholders: Map[String,String])

  private[android] case class Dex(inputs: (Boolean,Seq[File]),
                                  maxHeap: String,
                                  multi: Boolean,
                                  mainFileClassesConfig: File,
                                  minimizeMainFile: Boolean,
                                  buildTools: BuildToolInfo,
                                  additionalParams: Seq[String])

  private[android] case class Proguard(useProguard: Boolean,
                                       useProguardInDebug: Boolean,
                                       proguardScala: Boolean,
                                       proguardConfig: Seq[String],
                                       proguardOptions: Seq[String],
                                       proguardCache: Seq[String])

}