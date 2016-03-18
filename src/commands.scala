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

import scala.annotation.tailrec
import scala.util.Try
import language.postfixOps
import scala.util.matching.Regex

object Commands {

  val LOGCAT_COMMAND = "logcat -v brief -d"
  var defaultDevice: Option[String] = None

  lazy val initAdb = {
    AndroidDebugBridge.init(false)
    java.lang.Runtime.getRuntime.addShutdownHook(new Thread(new Runnable() {
      override def run() = AndroidDebugBridge.terminate()
    }))
    ()
  }

  def deviceList(state: State): Seq[IDevice] = deviceList(sdkpath(state), state.log)

  def deviceList(sdkpath: String, log: Logger): Seq[IDevice] = {
    initAdb

    import SdkConstants._
    val adbPath = sdkpath + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_ADB

    val adb = AndroidDebugBridge.createBridge(adbPath, false)

    val devices = adb.getDevices
    if (devices.isEmpty) {
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
      state.log.info(" %-22s %-16s %8s Android Version" format ("Serial", "Model", "Battery %"))
      state.log.info(       " %s %s %s ---------------" format ("-" * 22, "-" * 16, "-" * 9))
      list foreach { dev =>
        val name = Option(dev.getAvdName) orElse
          Option(dev.getProperty(IDevice.PROP_DEVICE_MODEL)) getOrElse (
            if (dev.isEmulator) "emulator" else "device")

        val sel = defaultDevice.fold(" ") { d =>
          if (d == dev.getSerialNumber) "*" else " "
        }

        state.log.info(f"$sel${dev.getSerialNumber}%-22s $name%-16s ${dev.getBattery.get}%8s%% ${dev.getProperty(IDevice.PROP_BUILD_VERSION)}%-6s API ${dev.getProperty(IDevice.PROP_BUILD_API_LEVEL)}")
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
    val children = fs.getChildrenSync(entry) map { e => e.getName -> e} toMap

    if (children.isEmpty) Parser.success(entry) else {
      val completions = children.keys map { e =>
        token(e)
      } toSeq

      val eof = EOF map {_ => entry}
      eof | (Parser.oneOf(completions) flatMap { e =>
        val entry = children(e)
        val eof = EOF map {_ => entry}
        eof | (token("/") ~> Parser.opt(androidPathParser(entry, fs)) map (
          _ getOrElse entry))
      })
    }
  }

  val androidFileParser: State => Parser[(FileEntry,Option[String])] = state => {
    Try(targetDevice(sdkpath(state), state.log)).toOption.flatten map { d =>
      val fs = d.getFileListingService
      (EOF map { _ => (fs.getRoot,None) }) |
        ((Space ~> token("/") ~> androidPathParser(fs.getRoot, fs)) ~
          Parser.opt(token("/") ~> token(Parsers.StringBasic, "")))
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
        if (n2.trim.isEmpty) Some(n2) else None
      }
      if (entry.isDirectory) {
        fixed match {
          case Some(n) =>
            val child = entry.findChild(n)
            if (child != null) {
              printEntry(state, child)
            }
          case None =>
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
      targetDevice(sdkpath(state), state.log) match {
        case Some(d) =>
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
        case None =>
          Parser.failure("No devices connected") map (_ => null)
      }
      state
  }

  val adbPushAction: (State, (File,(FileEntry,Option[String]))) => State = {
    case (state, (f, (entry, name))) =>
      val target = name map {
        entry.getFullPath + "/" + _ } getOrElse entry.getFullPath
      state.log.info("Pushing [%s] to [%s]" format (f, target))
      if (!f.isFile)
        Plugin.fail(f + " is not a file")
      targetDevice(sdkpath(state), state.log) match {
        case Some(d) => d.pushFile(f.getAbsolutePath, target)
        case None => Parser.failure("No devices connected") map ( _ => null)
      }
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
    } getOrElse Plugin.fail("no device selected")
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
      targetDevice(sdk, state.log) foreach { _.reboot(mode) }
      state
    } getOrElse Plugin.fail("no device selected")
  }

  val deviceAction: (State, String) => State = (state, dev) => {
    val devices = deviceList(state)

    defaultDevice = devices find (_.getSerialNumber == dev) map { dev =>
      val serial = dev.getSerialNumber
      state.log.info("default device: " + serial)
      serial
    }
    state
  }

  val adbWifiAction: State => State = state => {
    val sdk = sdkpath(state)
    import SdkConstants._
    val adbPath = sdk + OS_SDK_PLATFORM_TOOLS_FOLDER + FN_ADB

    val adbWifiOn = defaultDevice map { s =>
      (s indexOf ":") > 0
    } getOrElse Plugin.fail("no device selected")

    val d = targetDevice(sdk, state.log).get
    if (adbWifiOn) {
      state.log.info("turning ADB-over-wifi off")
      Seq(adbPath, "-s", d.getSerialNumber, "usb") !

      state
    } else {
      state.log.info("turning ADB-over-wifi on")

      val receiver = new ShellResult()
      d.executeShellCommand("ip addr show dev wlan0", receiver)
      val ip = receiver.result.linesIterator.dropWhile(
        _.trim.split(" ")(0) != "inet").toStream.headOption.fold("")(
        _.trim.split(" ")(1).takeWhile(_ != '/'))
      if (ip.nonEmpty) {
        state.log.debug("device ip: %s" format ip)

        Seq(adbPath, "-s", d.getSerialNumber, "tcpip", "5555") !

        val r = Seq(adbPath, "connect", ip) !

        if (r != 0)
          Plugin.fail("failed to connect ADB-over-wifi")
        deviceAction(state, ip + ":5555")
      } else {
        Plugin.fail("unable to determine IP of " + d.getSerialNumber)
      }
    }

  }

  val createProjectSbtAction: State => State = state => {
    def compareSbtVersion(version: String) = {
      val atLeast = List(0, 13, 5)
      @tailrec
      def compareVersions(v1: Seq[Int], v2: Seq[String]): Int = (v1, v2) match {
        case (Nil, Nil)     =>  0
        case (x :: xs, Nil) =>  1
        case (Nil, x :: xs) => -1
        case _ =>
          val i1 = v1.head
          val i2 = Try(v2.head.toInt).toOption getOrElse 0
          if (i1 > i2)
            1
          else if (i1 < i2)
            -1
          else
            compareVersions(v1.tail, v2.tail)
      }
      compareVersions(atLeast, version.split("\\.")) <= 0
    }
    val base = file(".")
    val layout = ProjectLayout(base)
    if (!layout.manifest.exists) {
      Plugin.fail("An android project does not exist at this location")
    }
    state.log.info("Creating SBT project files")
    val build = base / "project"
    build.mkdirs()
    val properties = build / "build.properties"
    val projectBuild = build / "build.scala"
    val pluginSbt = build / "android.sbt"
    val plugin = s"""addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "${BuildInfo.version}")""".stripMargin
    val version = Project.extract(state).get(sbt.Keys.sbtVersion)
    val useVersion = if (compareSbtVersion(version)) version else "0.13.5"
    IO.writeLines(pluginSbt, plugin ::  Nil)
    IO.writeLines(properties, "sbt.version=" + useVersion :: Nil)
    IO.writeLines(projectBuild,
      "object Build extends android.AutoBuild" :: Nil)
    if (util.Properties.isJavaAtLeast("1.8")) {
      val javacOption =
        """
          |javacOptions in Compile ++= "-source" :: "1.7" :: "-target" :: "1.7" :: Nil
        """.stripMargin
      IO.write(base / "build.sbt", javacOption.trim + "\n")
    }
    state
  }

  val createProjectParser: State => Parser[Either[Unit, ((String, String), String)]] = state => {
    val sdk = sdkpath(state)
    val manager = SdkManager.createManager(sdk, NullLogger)
    val platforms = manager.getTargets
    if (platforms.isEmpty) {
      Plugin.fail("No platform targets configured in sdk located at: " + sdk)
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
        Plugin.fail(
          "Usage: gen-android <platform-target> <package-name> <name>")
      }
      (maybe.right map {
        case ((target, pkg), name) =>
          val base = file(".")
          val layout = ProjectLayout(base)
          if (layout.manifest.exists) {
            Plugin.fail(
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
              Plugin.fail("failed to create project")
            }
            val gitignore = base / ".gitignore"
            val ignores = Seq("target/", "project/project",
              "bin/", "local.properties")
            val files = Seq("gradlew", "gradlew.bat",
              "build.gradle", "local.properties", "src/main/res/layout/main.xml")
            files foreach (f => (base / f).delete())
            IO.delete(base / "gradle")
            IO.delete(base / "src" / "main" / "java")
            val sampleTemplate = IO.readLinesURL(
              Tasks.resourceUrl("android-sample.scala.template")) mkString "\n"
            val layoutSample = IO.readLinesURL(
              Tasks.resourceUrl("android-main.xml.template")) mkString "\n"
            IO.write(base / "src/main/res/layout/main.xml", layoutSample)
            IO.write(base / "src/main/scala" / pkg.replace('.','/') / "sample.scala",
              sampleTemplate.format(pkg, name))

            val projectProperties = base / "project.properties"
            IO.writeLines(gitignore, ignores)
            IO.writeLines(projectProperties, "target=" + target :: Nil)
            createProjectSbtAction(state)
          }
      }).right getOrElse state
  }

  val stringParser: State => Parser[String] = state => {
    val str = Parser.oneOrMore(Parsers.any) map (_ mkString "")
    (EOF map { _ => ""}) | (Space ~> str)
  }

  val projectParser: State => Parser[Option[ProjectRef]] = state => {
    val NotSepClass = charClass(_ != '/')
    val extracted = Project.extract(state)
    import extracted._
    val prjs = structure.allProjects.map(rp =>
      (rp.id, ProjectRef(structure.root, rp.id))).filter { case (k, ref) =>
      getOpt(Keys.projectLayout in ref).isDefined
    }.toMap
    val ids = prjs.keys.filterNot(_ == currentRef.project).map(Parser.literal).toList

    if (ids.isEmpty) Parser.success(None)
    else if (prjs.size == 1) Parser.success(Some(prjs.values.head))
    else
      (Parser.success(None) <~ Parser.opt(NotSepClass | EOF)) | token("/" ~> oneOf(ids)).map(prjs.get)
  }

  val projectAndStringParser: State => Parser[(Option[ProjectRef],String)] = state =>
    projectParser(state) ~ stringParser(state)

  val LOG_LINE = """^([A-Z])/(.+?)\( *(\d+)\): (.*?)$""".r
  val LOG_LINE_N = """^([A-Z])/(.+?)\( *(\d+): *(\d+)\): (.*?)$""".r
  def pidcatLogLine(d: IDevice, pkgOpt: Option[String], log: Logger)(pred: LogcatLine => Option[LogcatLine]): String => Unit = {
    val PKG_PATTERN = """package:(\S+)""".r
    val PID_START = """^Start proc ([a-zA-Z0-9._:]+) for ([a-z]+ [^:]+): pid=(\d+) uid=(\d+) gids=(.*)$""".r
    val PID_START5_1 = """^Start proc (\d+):([a-zA-Z0-9._:]+)/[a-z0-9]+ for (.*)$""".r
    val PID_KILL = """^Killing (\d+):([a-zA-Z0-9._:]+)/[^:]+: (.*)$""".r
    val PID_LEAVE = """^No longer want ([a-zA-Z0-9._:]+) \(pid (\d+)\): .*$""".r
    val PID_DEATH = """^Process ([a-zA-Z0-9._:]+) \(pid (\d+)\) has died.?$""".r
    var pids = Set.empty[String]
    val v = d.getProperty(IDevice.PROP_BUILD_VERSION)
    val uidSet: Set[String] = if (v == "N") {
      val sr1 = new ShellResult
      val cmd = "cmd package list package " + pkgOpt.get
      d.executeShellCommand(cmd, sr1)
      val pkglist = sr1.result.split("\\s+").toList.map(_.trim).collect { case PKG_PATTERN(pkg) => pkg }
      pkglist.map { pkg =>
        val srd = new ShellResult
        d.executeShellCommand(s"stat -c %u /data/data/$pkg", srd)
        srd.result.trim
      }.toSet
    } else Set.empty

    def addPid(pkg: String, pid: String) {
      pkgOpt foreach { p => if (pkg contains p) pids += pid.trim}
    }

    def remPid(pkg: String, pid: String) {
      pkgOpt foreach { p => if (pkg contains p) pids -= pid.trim}
    }
    { (l: String) =>
      if (l.trim.length > 0) {
        l.trim match {
          case LOG_LINE_N(level, tag, uid, pid, msg) =>
            if (uidSet(uid)) pred(LogcatLine(level, tag, pid, msg)) foreach { case LogcatLine(lvl, t, p, m) =>
              val colored =
                f"${colorLevel(lvl)} (${colorPid(p)}) ${colorTag(t)}%8s: $m"
              scala.Console.out.println(colored)
            }
          case LOG_LINE(level, tag, pid, msg) =>
            if (tag == "ActivityManager") {
              msg match {
                case PID_START(pkg, _, pid2, _, _) => addPid(pkg, pid2)
                case PID_START5_1(pid2, pkg, _) => addPid(pkg, pid2)
                case PID_KILL(pid2, pkg, _) => remPid(pkg, pid2)
                case PID_LEAVE(pkg, pid2) => remPid(pkg, pid2)
                case PID_DEATH(pkg, pid2) => remPid(pkg, pid2)
                case _ =>
              }
            } else if (pids(pid.trim)) pred(LogcatLine(level, tag, pid, msg)) foreach { case LogcatLine(lvl, t, p, m) =>
              val colored =
                f"${colorLevel(lvl)} (${colorPid(p)}) ${colorTag(t)}%8s: $m"
              scala.Console.out.println(colored)
            }
          case _ => log.debug(l.trim)
        }
      }
    }
  }
  val pidcatGrepAction: (State, (Option[ProjectRef],String)) => State = {
    case (state, (prj,args)) =>

      val sdk = sdkpath(state)
      val packageName = thisPackageName(state, prj)

      val (pkgOpt, regex) = if (args.trim.nonEmpty) {
        val parts = args.trim.split(" ")
        if (prj.isDefined) {
          (packageName, parts.toSeq)
        } else if (parts.size > 1) {
          (Some(parts(0)), parts.tail.toSeq)
        } else {
          (Some(parts(0)), Seq.empty[String])
        }
      } else (packageName, Seq.empty[String])
      val re = regex.mkString(" ").r
      if (pkgOpt.isEmpty || (args contains "-h"))
        Plugin.fail("Usage: pidcat-grep [<partial package-name>] <regex>")

      targetDevice(sdk, state.log) map { d =>
        val receiver = new ShellLogging(pidcatLogLine(d, pkgOpt, state.log) { l =>
          val tagMatch = re.findAllMatchIn(l.tag)
          val msgMatch = re.findAllMatchIn(l.msg)
          if (tagMatch.nonEmpty|| msgMatch.nonEmpty)
            Some(highlightMatch(tagMatch, msgMatch, l)) else None
        })
        val v = d.getProperty(IDevice.PROP_BUILD_VERSION)
        val logcat = LOGCAT_COMMAND + (if (v == "N") " -v uid" else "")
        d.executeShellCommand(logcat, receiver)
        receiver.flush()

        state
      } getOrElse Plugin.fail("no device connected")
  }

  val pidcatAction: (State, (Option[ProjectRef],String)) => State = {
    case (state, (prj,args)) =>

    val sdk = sdkpath(state)
    val packageName = thisPackageName(state, prj)

    val (pkgOpt, tags) = if (args.trim.nonEmpty) {
      val parts = args.trim.split(" ")
      if (prj.isDefined) {
        (packageName, parts.toSeq)
      } else if (parts.size > 1) {
        (Some(parts(0)), parts.tail.toSeq)
      } else {
        (Some(parts(0)), Seq.empty[String])
      }
    } else (packageName, Seq.empty[String])
    if (pkgOpt.isEmpty || (args contains "-h"))
      Plugin.fail("Usage: pidcat [<partial package-name>] [TAGs]...")

    targetDevice(sdk, state.log) map { d =>
      val receiver = new ShellLogging(pidcatLogLine(d, pkgOpt, state.log)(l =>
        if (tags.isEmpty || tags.exists(l.tag.contains)) Some(l) else None))
      val v = d.getProperty(IDevice.PROP_BUILD_VERSION)
      val logcat = LOGCAT_COMMAND + (if (v == "N") " -v uid" else "")
      d.executeShellCommand(logcat, receiver)
      receiver.flush()

      state
    } getOrElse Plugin.fail("no device connected")
  }

  def colorLevel(level: String) = {
    LEVEL_COLORS.getOrElse(level,
      scala.Console.BOLD + scala.Console.YELLOW_B + scala.Console.WHITE) +
      " " + level + " " +
      scala.Console.RESET
  }
  def colorTag(tag: String) = {
    val idx = math.abs(tag.hashCode % TAG_COLORS.length)
    val color = TAG_COLORS(idx)
    color + tag.replaceAllLiterally(scala.Console.RESET, color) +
      scala.Console.RESET
  }
  def colorPid(p: String) = {
    val pid = p.trim.toInt
    val idx = pid % TAG_COLORS.length
    // can't do formatting in 'colored' because escape codes
    TAG_COLORS(idx) + f"$pid%5d" + scala.Console.RESET
  }
  lazy val LEVEL_COLORS = Map(
    "D" -> (scala.Console.BOLD + scala.Console.CYAN_B + scala.Console.WHITE),
    "E" -> (scala.Console.BOLD + scala.Console.RED_B + scala.Console.WHITE),
    "I" -> (scala.Console.BOLD + scala.Console.GREEN_B + scala.Console.WHITE),
    "V" -> (scala.Console.BOLD + scala.Console.BLUE_B + scala.Console.WHITE),
    "W" -> (scala.Console.BOLD + scala.Console.MAGENTA_B + scala.Console.WHITE)
  )

  lazy val TAG_COLORS = Vector(
    scala.Console.BLUE,
    scala.Console.BOLD + scala.Console.BLUE,
    scala.Console.GREEN,
    scala.Console.BOLD + scala.Console.GREEN,
    scala.Console.RED,
    scala.Console.BOLD + scala.Console.RED,
    scala.Console.MAGENTA,
    scala.Console.BOLD + scala.Console.MAGENTA,
    scala.Console.YELLOW,
    scala.Console.BOLD + scala.Console.YELLOW,
    scala.Console.CYAN,
    scala.Console.BOLD + scala.Console.CYAN
  )

  private def executeShellCommand(d: IDevice, cmd: String, state: State) {
    val receiver = new ShellLogging(l => state.log.info(l))
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
      if (cmd.trim.isEmpty) {
        Plugin.fail("Usage: adb-shell <command>")
      } else {
        executeShellCommand(d, cmd, state)
      }
      state
    } getOrElse Plugin.fail("no device selected")
  }

  val killAction: (State, (Option[ProjectRef],String)) => State = {
    case (state, (prj,str)) =>
      val sdk = sdkpath(state)
      val packageName = thisPackageName(state, prj)
      val targetPackage = Option(str).filter(_.nonEmpty) orElse packageName
      if (targetPackage.isEmpty)
        Plugin.fail("Usage: adb-kill [<package-name>]")
      state.log.info("Attempting to kill: " + targetPackage.get)
      targetDevice(sdk, state.log) map { d =>
        val api = Try(d.getProperty(IDevice.PROP_BUILD_API_LEVEL).toInt).toOption getOrElse 0

        val cmd = if (api >= 11) "am force-stop " else "am kill "
        executeShellCommand(d, cmd + FileEntry.escape(targetPackage.get), state)
        state
      } getOrElse Plugin.fail("no device selected")
  }
  val runasAction: (State, (Option[ProjectRef],String)) => State = {
    case (state, (prj,args)) =>
      val sdk = sdkpath(state)
      val packageName = thisPackageName(state, prj)
      if (packageName.isEmpty)
        Plugin.fail("Unable to determine package name\n\n" +
          "Usage: adb-runas <command> [args...]")
      if (args.isEmpty)
        Plugin.fail("Usage: adb-runas <command> [args...]")
      targetDevice(sdk, state.log) map { d =>
        executeShellCommand(d,
          "run-as " + FileEntry.escape(packageName.get) + " " + args, state)
        state
      } getOrElse Plugin.fail("no device selected")
  }

  val adbRmAction: (State, (FileEntry,Option[String])) => State = {
    case (state, (entry, name)) =>
      val target = name map {
        entry.getFullPath + "/" + _ } getOrElse entry.getFullPath
      val sdk = sdkpath(state)
      targetDevice(sdk, state.log) map { d =>
        executeShellCommand(d, "rm " + FileEntry.escape(target), state)
        state
      } getOrElse Plugin.fail("no device selected")
  }

  case class LogcatLine(level: String, tag: String, pid: String, msg: String)
  def logcatLogLine(log: Logger)(pred: LogcatLine => Option[LogcatLine])(l: String): Unit = {
    if (l.trim.length > 0) {
      l.trim match {
        case LOG_LINE(lv, tg, p, m) => pred(LogcatLine(lv, tg, p, m)) foreach {
          case LogcatLine(level, tag, pid, msg) =>
            val colored =
              f"${colorLevel(level)} (${colorPid(pid)}) ${colorTag(tag)}%8s: $msg"
            scala.Console.out.println(colored)
        }
        case _ => log.debug(l.trim)
      }
    }
  }
  def highlightMatch(tagMatch: Iterator[Regex.Match], msgMatch: Iterator[Regex.Match], line: LogcatLine): LogcatLine = {
    val start = scala.Console.RED + scala.Console.BOLD
    val incr = start.length
    val incr2 = scala.Console.RESET.length
    def highlightM(s: String, ms: Iterator[Regex.Match]): String = {
      ms.foldLeft((s,0)) { case ((str, off), m) =>
        val (s1, s2) = str.splitAt(m.start + off)
        val (e1, e2) = (s1 + start + s2).splitAt(m.end + off + incr)
        (e1 + scala.Console.RESET + e2, off + incr + incr2)
      }._1
    }
    val tagged = line.copy(tag = highlightM(line.tag, tagMatch))
    tagged.copy(msg = highlightM(line.msg, msgMatch))
  }
  val logcatGrepAction: (State, String) => State = (state, args) => {
    val (regex, fpid) = args.split(" ").foldRight((List.empty[String],Option.empty[String])) { case (a, (as,pid)) =>
      if (a != "-p") (a :: as,pid) else (as.drop(1), as.headOption)
    }
    val sdk = sdkpath(state)
    val re = regex.mkString(" ").r
    targetDevice(sdk, state.log) map { d =>
      val receiver = new ShellLogging(logcatLogLine(state.log) { l =>
        val pidmatch = fpid.fold(true)(_ == l.pid)
        val tagMatch = re.findAllMatchIn(l.tag)
        val msgMatch = re.findAllMatchIn(l.msg)
        if (pidmatch && (tagMatch.nonEmpty || msgMatch.nonEmpty))
          Some(highlightMatch(tagMatch, msgMatch, l))
        else
          None
      })
      d.executeShellCommand(LOGCAT_COMMAND, receiver)
      receiver.flush()
      state
    } getOrElse Plugin.fail("no device selected")
  }

  val logcatAction: (State, String) => State = (state, args) => {
    val (logcatargs, fpid) = args.split(" ").foldRight((List.empty[String],Option.empty[String])) { case (a, (as,pid)) =>
      if (a != "-p") (a :: as,pid) else (as.drop(1), as.headOption)
    }
    val sdk = sdkpath(state)
    targetDevice(sdk, state.log) map { d =>
      val receiver = new ShellLogging(logcatLogLine(state.log)(l =>
        fpid.fold(Option(l))(p => if (p == l.pid) Some(l) else None)))
      d.executeShellCommand(
        (LOGCAT_COMMAND :: logcatargs).mkString(" "), receiver)
      receiver.flush()
      state
    } getOrElse Plugin.fail("no device selected")
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
      Plugin.fail("no devices connected")
    } else {
      defaultDevice flatMap { device =>
        devices find (device == _.getSerialNumber) orElse {
          log.warn("default device not found, falling back to first device")
          None
        }
      } orElse {
        devices.headOption
      }
    }
  }

