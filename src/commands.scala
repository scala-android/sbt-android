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

    val adb = sdkpath map { path =>
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
    }

    val devices = adb.getDevices
    (if (devices.length == 0) {
      Thread.sleep(1000)
      adb.getDevices
    } else {
      devices filter { _.isOnline }
    }).toSeq
  }

  val devicesAction: State => State = state => {

    val list = deviceList(state)

    if (list.isEmpty) {
      state.log.warn("No devices connected")
    } else {
      state.log.info("Connected devices:")
      list foreach { dev =>
        val name = Option(dev.getAvdName) orElse
          Option(dev.getProperty("ro.build.product")) getOrElse (
            if (dev.isEmulator) "emulator" else "device")

        state.log.info(" %-22s %s" format (dev.getSerialNumber, name))
      }
    }
    state
  }

  val deviceParser: State => Parser[String] = state => {
    val names: Seq[Parser[String]] = deviceList(state) map (s =>
      Parser.stringLiteral(s.getSerialNumber, 0))

    if (names.isEmpty)
      Space ~> "<no-device>"
    else
      Space ~> Parser.oneOf(names)
  }

  val deviceAction: (State,String) => State = (state, dev) => {
    val devices = deviceList(state)

    devices find (_.getSerialNumber == dev) map { dev =>
      val serial = dev.getSerialNumber
      state.log.info("default device: " + serial)
      defaultDevice = Some(serial)
    } getOrElse {
      defaultDevice = None
    }
    state
  }
}
