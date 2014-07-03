package android

import android.Keys.ProjectLayout
import com.android.ddmlib.FileListingService.FileEntry
import com.android.sdklib.{AndroidTargetHash, SdkManager}
import sbt._
import sbt.complete.{Parsers, Parser}
import complete.DefaultParsers._

import scala.collection.JavaConversions._
import scala.util.matching.Regex

import java.io.File

import com.android.ddmlib.{FileListingService, AndroidDebugBridge, IDevice, IShellOutputReceiver}
import com.android.SdkConstants

object Commands {

  val eof = EOF map { _ => "" }
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

  def androidPathParser(entry: FileEntry, fs: FileListingService): Parser[FileEntry] = {
    val children = fs.getChildrenSync(entry)  map { e =>
      e.getName -> e} toMap

    val eof = EOF map {_ => ""}
    if (children.isEmpty) Parser.success(entry) else {
      val completions = children.keys map { e =>
        token(e)
      } toSeq

      Parser.oneOf(eof +: completions) flatMap { e =>
        if (e != "") {
          val entry = children(e)
          Parser.opt("/") ~> androidPathParser(entry, fs)
        } else Parser.success(entry)
      }
    }
  }

  val androidFileParser: State => Parser[(FileEntry,Option[String])] = state => {
    targetDevice(sdkpath(state), state.log) map { d =>
      val fs = d.getFileListingService
      // TODO how do I fix the optional / -- the completion works without...
      // so that segments can run into each other without the / during input
      (EOF map { _ => (fs.getRoot,None) }) |
        ((Space ~> token("/") ~> androidPathParser(fs.getRoot, fs)) ~
          Parser.opt("/" ~> token(Parsers.StringBasic, "")))
    } getOrElse (Parser.failure("No devices connected") map ( _ => null))
  }

  private def printEntry(state: State, entry: FileEntry) {
    val suffix = if (entry.isDirectory) "/"
    else if (entry.getPermissions contains "x") "*"
    else ""
    state.log.info("%8s %8s%12s %s %s %s" format (
      entry.getOwner, entry.getGroup, entry.getSize,
      entry.getDate, entry.getTime, entry.getName + suffix))
  }

  val adbLsAction: (State, (FileEntry,Option[String])) => State = {
    case (state, (entry, name)) =>
      val fixed = name flatMap { n =>
        val n2 = n.dropWhile(_ == '/')
        if (n2.trim.size > 0) Some(n2) else None
      }
      if (entry.isDirectory) {
        fixed map { n =>
          val child = entry.findChild(n)
          if (child != null) {
            printEntry(state, child)
          }
        } getOrElse {
          entry.getCachedChildren foreach (c => printEntry(state, c))
        }
      } else {
        printEntry(state, entry)
      }
      state
  }

  val rebootParser: State => Parser[String] = state => {
    (EOF map (_ => null))| (Space ~> Parser.oneOf(Seq("bootloader", "recovery")))
  }