  private def sdkpath(state: State): String = {
    Project.extract(state).getOpt(
      Keys.sdkPath) orElse (
      Option(System getenv "ANDROID_HOME") flatMap {
        p =>
          val f = file(p)
          if (f.exists && f.isDirectory)
            Some(p + File.separator)
          else
            None: Option[String]
      }) getOrElse Plugin.fail("ANDROID_HOME or sdk.dir is not set")
  }
  class ShellLogging[A](logger: String => A) extends IShellOutputReceiver {
    private[this] val b = new StringBuilder

    override def addOutput(data: Array[Byte], off: Int, len: Int) = {
      b.append(new String(data, off, len))
      val lastNL = b.lastIndexOf("\n")
      if (lastNL != -1) {
        b.mkString.split("\\n") foreach logger
        b.delete(0, lastNL + 1)
      }
    }

    override def flush() {
      b.mkString.split("\\n").filterNot(_.isEmpty) foreach logger
      b.clear()
    }

    override def isCancelled = false
  }
  class ShellResult extends IShellOutputReceiver {
    private[this] val b = new StringBuilder
    private[this] var _result = ""

    override def addOutput(data: Array[Byte], off: Int, len: Int) =
      b.append(new String(data, off, len))

    override def flush() {
      _result = b.mkString
      b.clear()
    }

