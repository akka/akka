import sbt._
import sbt.Process
import java.io.File
import java.lang.{ProcessBuilder => JProcessBuilder}
import java.io.{BufferedReader, InputStream, InputStreamReader, OutputStream}

trait MultiJvmTests extends DefaultProject {
  def multiJvmTestName = "MultiJvm"

  def multiJvmOptions: Seq[String] = Seq.empty

  def multiJvmExtraOptions(className: String): Seq[String] = Seq.empty

  val MultiJvmTestName = multiJvmTestName

  val ScalaTestRunner = "org.scalatest.tools.Runner"
  val ScalaTestOptions = "-o"

  val javaPath = Path.fromFile(System.getProperty("java.home")) / "bin" / "java"

  private val HeaderStart = System.getProperty("sbt.start.delimiter", "==")
  private val HeaderEnd = System.getProperty("sbt.end.delimiter", "==")

  // exclude multi jvm tests from normal tests
  override def testOptions = super.testOptions ++ Seq(TestFilter(test => !test.name.contains(MultiJvmTestName)))

  lazy val multiJvmTest = multiJvmTestAction
  lazy val multiJvmRun = multiJvmRunAction
  lazy val multiJvmTestAll = multiJvmTestAllAction

  def multiJvmTestAction = multiJvmMethod(getMultiJvmTests, testScalaOptions)

  def multiJvmRunAction = multiJvmMethod(getMultiJvmApps, runScalaOptions)

  def multiJvmTestAllAction = multiJvmTask(Nil, getMultiJvmTests, testScalaOptions)

  def multiJvmMethod(getMultiTestsMap: => Map[String, Seq[String]], scalaOptions: String => Seq[String]) = {
    task {
      args =>
        multiJvmTask(args.toList, getMultiTestsMap, scalaOptions)
    } completeWith (getMultiTestsMap.keys.toList)
  }

  def multiJvmTask(tests: List[String], getMultiTestsMap: => Map[String, Seq[String]], scalaOptions: String => Seq[String]) = {
    task {
      val multiTestsMap = getMultiTestsMap
      def process(runTests: List[String]): Option[String] = {
        if (runTests.isEmpty) {
          None
        } else {
          val testName = runTests(0)
          val failed = multiTestsMap.get(testName) match {
            case Some(testClasses) => runMulti(testName, testClasses, scalaOptions)
            case None => Some("No multi jvm test called " + testName)
          }
          failed orElse process(runTests.tail)
        }
      }
      val runTests = if (tests.size > 0) tests else multiTestsMap.keys.toList.asInstanceOf[List[String]]
      process(runTests)
    } dependsOn (testCompile)
  }

  /**
   * todo: Documentation
   */
  def getMultiJvmTests(): Map[String, Seq[String]] = {
    val allTests = testCompileConditional.analysis.allTests.toList.map(_.className)
    filterMultiJvmTests(allTests)
  }

  /**
   * todo: Documentation
   */
  def getMultiJvmApps(): Map[String, Seq[String]] = {
    val allApps = (mainCompileConditional.analysis.allApplications.toSeq ++
      testCompileConditional.analysis.allApplications.toSeq)
    filterMultiJvmTests(allApps)
  }

  /**
   * todo: Documentation
   */
  def filterMultiJvmTests(allTests: Seq[String]): Map[String, Seq[String]] = {
    val multiJvmTests = allTests filter (_.contains(MultiJvmTestName))
    val names = multiJvmTests map { fullName =>
      val lastDot = fullName.lastIndexOf(".")
      val className = if (lastDot >= 0) fullName.substring(lastDot + 1) else fullName
      val i = className.indexOf(MultiJvmTestName)
      if (i >= 0) fullName.substring(0, lastDot + i + 1) else fullName
    }
    val testPairs = names map { name => (name, multiJvmTests.toList.filter(_.startsWith(name)).sort(_ < _)) }
    Map(testPairs: _*)
  }

  /**
   * todo: Documentation
   */
  def testIdentifier(className: String) = {
    val i = className.indexOf(MultiJvmTestName)
    val l = MultiJvmTestName.length
    className.substring(i + l)
  }

  /**
   * todo: Documentation
   */
  def testSimpleName(className: String) = {
    className.split("\\.").last
  }

  /**
   * todo: Documentation
   */
  def testScalaOptions(testClass: String) = {
    val scalaTestJars = testClasspath.get.filter(_.name.contains("scalatest"))
    val cp = Path.makeString(scalaTestJars)
    val paths = "\"" + testClasspath.get.map(_.absolutePath).mkString(" ", " ", " ") + "\""
    Seq("-cp", cp, ScalaTestRunner, ScalaTestOptions, "-s", testClass, "-p", paths)
  }

  /**
   * todo: Documentation
   */
  def runScalaOptions(appClass: String) = {
    val cp = Path.makeString(testClasspath.get)
    Seq("-cp", cp, appClass)
  }

