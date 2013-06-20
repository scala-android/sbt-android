import sbt._
import complete.Parser
import complete.DefaultParsers._

import scala.collection.JavaConversions._

import java.io.File

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.{IDevice, IShellOutputReceiver}
import com.android.SdkConstants

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
    deviceList(sdkpath(state), state.log)

  def deviceList(sdkpath: String, log: Logger): Seq[IDevice] = {
    initAdb

    import SdkConstants._
    val adbPath = sdkpath + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_ADB

    val adb = AndroidDebugBridge.createBridge(adbPath, false)

    val devices = adb.getDevices
    if (devices.length == 0) {
      Thread.sleep(1000)
      adb.getDevices filter { _.isOnline }
    } else {
      devices filter { _.isOnline }
    }
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

        val sel = defaultDevice map { d =>
          if (d == dev.getSerialNumber) "*" else " " } getOrElse " "

        state.log.info(" %s%-22s %s" format (sel, dev.getSerialNumber, name))
      }
    }
    state
  }

  val rebootParser: State => Parser[Any] = state => {
    EOF | (Space ~> Parser.oneOf(Seq("bootloader", "recovery")))
  }

  val adbWifiAction: State => State = state => {
    val sdk = sdkpath(state)
    import SdkConstants._
    val adbPath = sdk + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_ADB

    val adbWifiOn = defaultDevice map { s =>
      (s indexOf ":") > 0
    } getOrElse sys.error("no device selected")

    val receiver = new IShellOutputReceiver {
      val b = new StringBuilder
      var _result = ""
      override def addOutput(data: Array[Byte], off: Int, len: Int) =
        b.append(new String(data, off, len))
      override def flush() {
        _result = b.toString
        b.clear
      }
      override def isCancelled = false
      def result = _result
    }
    val d = targetDevice(sdk, state.log).get
    if (adbWifiOn) {
      state.log.info("turning ADB-over-wifi off")
      d.executeShellCommand("ps | grep -w [a]dbd", receiver)
      val pid = receiver.result.split(" +")(1)
      state.log.debug("current adbd pid: %s" format pid)
      d.executeShellCommand("setprop service.adb.tcp.port 0", receiver)
      d.executeShellCommand("kill %s" format pid, receiver)

    } else {
      state.log.info("turning ADB-over-wifi on")

      d.executeShellCommand("ifconfig wlan0", receiver)
      val ip = receiver.result.split(" +")(2)
      state.log.debug("device ip: %s" format ip)
      d.executeShellCommand("ps | grep -w [a]dbd", receiver)
      val pid = receiver.result.split(" +")(1)
      state.log.debug("current adbd pid: %s" format pid)
      d.executeShellCommand("setprop service.adb.tcp.port 5555", receiver)
      d.executeShellCommand("kill %s" format pid, receiver)

      val r = Seq(adbPath, "connect", ip) !

      if (r != 0)
        sys.error("failed to connect ADB-over-wifi")
    }

    state
  }

  val rebootAction: (State,Any) => State = (state, mode) => {
    val sdk = sdkpath(state)
    defaultDevice map { s =>
      val rebootMode = mode match {
        case m: String => m
        case _ => null
      }
      targetDevice(sdk, state.log) map { d =>
        d.reboot(rebootMode)
      }

      state
    } getOrElse sys.error("no device selected")
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
  def targetDevice(path: String, log: Logger): Option[IDevice] = {
    AndroidCommands.initAdb

    val devices = AndroidCommands.deviceList(path, log)
    if (devices.isEmpty) {
      sys.error("no devices connected")
    } else {
      AndroidCommands.defaultDevice flatMap { device =>
        devices find (device == _.getSerialNumber) orElse {
          log.warn("default device not found, falling back to first device")
          None
        }
      } orElse {
        Some(devices(0))
      }
    }
  }

  private def sdkpath(state: State): String = {
    Project.extract(state).getOpt(
      AndroidKeys.sdkPath in AndroidKeys.Android) orElse (
        Option(System getenv "ANDROID_HOME") flatMap { p =>
          val f = file(p)
          if (f.exists && f.isDirectory)
            Some(p + File.separator)
          else
            None: Option[String]
        }) getOrElse sys.error("ANDROID_HOME or sdk.dir is not set")
  }
}
