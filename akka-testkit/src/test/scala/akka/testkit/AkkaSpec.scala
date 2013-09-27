/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import language.{ postfixOps, reflectiveCalls }
import org.scalatest.{ WordSpecLike, BeforeAndAfterAll, Tag, Suite }
import org.scalatest.matchers.MustMatchers
import akka.actor.{ Actor, Props, ActorSystem, PoisonPill, DeadLetter, ActorSystemImpl }
import akka.event.{ Logging, LoggingAdapter }
import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import com.typesafe.config.{ Config, ConfigFactory }
import java.util.concurrent.TimeoutException
import akka.dispatch.Dispatchers
import akka.pattern.ask
import akka.testkit.TestEvent._
import scala.util.control.NonFatal

object AkkaSpec {
  val testConf: Config = ConfigFactory.parseString("""
      akka {
        loggers = ["akka.testkit.TestEventListener"]
        loglevel = "WARNING"
        stdout-loglevel = "WARNING"
        actor {
          default-dispatcher {
            executor = "fork-join-executor"
            fork-join-executor {
              parallelism-min = 8
              parallelism-factor = 2.0
              parallelism-max = 8
            }
          }
        }
      }
      """)

  val bufferLoggingOnConf: Config = ConfigFactory.parseString("akka.test.buffer-logging = on")

  def mapToConfig(map: Map[String, Any]): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(map.asJava)
  }

  def getCallerName(clazz: Class[_]): String = {
    val s = (Thread.currentThread.getStackTrace map (_.getClassName) drop 1)
      .dropWhile(_ matches "(java.lang.Thread|.*AkkaSpec.?$)")
    val reduced = s.lastIndexWhere(_ == clazz.getName) match {
      case -1 ⇒ s
      case z  ⇒ s drop (z + 1)
    }
    reduced.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
  }

}

abstract class AkkaSpec(_system: ActorSystem)
  extends TestKit(_system) with Suite with WordSpecLike with MustMatchers with BeforeAndAfterAll with WatchedByCoroner {

  def this(config: Config) = this(ActorSystem(AkkaSpec.getCallerName(getClass),
    ConfigFactory.load(config.withFallback(AkkaSpec.testConf).withFallback(AkkaSpec.bufferLoggingOnConf))))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(AkkaSpec.mapToConfig(configMap))

  def this() = this(ActorSystem(AkkaSpec.getCallerName(getClass), AkkaSpec.testConf))

  val log: LoggingAdapter = Logging(system, this.getClass)

  final override def beforeAll {
    startCoroner
    atStartup()
  }

  final override def afterAll {
    beforeTermination()
    shutdown(system)
    afterTermination()
    stopCoroner()
  }

  protected def atStartup() {}

  protected def beforeTermination() {}

  protected def afterTermination() {}

  override protected def withFixture(test: NoArgTest): Unit =
    if (TestKitExtension(system).BufferLogging) {
      val startTime = System.nanoTime()
      def durationMillis = (System.nanoTime - startTime).nanos.toMillis
      try {
        super.withFixture(test)
        log.info("Succesfull test [{}] in [{} ms]", test.name, durationMillis)
      } catch {
        case NonFatal(e) ⇒
          system.eventStream.publish(TestEvent.Flush)
          log.error("Failed test [{}] in [{} ms]", test.name, durationMillis)
          throw e
      }
    } else super.withFixture(test)

  def spawn(dispatcherId: String = Dispatchers.DefaultDispatcherId)(body: ⇒ Unit): Unit =
    Future(body)(system.dispatchers.lookup(dispatcherId))

  override def expectedTestDuration: FiniteDuration = 60 seconds

  def muteDeadLetters(messageClasses: Class[_]*)(sys: ActorSystem = system): Unit =
    if (!sys.log.isDebugEnabled) {
      def mute(clazz: Class[_]): Unit =
        sys.eventStream.publish(Mute(DeadLettersFilter(clazz)(occurrences = Int.MaxValue)))
      if (messageClasses.isEmpty) mute(classOf[AnyRef])
      else messageClasses foreach mute
    }

}
