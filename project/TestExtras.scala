package akka

import sbt._
import Keys._

import com.timgroup.statsd.{StatsDClientErrorHandler, NonBlockingStatsDClient}
import sbt.testing.{TestSelector, Status, Event}
import scala.util.Try
import java.io.{InputStreamReader, BufferedReader, DataOutputStream, OutputStreamWriter}
import java.net.{InetAddress, URLEncoder, HttpURLConnection, Socket}
import com.typesafe.sbt.SbtGit
import com.typesafe.sbt.SbtGit.GitKeys._

object TestExtras {

  object JUnitFileReporting {
    val settings = Seq(
      // we can enable junit-style reports everywhere with this
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a", "-u", (target.value / "test-reports").getAbsolutePath),
      testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-u", (target.value / "test-reports").getAbsolutePath)
    )
  }

  object GraphiteBuildEvents {
    val graphite = config("graphite")

    val enabled = settingKey[Boolean]("Set to true when you want to send build events to graphite; Enable with `-Dakka.sbt.graphite=true`")

    val host = settingKey[String]("Host where graphite is located (ip, or hostname)")

    val port = settingKey[Int]("Port on which graphite is listening, defaults to 80")

    private val notifier = settingKey[Option[GraphiteBuildNotifier]]("Notifies graphite about this build")

    val settings = SbtGit.settings ++ SbtGit.projectSettings ++ Seq(
      enabled in graphite := sys.props("akka.sbt.graphite") == "true",
      host in graphite := sys.props.get("akka.sbt.graphite.host").getOrElse("54.72.154.120"),
      port in graphite := sys.props.get("akka.sbt.graphite.port").flatMap(p => Try(p.toInt).toOption).getOrElse(80),

      notifier := (enabled.in(graphite).value match {
        case true => Some(new GraphiteBuildNotifier(gitCurrentBranch.value, gitHeadCommit.value, host.in(graphite).value, port.in(graphite).value))
        case _ => None
      }),

      // this wraps the test task in order to send events before and after it
      test in Test := Def.settingDyn {
        val g = notifier.value
        g.foreach(_.start())

        // todo support complete(failed / successful)
        val task = (test in Test).taskValue andFinally { g.foreach(_.complete()) }

        Def.setting(task)
      }.value
    )

    /**
     * Notifies graphite by sending an *event*, when a build starts.
     * It will be tagged as "akka-build" and "branch:...", for filtering in UIs.
     *
     * Event includes branch and commit id of the build that is running.
     */
    class GraphiteBuildNotifier(branch: String, commitId: Option[String], host: String, port: Int)  {

      private val url = new URL(s"http://$host:$port/events/")

      private val hostname = InetAddress.getLocalHost.getHostName

      private val marker = branch + commitId.fold("")(id => s" @ $id")

      private def json(what: String, tag: String, data: String = "") =
        s"""{ "what": "$what", "tags": "akka-build,branch:${sanitize(branch)},$tag", "data": "$data"}""".stripMargin

      def start(): Unit = send(s"Build started: $marker", data = "host = " + hostname, tag = "started")

      def complete(): Unit = send(s"Build completed: $marker", data = "host = " + hostname, tag = "completed")

      def send(msg: String, data: String, tag: String) = try {
        // specifically not using Akka-IO (even though I'd love to), in order to not make the akka build depend on akka itself
        val con = url.openConnection().asInstanceOf[HttpURLConnection]
        try {
          val bytes = json(msg, data, tag).getBytes("UTF-8")
          con.setDoOutput(true) // triggers POST
          con.connect()

          val out = new DataOutputStream(con.getOutputStream)
          try {
            out.write(bytes)
            out.flush()

            // sigh, if left un-consumed graphite wouldn't take the write (*really*)!
            consume(con)

          } finally {
            out.close()
          }
        } finally {
          con.disconnect()
        }
      }

      private def sanitize(s: String): String = s.replaceAll("""[^\w]+""", "-")

      private def consume(con: HttpURLConnection) {
        val in = new BufferedReader(new InputStreamReader(con.getInputStream))
        var inputLine = ""
        try {
          while (inputLine != null) {
            inputLine = in.readLine()
          }
        } finally {
          in.close()
        }
      }
    }
  }

  object StatsDMetrics {

    val statsd = config("statsd")

    val enabled = settingKey[Boolean]("Set to true when you want to send stats to statsd; Enable with `-Dakka.sbt.statsd=true`")

    val prefix = settingKey[String]("Prefix given to all metrics sent to statsd")

    val host = settingKey[String]("Host where statsd is located (ip, or hostname)")

    val port = settingKey[Int]("Port on which statsd is listening, defaults to 8125")


