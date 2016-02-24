package android

import java.io.File

import android.Dependencies.LibraryDependency
import android.Keys.PackagingOptions
import com.android.builder.core.AndroidBuilder
import com.android.sdklib.BuildToolInfo

object Aggregate {
  private[android] case class Retrolambda(enable: Boolean,
                                          classpath: Seq[File],
                                          bootClasspath: Seq[File],
                                          builder: AndroidBuilder)

  private[android] case class AndroidTest(debugIncludesTests: Boolean,
                                          instrumentTestRunner: String,
                                          instrumentTestTimeout: Int,
                                          apkbuildDebug: Boolean,
                                          debugSigningConfig: ApkSigningConfig,
                                          dexMaxHeap: String,
                                          externalDependencyClassPathInTest: Seq[File],
                                          externalDependencyClasspathInCompile: Seq[File],
                                          packagingOptions: PackagingOptions,
                                          libraryProject: Boolean)

  private[android] case class CollectResources(libraryProject: Boolean,
                                               libraryProjects: Seq[LibraryDependency],
                                               extraResDirectories: Seq[File],
                                               extraAssetDirectories: Seq[File],
                                               projectLayout: ProjectLayout,
                                               outputLayout: BuildOutput.Converter)

  private[android] case class Apkbuild(packagingOptions: PackagingOptions,
                                       apkbuildDebug: Boolean,
                                       debugSigningConfig: ApkSigningConfig,
                                       dex: File,
                                       predex: Seq[(File,File)],
                                       collectJni: Seq[File],
                                       resourceShrinker: File)

  private[android] case class Manifest(applicationId: String,
                                       versionName: Option[String],
                                       versionCode: Option[Int],
                                       minSdkVersion: String,
                                       targetSdkVersion: String,
                                       placeholders: Map[String,String],
                                       overlays: Seq[File])

  private[android] case class Dex(inputs: (Boolean,Seq[File]),
                                  maxHeap: String,
                                  multi: Boolean,
                                  mainClassesConfig: File,
                                  minimizeMain: Boolean,
                                  buildTools: BuildToolInfo,
                                  additionalParams: Seq[String])

  private[android] case class Proguard(useProguard: Boolean,
                                       useProguardInDebug: Boolean,
                                       proguardScala: Boolean,
                                       proguardConfig: Seq[String],
                                       proguardOptions: Seq[String],
                                       proguardCache: Seq[String])

}