package android

import java.util.Locale

import com.android.ddmlib.FileListingService.FileEntry
import com.android.sdklib.{AndroidTargetHash, SdkManager}
import sbt._
import sbt.complete.{Parsers, Parser}
import complete.DefaultParsers._

import java.io.File

import com.android.ddmlib._
import com.android.SdkConstants

import scala.util.Try

object Commands {

  var defaultDevice: Option[String] = None

  lazy val initAdb = {
    AndroidDebugBridge.init(false)
    java.lang.Runtime.getRuntime.addShutdownHook(new Thread(new Runnable() {
      override def run() = AndroidDebugBridge.terminate()
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

  val fileSep = token(charClass(c => c == '/' || c == '\\'), "/")
  def localPathParser(entry: File): Parser[File] = {
    if (entry.isFile) Parser.success(entry) else {
      val children = (Option(entry.listFiles) map { c =>
        entry / ".." +: c map { e =>
          e.getName -> e
        }
      } getOrElse Array.empty) toMap

      if (children.isEmpty) {
        success(entry)
      } else {
        val completions = children.keys map { e =>
          token(e)
        } toSeq

        val eof = EOF map { _ => entry}
        val dot = literal(".") map { _ => entry }
        dot | eof | (Parser.oneOf(completions) flatMap { e =>
          val entry = children(e)
          val eof = EOF map { _ => entry}
          eof | (fileSep ~> Parser.opt(localPathParser(entry)) map (
            _ getOrElse entry))
        })
      }
    }
  }

  lazy val isWindows = System.getProperty("os.name", "").toLowerCase(
    Locale.ENGLISH).contains("windows")

  val rootPathParser: Parser[File] = {
    val drives = Parser.charClass { c =>
      val c2 = c.toLower
      c2 > 'a' && c2 < 'z'
    }

    val root = fileSep map ( _ => file("/") )
    (if (isWindows) {
      root | token((drives ~ ":" ~ fileSep) map {
        case ((d,_),_) => file(d + ":/") })
    } else {
      root
    }) flatMap localPathParser
  }
  val localFileParser: State => Parser[File] = state => {

    ((EOF map { _ => (file("."), None) }) |
      (Space ~> (rootPathParser | localPathParser(file("."))) ~
        Parser.opt(fileSep ~> token(Parsers.StringBasic, "")))) map {
      case (a, b) =>
        b map { f => new File(a, f) } getOrElse a
    }
  }

  def androidPathParser(entry: FileEntry, fs: FileListingService): Parser[FileEntry] = {
    val children = fs.getChildrenSync(entry)  map { e =>
      e.getName -> e} toMap

    if (children.isEmpty) Parser.success(entry) else {
      val completions = children.keys map { e =>
        token(e)
      } toSeq

      val eof = EOF map {_ => entry}
      eof | (Parser.oneOf(completions) flatMap { e =>
        val entry = children(e)
        val eof = EOF map {_ => entry}
        eof | ("/" ~> Parser.opt(androidPathParser(entry, fs)) map (
          _ getOrElse entry))
      })
    }
  }

  val androidFileParser: State => Parser[(FileEntry,Option[String])] = state => {
    Try(targetDevice(sdkpath(state), state.log)).toOption.flatten map { d =>
      val fs = d.getFileListingService
      (EOF map { _ => (fs.getRoot,None) }) |
        ((Space ~> token("/") ~> androidPathParser(fs.getRoot, fs)) ~
          Parser.opt("/" ~> token(Parsers.StringBasic, "")))
    } getOrElse (Parser.failure("No devices connected") map ( _ => null))
  }

  private def printEntry(state: State, entry: FileEntry) {
    val suffix = if (entry.isDirectory) "/"
    else if (entry.getPermissions contains "x") "*"
    else ""
    state.log.info("%-8s %-8s %10s %s %s %s" format (
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
  val adbPushParser: State => Parser[(File,(FileEntry,Option[String]))] =
    state => localFileParser(state) ~ androidFileParser(state)

  val adbPullParser: State => Parser[((FileEntry,Option[String]),File)] =
    state => androidFileParser(state) ~ localFileParser(state)

  val adbPullAction: (State, ((FileEntry,Option[String]), File)) => State = {
    case (state, ((entry, name), f)) =>
      val target = name map {
        entry.getFullPath + "/" + _ } getOrElse entry.getFullPath
      state.log.info("Pulling [%s] to [%s]" format (target, f))
      targetDevice(sdkpath(state), state.log) map { d =>
        val dest = if (f.isDirectory) {
          val x = target.lastIndexOf("/")
          val n = if (x < 0) {
            target
          } else {
            target.substring(x)
          }
          new File(f, n)
        } else f
        d.pullFile(target, dest.getAbsolutePath)
      } getOrElse (Parser.failure("No devices connected") map ( _ => null))
      state
  }

  val adbPushAction: (State, (File,(FileEntry,Option[String]))) => State = {
    case (state, (f, (entry, name))) =>
      val target = name map {
        entry.getFullPath + "/" + _ } getOrElse entry.getFullPath
      state.log.info("Pushing [%s] to [%s]" format (f, target))
      if (!f.isFile)
        sys.error(f + " is not a file")
      targetDevice(sdkpath(state), state.log) map { d =>
        d.pushFile(f.getAbsolutePath, target)
      } getOrElse (Parser.failure("No devices connected") map ( _ => null))
      state
  }

  val adbPowerAction: State => State = { state =>
    val sdk = sdkpath(state)

    val nullRecv = new IShellOutputReceiver {
      override def isCancelled = false
      override def addOutput(data: Array[Byte], offset: Int, length: Int) = ()
      override def flush() = ()
    }
    targetDevice(sdk, state.log) map { d =>
      executeShellCommand(d, "input keyevent 26", nullRecv)
      executeShellCommand(d, "input keyevent 82", nullRecv)
      state
    } getOrElse sys.error("no device selected")
  }
  val adbCatAction: (State, (FileEntry,Option[String])) => State = {
    case (state, (entry, name)) =>
      val target = name map {
        entry.getFullPath + "/" + _ } getOrElse entry.getFullPath
      targetDevice(sdkpath(state), state.log) map { d =>
        val f = File.createTempFile("adb-cat", "txt")
        d.pullFile(target, f.getAbsolutePath)
        IO.readLines(f) foreach println
        f.delete()
      } getOrElse (Parser.failure("No devices connected") map ( _ => null))
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
        _result = b.toString()
        b.clear()
      }

      override def isCancelled = false

      def result = _result
    }
    val d = targetDevice(sdk, state.log).get
    if (adbWifiOn) {
      state.log.info("turning ADB-over-wifi off")
      Seq(adbPath, "-s", d.getSerialNumber.toString, "usb") !

      state
    } else {
      state.log.info("turning ADB-over-wifi on")

      d.executeShellCommand("ifconfig wlan0", receiver)
      val ip = receiver.result.split(" +")(2)
      state.log.debug("device ip: %s" format ip)

      Seq(adbPath, "-s", d.getSerialNumber.toString, "tcpip", "5555") !

      val r = Seq(adbPath, "connect", ip) !

      if (r != 0)
        sys.error("failed to connect ADB-over-wifi")
      deviceAction(state, ip + ":5555")
    }

  }

  val createProjectSbtAction: State => State = state => {
    val base = file(".")
    val layout = ProjectLayout(base)
    if (!layout.manifest.exists) {
      sys.error("An android project does not exist at this location")
    }
    state.log.info("Creating SBT project files")
    val build = base / "project"
    build.mkdirs()
    val properties = build / "build.properties"
    val projectBuild = build / "build.scala"
    val version = Project.extract(state).get(sbt.Keys.sbtVersion)
    IO.writeLines(properties, "sbt.version=" + version :: Nil)
    IO.writeLines(projectBuild,
      "object Build extends android.AutoBuild" :: Nil)
    state
  }

  val createProjectParser: State => Parser[Either[Unit, ((String, String), String)]] = state => {
    val sdk = sdkpath(state)
    val manager = SdkManager.createManager(sdk, NullLogger)
    val platforms = manager.getTargets
    if (platforms.isEmpty) {
      sys.error("No platform targets configured in sdk located at: " + sdk)
    }
    val targets = Parser.oneOf(platforms map { t =>
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
    case (state, maybe) =>
      val sdk = sdkpath(state)
      maybe.left foreach { _ =>
        sys.error(
          "Usage: gen-android <platform-target> <package-name> <name>")
      }
      (maybe.right map {
        case ((target, pkg), name) =>
          val base = file(".")
          val layout = ProjectLayout(base)
          if (layout.manifest.exists) {
            sys.error(
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

            if (p != 0) {
              sys.error("failed to create project")
            }
            val gitignore = base / ".gitignore"
            val ignores = Seq("target/", "project/project",
              "bin/", "local.properties")
            val files = Seq("gradlew", "gradlew.bat",
              "build.gradle", "local.properties")
            files foreach (f => (base / f).delete())
            IO.delete(base / "gradle")

            val projectProperties = base / "project.properties"
            IO.writeLines(gitignore, ignores)
            IO.writeLines(projectProperties, "target=" + target :: Nil)
            createProjectSbtAction(state)
          }
      }).right getOrElse state
  }

  val stringParser: State => Parser[String] = state => {
    val anything = Parser.charClass(c => true)
    val str = Parser.oneOrMore(anything) map (_ mkString "")
    (EOF map { _ => ""}) | (Space ~> str)
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
    if (pkgOpt.isEmpty || (args contains "-h"))
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
          b.toString().split("\\n").foreach { l =>
            if (l.trim.size > 0) {
              l.trim match {
                case LOG_LINE(level, tag, pid, msg) =>
                  if (tag == "ActivityManager") {
                    msg match {
                      case PID_START(pkg, _, pid2, _, _) => addPid(pkg, pid2)
                      case PID_KILL(pid2, pkg, _) => remPid(pkg, pid2)
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
          b.clear()
        }

        override def isCancelled = false
      }
      d.executeShellCommand("logcat -d", receiver)
      receiver.flush()

      state
    } getOrElse sys.error("no device connected")
  }

  private def executeShellCommand(d: IDevice, cmd: String, state: State) {
    val receiver = new IShellOutputReceiver() {
      val b = new StringBuilder

      override def addOutput(data: Array[Byte], off: Int, len: Int) =
        b.append(new String(data, off, len))

      override def flush() {
        b.toString().split("\\n").foreach { l => state.log.info(l) }
        b.clear()
      }

      override def isCancelled = false
    }
    executeShellCommand(d, cmd, receiver)
  }

  private def executeShellCommand(d: IDevice, cmd: String,
                                  receiver: IShellOutputReceiver) {
    d.executeShellCommand(cmd, receiver)
    receiver.flush()
  }

  val shellAction: (State, String) => State = (state, cmd) => {
    val sdk = sdkpath(state)
    targetDevice(sdk, state.log) map { d =>
      if (cmd.trim.size == 0) {
        sys.error("Usage: adb-shell <command>")
      } else {
        executeShellCommand(d, cmd, state)
      }
      state
    } getOrElse sys.error("no device selected")
  }

  val killAction: (State, String) => State = {
    case (state, str) =>
      val sdk = sdkpath(state)
      val thisProject = Project.extract(state).getOpt(sbt.Keys.thisProjectRef)
      val packageName = thisProject flatMap { prj =>
        Project.extract(state).getOpt(
          Keys.packageName in(prj, Keys.Android))
      }
      val targetPackage = Option(str).filter(_.nonEmpty) orElse packageName
      if (targetPackage.isEmpty)
        sys.error("Usage: adb-kill [<package-name>]")
      state.log.info("Attempting to kill: " + targetPackage.get)
      targetDevice(sdk, state.log) map { d =>
        executeShellCommand(d,
          "am kill " + FileEntry.escape(targetPackage.get), state)
        state
      } getOrElse sys.error("no device selected")
  }
  val runasAction: (State, String) => State = {
    case (state, args) =>
      val sdk = sdkpath(state)
      val thisProject = Project.extract(state).getOpt(sbt.Keys.thisProjectRef)
      val packageName = thisProject flatMap { prj =>
        Project.extract(state).getOpt(
          Keys.packageName in(prj, Keys.Android))
      }
      if (packageName.isEmpty)
        sys.error("Unable to determine package name\n\n" +
          "Usage: adb-runas <command> [args...]")
      if (args.isEmpty)
        sys.error("Usage: adb-runas <command> [args...]")
      targetDevice(sdk, state.log) map { d =>
        executeShellCommand(d,
          "run-as " + FileEntry.escape(packageName.get) + " " + args, state)
        state
      } getOrElse sys.error("no device selected")
  }

  val adbRmAction: (State, (FileEntry,Option[String])) => State = {
    case (state, (entry, name)) =>
      val target = name map {
        entry.getFullPath + "/" + _ } getOrElse entry.getFullPath
      val sdk = sdkpath(state)
      targetDevice(sdk, state.log) map { d =>
        executeShellCommand(d, "rm " + FileEntry.escape(target), state)
        state
      } getOrElse sys.error("no device selected")
  }

  val logcatAction: (State, String) => State = (state, args) => {
    val sdk = sdkpath(state)
    targetDevice(sdk, state.log) map { d =>

      executeShellCommand(d, "logcat -d " + args, state)
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
      Option(System getenv "ANDROID_HOME") flatMap {
        p =>
          val f = file(p)
          if (f.exists && f.isDirectory)
            Some(p + File.separator)
          else
            None: Option[String]
      }) getOrElse sys.error("ANDROID_HOME or sdk.dir is not set")
  }
}