    val settings = Seq(
      // configuration
      enabled in statsd := sys.props("akka.sbt.statsd") == "true",
      prefix in statsd := Option(sys.props("akka.sbt.statsd.prefix")).getOrElse("akka_master"),
      host in statsd := Option(sys.props("akka.sbt.statsd.host")).getOrElse("54.72.154.120"),
      port in statsd := Option(sys.props("akka.sbt.statsd.port")).flatMap(p => Try(p.toInt).toOption).getOrElse(8125),

      testListeners in(Test, test) ++= {
        // for `test`
        enabled.in(statsd).value match {
          case true => Seq(StatsDTestListener(streams.value.log, prefix.in(statsd).value, host.in(statsd).value, port.in(statsd).value))
          case _ => Nil
        }
      },
      testListeners ++= {
        // for `testOnly`
        enabled.in(statsd).value match {
          case true => Seq(StatsDTestListener(streams.value.log, prefix.in(statsd).value, host.in(statsd).value, port.in(statsd).value))
          case _ => Nil
        }
      }
    )

    case class StatsDTestListener(log: Logger, prefix: String, host: String, port: Int) extends TestsListener {

      var client: NonBlockingStatsDClient = _

      override def doInit(): Unit = {
        log.info(s"Initialised StatsDTestsListener (sending stats to $host:$port)")
        client = new NonBlockingStatsDClient(prefix, host, port, new StatsDClientErrorHandler {
          override def handle(exception: Exception): Unit = log.error(exception.toString)
        })
      }

      override def testEvent(event: TestEvent) {
        event.detail foreach { det =>
          det.status match {
            case Status.Success =>
              client.incrementCounter(testCounterKey(det, det.status))
              client.recordExecutionTime(testTimerKey(det), det.duration.toInt)

            case status =>
              client.incrementCounter(testCounterKey(det, status))
          }
        }
      }

      override def endGroup(name: String, result: TestResult.Value) {
        // manual switch instead of toStringing class name all the time
        result match {
          case TestResult.Passed => client.incrementCounter(keySuccess(name))
          case TestResult.Failed => client.incrementCounter(keyFail(name))
          case TestResult.Error => client.incrementCounter(keyError(name))
        }
      }

      override def endGroup(name: String, t: Throwable) {
        client.incrementCounter(keyError(name))
      }

      override def startGroup(name: String) {
        // do nothing
      }

      override def doComplete(finalResult: TestResult.Value): Unit = {
        log.debug("Final test run result: " + finalResult)
        log.info("Shutting down StatsDTestsListener client...")
        if (client != null)
          client.stop()
      }

      private def testTimerKey(det: Event): String = s"${det.fullyQualifiedName}.${testSelectorToId(det.selector)}"

      private def testSelectorToId(sel: testing.Selector): String = sanitize(sel.asInstanceOf[TestSelector].testName())

      private def testCounterKey(det: Event, status: Status): String = s"${sanitize(det.fullyQualifiedName)}.${status.toString.toLowerCase}"

      private def keySuccess(fullyQualifiedName: String): String = fullyQualifiedName + ".success"

      private def keyFail(fullyQualifiedName: String): String = fullyQualifiedName + ".fail"

      private def keyError(fullyQualifiedName: String): String = fullyQualifiedName + ".error"

      private def sanitize(s: String): String = s.replaceAll("""[^\w]""", "_")

    }

  }

  object Filter {
    object Keys {
      val excludeTestNames = settingKey[Set[String]]("Names of tests to be excluded. Not supported by MultiJVM tests. Example usage: -Dakka.test.names.exclude=TimingSpec")
      val excludeTestTags = settingKey[Set[String]]("Tags of tests to be excluded. It will not be used if you specify -Dakka.test.tags.only. Example usage: -Dakka.test.tags.exclude=long-running")
      val onlyTestTags = settingKey[Set[String]]("Tags of tests to be ran. Example usage: -Dakka.test.tags.only=long-running")
    }

    import Keys._

    private[Filter] object Params {
      val testNamesExclude = systemPropertyAsSeq("akka.test.names.exclude").toSet
      val testTagsExlcude = systemPropertyAsSeq("akka.test.tags.exclude").toSet
      val testTagsOnly = systemPropertyAsSeq("akka.test.tags.only").toSet
    }

    def settings = {
      Seq(
        excludeTestNames := Params.testNamesExclude,
        excludeTestTags := {
          if (onlyTestTags.value.isEmpty) Params.testTagsExlcude
          else Set.empty
        },
        onlyTestTags := Params.testTagsOnly,

        // add filters for tests excluded by name
        testOptions in Test <++= excludeTestNames map { _.toSeq.map(exclude => Tests.Filter(test => !test.contains(exclude))) },

        // add arguments for tests excluded by tag
        testOptions in Test <++= excludeTestTags map { tags =>
          if (tags.isEmpty) Seq.empty else Seq(Tests.Argument("-l", tags.mkString(" ")))
        },

        // add arguments for running only tests by tag
        testOptions in Test <++= onlyTestTags map { tags =>
          if (tags.isEmpty) Seq.empty else Seq(Tests.Argument("-n", tags.mkString(" ")))
        }
      )
    }

    def containsOrNotExcludesTag(tag: String) = {
      Params.testTagsOnly.contains(tag) || !Params.testTagsExlcude(tag)
    }

    def systemPropertyAsSeq(name: String): Seq[String] = {
      val prop = sys.props.get(name).getOrElse("")
      if (prop.isEmpty) Seq.empty else prop.split(",").toSeq
    }
  }

}
