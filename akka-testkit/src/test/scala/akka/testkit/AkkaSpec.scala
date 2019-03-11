/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import org.scalactic.{ CanEqual, TypeCheckedTripleEquals }

import language.postfixOps
import org.scalatest.{ BeforeAndAfterAll, WordSpecLike }
import org.scalatest.Matchers
import akka.actor.ActorSystem
import akka.event.{ Logging, LoggingAdapter }

import scala.concurrent.duration._
import scala.concurrent.Future
import com.typesafe.config.{ Config, ConfigFactory }
import akka.dispatch.Dispatchers
import akka.testkit.TestEvent._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Millis, Span }

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

  def mapToConfig(map: Map[String, Any]): Config = {
    import scala.collection.JavaConverters._
    ConfigFactory.parseMap(map.asJava)
  }

  def getCallerName(clazz: Class[_]): String = {
    val s = Thread.currentThread.getStackTrace
      .map(_.getClassName)
      .drop(1)
      .dropWhile(_.matches("(java.lang.Thread|.*AkkaSpec.*|.*\\.StreamSpec.*|.*MultiNodeSpec.*|.*\\.Abstract.*)"))
    val reduced = s.lastIndexWhere(_ == clazz.getName) match {
      case -1 => s
      case z  => s.drop(z + 1)
    }
    reduced.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
  }

}

abstract class AkkaSpec(_system: ActorSystem)
    extends TestKit(_system)
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with WatchedByCoroner
    with TypeCheckedTripleEquals
    with ScalaFutures {

  implicit val patience = PatienceConfig(testKitSettings.DefaultTimeout.duration, Span(100, Millis))

  def this(config: Config) =
    this(ActorSystem(AkkaSpec.getCallerName(getClass), ConfigFactory.load(config.withFallback(AkkaSpec.testConf))))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(AkkaSpec.mapToConfig(configMap))

  def this() = this(ActorSystem(AkkaSpec.getCallerName(getClass), AkkaSpec.testConf))

  val log: LoggingAdapter = Logging(system, this.getClass)

  override val invokeBeforeAllAndAfterAllEvenIfNoTestsAreExpected = true

  final override def beforeAll: Unit = {
    startCoroner()
    atStartup()
  }

  final override def afterAll: Unit = {
    beforeTermination()
    shutdown()
    afterTermination()
    stopCoroner()
  }

  protected def atStartup(): Unit = {}

  protected def beforeTermination(): Unit = {}

  protected def afterTermination(): Unit = {}

  def spawn(dispatcherId: String = Dispatchers.DefaultDispatcherId)(body: => Unit): Unit =
    Future(body)(system.dispatchers.lookup(dispatcherId))

  override def expectedTestDuration: FiniteDuration = 60 seconds

  def muteDeadLetters(messageClasses: Class[_]*)(sys: ActorSystem = system): Unit =
    if (!sys.log.isDebugEnabled) {
      def mute(clazz: Class[_]): Unit =
        sys.eventStream.publish(Mute(DeadLettersFilter(clazz)(occurrences = Int.MaxValue)))
      if (messageClasses.isEmpty) mute(classOf[AnyRef])
      else messageClasses.foreach(mute)
    }

  // for ScalaTest === compare of Class objects
  implicit def classEqualityConstraint[A, B]: CanEqual[Class[A], Class[B]] =
    new CanEqual[Class[A], Class[B]] {
      def areEqual(a: Class[A], b: Class[B]) = a == b
    }

  implicit def setEqualityConstraint[A, T <: Set[_ <: A]]: CanEqual[Set[A], T] =
    new CanEqual[Set[A], T] {
      def areEqual(a: Set[A], b: T) = a == b
    }
}
