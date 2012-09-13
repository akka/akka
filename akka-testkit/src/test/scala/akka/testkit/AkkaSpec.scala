/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import language.{ postfixOps, reflectiveCalls }

import org.scalatest.{ WordSpec, BeforeAndAfterAll, Tag }
import org.scalatest.matchers.MustMatchers
import akka.actor.{ Actor, Props, ActorSystem, PoisonPill, DeadLetter, ActorSystemImpl }
import akka.event.{ Logging, LoggingAdapter }
import scala.concurrent.util.duration._
import scala.concurrent.{ Await, Future }
import com.typesafe.config.{ Config, ConfigFactory }
import java.util.concurrent.TimeoutException
import akka.dispatch.Dispatchers
import akka.pattern.ask

object AkkaSpec {
  val testConf: Config = ConfigFactory.parseString("""
      akka {
        event-handlers = ["akka.testkit.TestEventListener"]
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

  def mapToConfig(map: Map[String, Any]): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(map.asJava)
  }

  def getCallerName(clazz: Class[_]): String = {
    val s = Thread.currentThread.getStackTrace map (_.getClassName) drop 1 dropWhile (_ matches ".*AkkaSpec.?$")
    val reduced = s.lastIndexWhere(_ == clazz.getName) match {
      case -1 ⇒ s
      case z  ⇒ s drop (z + 1)
    }
    reduced.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
  }

}

abstract class AkkaSpec(_system: ActorSystem)
  extends TestKit(_system) with WordSpec with MustMatchers with BeforeAndAfterAll {

  def this(config: Config) = this(ActorSystem(AkkaSpec.getCallerName(getClass),
    ConfigFactory.load(config.withFallback(AkkaSpec.testConf))))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(AkkaSpec.mapToConfig(configMap))

  def this() = this(ActorSystem(AkkaSpec.getCallerName(getClass), AkkaSpec.testConf))

  val log: LoggingAdapter = Logging(system, this.getClass)

  final override def beforeAll {
    atStartup()
  }

  final override def afterAll {
    system.shutdown()
    try system.awaitTermination(5 seconds) catch {
      case _: TimeoutException ⇒
        system.log.warning("Failed to stop [{}] within 5 seconds", system.name)
        println(system.asInstanceOf[ActorSystemImpl].printTree)
    }
    atTermination()
  }

  protected def atStartup() {}

  protected def atTermination() {}

  def spawn(dispatcherId: String = Dispatchers.DefaultDispatcherId)(body: ⇒ Unit): Unit =
    Future(body)(system.dispatchers.lookup(dispatcherId))
}
