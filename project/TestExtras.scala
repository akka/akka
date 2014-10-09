package akka

import sbt._
import Keys._

import com.timgroup.statsd.{StatsDClientErrorHandler, NonBlockingStatsDClient, StatsDClient}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import sbt.testing.{TestSelector, Status, Event}
import java.io.File
import com.typesafe.tools.mima.plugin.MimaKeys._
import scala.Some
import scala.Some
import java.io.File
import sbt.File
import scala.util.Try

object TestExtras {

  object JUnitFileReporting {
    val settings = Seq(
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-a"),
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v"),
      // we can enable junit-style reports everywhere with this
      testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-u", (target.value / "test-reports").getAbsolutePath)
    )
  }

  object StatsDMetrics {

    val statsd = config("statsd") extend Test

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
        // for test
        enabled.in(statsd).value match {
          case true => Seq(StatsDTestListener(streams.value.log, prefix.in(statsd).value, host.in(statsd).value, port.in(statsd).value))
          case _ => Nil
        }
      },
      testListeners ++= {
        // for testOnly
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
        event.detail foreach {
          det =>
            det.status match {
              case Status.Success =>
                client.incrementCounter(testCounterKey(det, det.status))
                client.recordExecutionTime(testTimerKey(det), det.duration.toInt + 1000 + (Math.random() * 1000).toInt)

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

      private def testSelectorToId(sel: testing.Selector): String = sel.asInstanceOf[TestSelector].testName().replaceAll("[. ']", "_")

      private def testCounterKey(det: Event, status: Status): String = s"${det.fullyQualifiedName}.${status.toString.toLowerCase}"

      private def keySuccess(fullyQualifiedName: String): String = fullyQualifiedName + ".success"

      private def keyFail(fullyQualifiedName: String): String = fullyQualifiedName + ".fail"

      private def keyError(fullyQualifiedName: String): String = fullyQualifiedName + ".error"

    }

  }

}