    override def isCancelled = false

    def result = _result
  }

  def thisPackageName(s: State, ref: Option[ProjectRef]) = {
    val extracted = Project.extract(s)
    val prj = ref getOrElse extracted.currentRef
    Try {
      extracted.runTask(Keys.applicationId in prj, s)._2
    }.toOption
  }

  type VariantResult = (ProjectRef,Option[String],Option[String])
  type VariantParser = Parser[VariantResult]
  val variantParser: State => VariantParser = s => {
    val extracted = Project.extract(s)
    projectParser(s) flatMap { r =>
      val prj = r.orElse {
        val ref = extracted.currentRef
        if (extracted.getOpt(Keys.projectLayout in ref).isDefined)
          Some(ref)
        else None
      }

      prj.fold(Parser.failure("No Android project selected"): VariantParser) { p =>
        val typeParser = extracted.getOpt(Keys.buildTypes in p).fold {
          token("--").map(_ => Option.empty[String])
        } { buildTypes =>
          val bt = buildTypes.keys.map(literal).toList
          Parser.oneOf(literal("--") :: bt).map(b => if ("--" == b) None else Option(b))
        }

        val flavorParser = extracted.getOpt(Keys.flavors in p).fold {
          token("--").map(_ => Option.empty[String])
        } { flavors =>
          val fl = flavors.keys.map(literal).toList
          Parser.oneOf(literal("--") :: fl).map(f => if ("--" == f) None else Option(f))
        }

        (Parser.success(p) ~ (EOF.map(_ => None) | token(Space ~> typeParser)) ~ (EOF.map(_ => None) | token(Space ~> flavorParser))).map {
          case (((a,b),c)) => (a,b,c)
        }
      }
    }
  }
  val variantAction: (State, VariantResult) => State = {
    case (s, (prj, buildType, flavor)) =>
      if (buildType.isEmpty && flavor.isEmpty) {
        VariantSettings.showVariantStatus(s, prj)
      } else {
        VariantSettings.setVariant(s, prj, buildType, flavor)
      }
  }

  val variantClearAction: (State, Option[ProjectRef]) => State = {
    (state, ref) =>
      ref match {
        case None      => VariantSettings.clearVariant(state)
        case Some(prj) => VariantSettings.clearVariant(state, prj)
      }
  }
}
