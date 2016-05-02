package android

import java.io.File

import android.Dependencies.LibraryDependency
import android.Keys.PackagingOptions
import com.android.builder.core.{AndroidBuilder, DexOptions}
import com.android.sdklib.BuildToolInfo
import sbt.{Attributed, Logger}

object Aggregate {
  private[android] case class Retrolambda(enable: Boolean,
                                          classpath: Seq[File],
                                          bootClasspath: Seq[File],
                                          builder: Logger => AndroidBuilder)

  private[android] case class Aapt(builder: Logger => AndroidBuilder,
                                   debug: Boolean,
                                   additionalParams: Seq[String])

  private[android] case class AndroidTest(debugIncludesTests: Boolean,
                                          instrumentTestRunner: String,
                                          instrumentTestTimeout: Int,
                                          apkbuildDebug: Boolean,
                                          debugSigningConfig: ApkSigningConfig,
                                          dexMaxHeap: String,
                                          dexMaxProcessCount: Int,
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

  private[android] case class Ndkbuild(javah: Seq[File],
                                       path: Option[String],
                                       env: Seq[(String,String)],
                                       args: Seq[String])

  private[android] case class Apkbuild(packagingOptions: PackagingOptions,
                                       apkbuildDebug: Boolean,
                                       debugSigningConfig: ApkSigningConfig,
                                       dex: File,
                                       predex: Seq[(File,File)],
                                       collectJni: Seq[File],
                                       resourceShrinker: File,
                                       minSdkVersion: Int)

  private[android] case class Manifest(applicationId: String,
                                       versionName: Option[String],
                                       versionCode: Option[Int],
                                       minSdkVersion: String,
                                       targetSdkVersion: String,
                                       placeholders: Map[String,String],
                                       overlays: Seq[File])

  private[android] case class Dex(inputs: (Boolean,Seq[File]),
                                  maxHeap: String,
                                  maxProcessCount: Int,
                                  multi: Boolean,
                                  mainClassesConfig: File,
                                  minimizeMain: Boolean,
                                  buildTools: BuildToolInfo,
                                  additionalParams: Seq[String]) {
    lazy val incremental = inputs._1 && !multi

    def toDexOptions(incremental: Boolean = incremental) = new DexOptions {
      override def getIncremental: Boolean = incremental
      override def getJavaMaxHeapSize: String = maxHeap
      override def getJumboMode: Boolean = false
      override def getMaxProcessCount: Integer = maxProcessCount
      override def getThreadCount: Integer = Runtime.getRuntime.availableProcessors()
      override def getPreDexLibraries: Boolean = false
      override def getDexInProcess = false
    }
  }

  private[android] case class Proguard(useProguard: Boolean,
                                       useProguardInDebug: Boolean,
                                       managedClasspath: Seq[Attributed[File]],
                                       proguardScala: Boolean,
                                       proguardConfig: Seq[String],
                                       proguardOptions: Seq[String],
                                       proguardCache: Seq[String])

}