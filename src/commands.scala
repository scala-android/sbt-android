import sbt._
import complete.Parser
import complete.DefaultParsers._

import scala.collection.JavaConversions._

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.sdklib.SdkConstants

object AndroidCommands {

  var defaultDevice: Option[String] = None

  lazy val initAdb = {
    AndroidDebugBridge.init(false)
    java.lang.Runtime.getRuntime.addShutdownHook(new Thread(new Runnable() {
      override def run = AndroidDebugBridge.terminate()
    }))
    ()
  }

  def deviceList(state: State): Seq[IDevice] =
    deviceList(Project.extract(state).getOpt(
      AndroidKeys.sdkPath in AndroidKeys.Android), state.log)

  def deviceList(sdkpath: Option[String], log: Logger): Seq[IDevice] = {
    initAdb

    (sdkpath map { path =>
      import SdkConstants._
      val adbPath = path + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_ADB

      AndroidDebugBridge.createBridge(adbPath, false)
    } getOrElse {
      val adb = AndroidDebugBridge.createBridge()

      while (!adb.isConnected) {
        Thread.sleep(1000)
        if (!adb.isConnected) {
          log.warn(
            "adb is not connected, run 'adb start-server' manually")
        }
      }

      adb
    }).getDevices.toSeq
  }

  val devicesAction: State => State = state => {
    initAdb

    val list = deviceList(state)

    if (list.isEmpty) {
      state.log.warn("No devices connected")
    } else {
      state.log.info("Connected devices:")
      list foreach { dev =>
        val name = Option(dev.getAvdName) getOrElse "device"
        state.log.info("%24s%s" format (dev.getSerialNumber, name))
      }
    }
    state
  }

  val deviceParser: State => Parser[String] = state => {
    initAdb
    val names: Seq[Parser[String]] = Seq("a","b","c","d")

    Space ~> Parser.oneOf(names)
  }

  val deviceAction: (State,String) => State = (state, dev) => {
    val project = Project.extract(state)
    val compile = project.get(Keys.compile in Compile)
    project.runTask(Keys.compile in Compile, state)

    state.log.info("selected device: " + dev)

    defaultDevice = Some(dev)
    state
  }
}
