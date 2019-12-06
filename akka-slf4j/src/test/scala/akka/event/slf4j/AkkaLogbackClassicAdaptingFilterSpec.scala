/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.event.slf4j

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Props
import akka.testkit.AkkaSpec
import ch.qos.logback.core.OutputStreamAppender

import scala.concurrent.duration._

object AkkaLogbackClassicAdaptingFilterSpec {

  val config = """
    akka {
      loglevel = INFO
      loggers = ["akka.event.slf4j.Slf4jLogger"]
      logger-startup-timeout = 30s
    }
    """

  def loggingActorProps = Props(new LoggingActor)
  class LoggingActor extends Actor with ActorLogging {
    def receive = {
      case _ =>
        log.info(Thread.currentThread().getName)
    }
  }
  val output = new ByteArrayOutputStream
  def outputString: String = output.toString("UTF-8")

  class TestAppender extends OutputStreamAppender {

    override def start(): Unit = {
      setOutputStream(output)
      super.start()
    }
  }
}

class AkkaLogbackClassicAdaptingFilterSpec extends AkkaSpec(AkkaLogbackClassicAdaptingFilterSpec.config) {

  import AkkaLogbackClassicAdaptingFilterSpec._

  "The Akka classic logback adapting filter" must {

    "put source thread in entries" in {
      val ref = system.actorOf(loggingActorProps, "LoggingActor")

      ref ! "log!"
      awaitCond(outputString.contains("----"), 5.seconds)
      val keyValue = outputString
        .dropRight(5)
        .split(' ')
        .toList
        .map(_.split('='))
        .map { case Array(key, value) => key -> value }
        .toMap
      // we put current thread as message in log entry
      keyValue("thread") should ===(keyValue("msg"))

      // not watertight but better than nothing - compare that the event timestamp is the same as the
      // akkaTimestampMillis when rendered to string with millis
      val akkaTimestampMillis = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(keyValue("akkaTimestampMillis").drop(1).dropRight(1).toLong),
        TimeZone.getDefault.toZoneId)
      val formattedAkkaTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'hh:mm:ss.SSS'Z'").format(akkaTimestampMillis)
      keyValue("timestamp").drop(1).dropRight(1) should ===(formattedAkkaTimestamp)

    }

  }

}