  /**
   * Runs all the test. This method blocks until all processes have completed.
   *
   * @return an option that return an error message if one of the tests failed, or a None in case of a success.
   */
  def runMulti(testName: String, testClasses: Seq[String], scalaOptions: String => Seq[String]): Option[String] = {
    log.control(ControlEvent.Start, "%s multi-jvm / %s %s" format (HeaderStart, testName, HeaderEnd))

    //spawns all the processes.
    val processes = testClasses.toList.zipWithIndex map {
      case (testClass, index) => {
        val jvmName = "JVM-" + testIdentifier(testClass)
        val jvmLogger = new JvmLogger(jvmName)
        val className = testSimpleName(testClass)
        val optionsFiles = (testSourcePath ** (className + ".opts")).get
        val optionsFromFile: Seq[String] = {
          if (!optionsFiles.isEmpty) {
            val file = optionsFiles.toList.head.asFile
            log.info("Reading JVM options from %s" + file)
            FileUtilities.readString(file, log) match {
              case Right(opts: String) => opts.trim.split(" ").toSeq
              case _ => Seq.empty
            }
          } else Seq.empty
        }
        val extraOptions = multiJvmExtraOptions(className)
        val jvmOptions = multiJvmOptions ++ optionsFromFile ++ extraOptions
        log.info("Starting %s for %s" format (jvmName, testClass))
        log.info("  with JVM options: %s" format jvmOptions.mkString(" "))
        (testClass, startJvm(jvmOptions, scalaOptions(testClass), jvmLogger, index == 0))
      }
    }

    //places the exit code of the process belonging to a specific textClass in the exitCodes map.
    val exitCodes = processes map {
      case (testClass, process) => (testClass, process.exitValue)
    }

    //Checks if there are any processes that failed with an error.
    val failures = exitCodes flatMap {
      case (testClass, exit) if exit > 0 => Some("%s failed with exit code %s" format (testClass, exit))
      case _ => None
    }

    //log the failures (if there are any).
    failures foreach (log.error(_))
    log.control(ControlEvent.Finish, "%s multi-jvm / %s %s" format (HeaderStart, testName, HeaderEnd))

    if (!failures.isEmpty) Some("Some processes failed") else None
  }

  /**
   * Starts a JVM with the given options.
   */
  def startJvm(jvmOptions: Seq[String], scalaOptions: Seq[String], logger: Logger, connectInput: Boolean) = {
    val si = buildScalaInstance
    val scalaJars = Seq(si.libraryJar, si.compilerJar)
    forkScala(jvmOptions, scalaJars, scalaOptions, logger, connectInput)
  }

  def forkScala(jvmOptions: Seq[String], scalaJars: Iterable[File], arguments: Seq[String], logger: Logger, connectInput: Boolean) = {
    val scalaClasspath = scalaJars.map(_.getAbsolutePath).mkString(File.pathSeparator)
    val bootClasspath = "-Xbootclasspath/a:" + scalaClasspath
    val mainScalaClass = "scala.tools.nsc.MainGenericRunner"
    val options = jvmOptions ++ Seq(bootClasspath, mainScalaClass) ++ arguments
    forkJava(options, logger, connectInput)
  }

  def forkJava(options: Seq[String], logger: Logger, connectInput: Boolean) = {
    val java = javaPath.toString
    val command = (java :: options.toList).toArray
    val builder = new JProcessBuilder(command: _*)
    Process(builder).run(JvmIO(logger, connectInput))
  }
}

final class JvmLogger(name: String) extends BasicLogger {
  def jvm(message: String) = "[%s] %s" format (name, message)

  def log(level: Level.Value, message: => String) = System.out.synchronized {
    System.out.println(jvm(message))
  }

  def trace(t: => Throwable) = System.out.synchronized {
    val traceLevel = getTrace
    if (traceLevel >= 0) System.out.print(StackTrace.trimmed(t, traceLevel))
  }

  def success(message: => String) = log(Level.Info, message)
  def control(event: ControlEvent.Value, message: => String) = log(Level.Info, message)

  def logAll(events: Seq[LogEvent]) = System.out.synchronized { events.foreach(log) }
}

object JvmIO {
  def apply(log: Logger, connectInput: Boolean) =
    new ProcessIO(input(connectInput), processStream(log, Level.Info), processStream(log, Level.Error))

  final val BufferSize = 8192

  def processStream(log: Logger, level: Level.Value): InputStream => Unit =
    processStream(line => log.log(level, line))

  def processStream(processLine: String => Unit): InputStream => Unit = in => {
    val reader = new BufferedReader(new InputStreamReader(in))
    def process {
      val line = reader.readLine()
      if (line != null) {
        processLine(line)
        process
      }
    }
    process
  }

  def input(connectInput: Boolean): OutputStream => Unit =
    if (connectInput) connectSystemIn else ignoreOutputStream

  def connectSystemIn(out: OutputStream) = transfer(System.in, out)

  def ignoreOutputStream = (out: OutputStream) => ()

  def transfer(in: InputStream, out: OutputStream) {
    try {
      val buffer = new Array[Byte](BufferSize)
      def read {
        val byteCount = in.read(buffer)
        if (Thread.interrupted) throw new InterruptedException
        if (byteCount > 0) {
          out.write(buffer, 0, byteCount)
          out.flush()
          read
        }
      }
      read
    } catch {
      case _: InterruptedException => ()
    }
  }
}