  val rebootAction: (State, String) => State = (state, mode) => {
    val sdk = sdkpath(state)
    defaultDevice map { s =>
      targetDevice(sdk, state.log) map { _.reboot(mode) }
      state
    } getOrElse sys.error("no device selected")
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
      state

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
      deviceAction(state, ip + ":5555")
    }

  }

  val createProjectParser: State => Parser[Either[Unit, ((String, String), String)]] = state => {
    val manager = SdkManager.createManager(sdkpath(state), NullLogger)
    val targets = Parser.oneOf(manager.getTargets map { t =>
      token(AndroidTargetHash.getPlatformHashString(t.getVersion))
    })

    val idStart = Parser.charClass(Character.isJavaIdentifierStart)
    val id = Parser.charClass(Character.isJavaIdentifierPart)
    val dot = Parser.literal('.')

    val pkgPart = (idStart ~ Parser.zeroOrMore(id) ~ dot ~
      idStart ~ Parser.zeroOrMore(id) ~ Parser.opt(dot)) map {
      case (((((s, p), d), s2), p2), d2) =>
        s + (p mkString "") + d + s2 + (p2 mkString "") + (d2 getOrElse "")
    }
    val pkgName = token(Parser.oneOrMore(pkgPart) map (_ mkString ""), "<package>")
    val name = token((idStart ~ Parser.zeroOrMore(id)) map {
      case (a, b) => a + (b mkString "")
    }, "<name>")

    Parser.choiceParser(EOF,
      (Space ~> targets) ~ (Space ~> pkgName) ~ (Space ~> name)
    )
  }
  // gen-android -p package-name -n project-name -t api
  val createProjectAction: (State, Either[Unit, ((String, String), String)]) => State = {
    case (state, maybe) => {
      val sdk = sdkpath(state)
      maybe.left foreach { _ =>
        state.log.error(
          "Usage: gen-android <platform-target> <package-name> <name>")
      }
      maybe.right foreach {
        case ((target, pkg), name) =>
          val base = file(".")
          val layout = ProjectLayout(base)
          if (layout.manifest.exists) {
            state.log.error(
              "An Android project already exists in this location")
          } else {
            import SdkConstants._
            state.log.info("Creating project: " + name)
            val android = sdk + OS_SDK_TOOLS_FOLDER + androidCmdName
            val p = Seq(android,
              "create", "project",
              "-g", "-v", "0.12.1",
              "-p", ".",
              "-k", pkg,
              "-n", name,
              "-a", "MainActivity",
              "-t", target) !
            val gitignore = base / ".gitignore"
            val ignores = Seq("target/", "project/project",
              "bin/", "local.properties")
            val build = base / "project"
            build.mkdirs()
            val files = Seq("gradlew", "gradlew.bat",
              "build.gradle", "local.properties")
            files foreach (f => (base / f).delete())
            IO.delete(base / "gradle")

            val properties = build / "build.properties"
            val projectBuild = build / "build.scala"
            val projectProperties = base / "project.properties"
            val version = Project.extract(state).get(sbt.Keys.sbtVersion)
            IO.writeLines(properties, "sbt.version=" + version :: Nil)
            IO.writeLines(projectBuild,
              "object Build extends android.AutoBuild" :: Nil)
            IO.writeLines(gitignore, ignores)
            IO.writeLines(projectProperties, "target=" + target :: Nil)
          }
      }
      state
    }
  }
  val logcatParser: State => Parser[String] = state => {
    val anything = Parser.charClass(c => true)
    val str = Parser.oneOrMore(anything) map (_ mkString "")
    eof | (Space ~> str)
  }

  val pidcatAction: (State, String) => State = (state, args) => {
    val LOG_LINE = """^([A-Z])/(.+?)\( *(\d+)\): (.*?)$""".r
    val PID_START = """^Start proc ([a-zA-Z0-9._:]+) for ([a-z]+ [^:]+): pid=(\d+) uid=(\d+) gids=(.*)$""".r
    val PID_KILL = """^Killing (\d+):([a-zA-Z0-9._:]+)/[^:]+: (.*)$""".r
    val PID_LEAVE = """^No longer want ([a-zA-Z0-9._:]+) \(pid (\d+)\): .*$""".r
    val PID_DEATH = """^Process ([a-zA-Z0-9._:]+) \(pid (\d+)\) has died.?$""".r

    val sdk = sdkpath(state)
    val thisProject = Project.extract(state).getOpt(sbt.Keys.thisProjectRef)
    val packageName = thisProject flatMap { prj =>
      Project.extract(state).getOpt(
        Keys.packageName in(prj, Keys.Android))
    }

    val (pkgOpt, tags) = if (args.trim.size > 0) {
      val parts = args.trim.split(" ")
      if (parts.size > 1) {
        (Some(parts(0)), parts.tail.toSeq)
      } else {
        (Some(parts(0)), Seq.empty[String])
      }
    } else (packageName, Seq.empty[String])
    if (pkgOpt.isEmpty)
      sys.error("Usage: pidcat [<partial package-name>] [TAGs]...")

    targetDevice(sdk, state.log) map { d =>
      val receiver = new IShellOutputReceiver() {
        val b = new StringBuilder
        var pids = Set.empty[String]

        def addPid(pkg: String, pid: String) {
          pkgOpt foreach { p => if (pkg contains p) pids += pid.trim}
        }

        def remPid(pkg: String, pid: String) {
          pkgOpt foreach { p => if (pkg contains p) pids -= pid.trim}
        }

        override def addOutput(data: Array[Byte], off: Int, len: Int) =
          b.append(new String(data, off, len))

        override def flush() {
          b.toString.split("\\n").foreach { l =>
            if (l.trim.size > 0) {
              l.trim match {
                case LOG_LINE(level, tag, pid, msg) =>
                  if (tag == "ActivityManager") {
                    msg match {
                      case PID_START(pkg, _, pid2, _, _) => addPid(pkg, pid2)
                      case PID_KILL(pid2, pkg, msg) => remPid(pkg, pid2)
                      case PID_LEAVE(pkg, pid2) => remPid(pkg, pid2)
                      case PID_DEATH(pkg, pid2) => remPid(pkg, pid2)
                      case _ =>
                    }
                  } else if (pids(pid.trim)) {
                    if (tags.isEmpty)
                      state.log.info(l.trim)
                    else if (tags exists tag.contains)
                      state.log.info(l.trim)
                  }
                case _ => state.log.debug(l.trim)
              }
            }
          }
          b.clear
        }

        override def isCancelled = false
      }
      d.executeShellCommand("logcat -d", receiver)
      receiver.flush()

      state
    } getOrElse sys.error("no device connected")
  }

  val logcatAction: (State, String) => State = (state, args) => {
    val sdk = sdkpath(state)
    targetDevice(sdk, state.log) map { d =>
      val receiver = new IShellOutputReceiver() {
        val b = new StringBuilder

        override def addOutput(data: Array[Byte], off: Int, len: Int) =
          b.append(new String(data, off, len))

        override def flush() {
          b.toString.split("\\n").foreach { l =>
            if (l.trim.size > 0)
              state.log.info(l.trim)
          }
          b.clear
        }

        override def isCancelled = false
      }
      d.executeShellCommand("logcat -d " + args, receiver)
      receiver.flush()

      state
    } getOrElse sys.error("no device selected")
  }

  val deviceParser: State => Parser[String] = state => {
    val names: Seq[Parser[String]] = deviceList(state) map (s =>
      token(s.getSerialNumber))

    if (names.isEmpty)
      Space ~> "<no-device>"
    else
      Space ~> Parser.oneOf(names)
  }

  val deviceAction: (State, String) => State = (state, dev) => {
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
    initAdb

    val devices = deviceList(path, log)
    if (devices.isEmpty) {
      sys.error("no devices connected")
    } else {
      defaultDevice flatMap { device =>
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
      Keys.sdkPath in Keys.Android) orElse (
      Option(System getenv "ANDROID_HOME") flatMap { p =>
        val f = file(p)
        if (f.exists && f.isDirectory)
          Some(p + File.separator)
        else
          None: Option[String]
      }) getOrElse sys.error("ANDROID_HOME or sdk.dir is not set")
  }
}
