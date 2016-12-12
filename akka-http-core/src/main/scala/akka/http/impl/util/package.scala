/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl

import akka.NotUsed
import akka.stream.{ Attributes, Outlet, Inlet, FlowShape }

import language.implicitConversions
import java.nio.charset.Charset
import com.typesafe.config.Config
import akka.stream.scaladsl.{ Flow, Source }
import akka.stream.stage._
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
import scala.reflect.ClassTag
import scala.util.{ Failure, Success }
import scala.util.matching.Regex
import akka.util.ByteString
import akka.actor._

package object util {
  private[http] val UTF8 = Charset.forName("UTF8")
  private[http] val ASCII = Charset.forName("ASCII")
  private[http] val ISO88591 = Charset.forName("ISO-8859-1")

  private[http] val EmptyByteArray = Array.empty[Byte]

  private[http] def actorSystem(implicit refFactory: ActorRefFactory): ExtendedActorSystem =
    refFactory match {
      case x: ActorContext        ⇒ actorSystem(x.system)
      case x: ExtendedActorSystem ⇒ x
      case _                      ⇒ throw new IllegalStateException
    }

  private[http] implicit def enhanceByteArray(array: Array[Byte]): EnhancedByteArray = new EnhancedByteArray(array)
  private[http] implicit def enhanceConfig(config: Config): EnhancedConfig = new EnhancedConfig(config)
  private[http] implicit def enhanceString_(s: String): EnhancedString = new EnhancedString(s)
  private[http] implicit def enhanceRegex(regex: Regex): EnhancedRegex = new EnhancedRegex(regex)
  private[http] implicit def enhanceByteStrings(byteStrings: TraversableOnce[ByteString]): EnhancedByteStringTraversableOnce =
    new EnhancedByteStringTraversableOnce(byteStrings)
  private[http] implicit def enhanceByteStringsMat[Mat](byteStrings: Source[ByteString, Mat]): EnhancedByteStringSource[Mat] =
    new EnhancedByteStringSource(byteStrings)

  private[this] var eventStreamLogger: ActorRef = _
  private[http] def installEventStreamLoggerFor(channel: Class[_])(implicit system: ActorSystem): Unit = {
    synchronized {
      if (eventStreamLogger == null)
        eventStreamLogger = system.actorOf(Props[util.EventStreamLogger]().withDeploy(Deploy.local), name = "event-stream-logger")
    }
    system.eventStream.subscribe(eventStreamLogger, channel)
  }
  private[http] def installEventStreamLoggerFor[T](implicit ct: ClassTag[T], system: ActorSystem): Unit =
    installEventStreamLoggerFor(ct.runtimeClass)

  private[http] implicit class AddFutureAwaitResult[T](future: Future[T]) {
    /** "Safe" Await.result that doesn't throw away half of the stacktrace */
    def awaitResult(atMost: Duration): T = {
      Await.ready(future, atMost)
      future.value.get match {
        case Success(t)  ⇒ t
        case Failure(ex) ⇒ throw new RuntimeException("Trying to await result of failed Future, see the cause for the original problem.", ex)
      }
    }
  }

  private[http] def humanReadableByteCount(bytes: Long, si: Boolean): String = {
    val unit = if (si) 1000 else 1024
    if (bytes >= unit) {
      val exp = (math.log(bytes) / math.log(unit)).toInt
      val pre = if (si) "kMGTPE".charAt(exp - 1).toString else "KMGTPE".charAt(exp - 1).toString + 'i'
      "%.1f %sB" format (bytes / math.pow(unit, exp), pre)
    } else bytes.toString + "  B"
  }
}

package util {

  import akka.http.scaladsl.model.{ ContentType, HttpEntity }
  import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
  import akka.stream.{ Attributes, Outlet, Inlet, FlowShape }
  import scala.concurrent.duration.FiniteDuration

  private[http] class ToStrict(timeout: FiniteDuration, contentType: ContentType)
    extends GraphStage[FlowShape[ByteString, HttpEntity.Strict]] {

    val byteStringIn = Inlet[ByteString]("ToStrict.byteStringIn")
    val httpEntityOut = Outlet[HttpEntity.Strict]("ToStrict.httpEntityOut")

    override def initialAttributes = Attributes.name("ToStrict")

    override val shape = FlowShape(byteStringIn, httpEntityOut)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      val bytes = ByteString.newBuilder
      private var emptyStream = false

      override def preStart(): Unit = scheduleOnce("ToStrictTimeoutTimer", timeout)

      setHandler(httpEntityOut, new OutHandler {
        override def onPull(): Unit = {
          if (emptyStream) {
            push(httpEntityOut, HttpEntity.Strict(contentType, ByteString.empty))
            completeStage()
          } else pull(byteStringIn)
        }
      })

      setHandler(byteStringIn, new InHandler {
        override def onPush(): Unit = {
          bytes ++= grab(byteStringIn)
          pull(byteStringIn)
        }
        override def onUpstreamFinish(): Unit = {
          if (isAvailable(httpEntityOut)) {
            push(httpEntityOut, HttpEntity.Strict(contentType, bytes.result()))
            completeStage()
          } else emptyStream = true
        }
      })

      override def onTimer(key: Any): Unit =
        failStage(new java.util.concurrent.TimeoutException(
          s"HttpEntity.toStrict timed out after $timeout while still waiting for outstanding data"))
    }

    override def toString = "ToStrict"
  }

  private[http] class EventStreamLogger extends Actor with ActorLogging {
    def receive = { case x ⇒ log.warning(x.toString) }
  }

  private[http] trait LogMessages extends ActorLogging { this: Actor ⇒
    def logMessages(mark: String = "")(r: Receive): Receive =
      new Receive {
        def isDefinedAt(x: Any): Boolean = r.isDefinedAt(x)
        def apply(x: Any): Unit = {
          log.debug(s"[$mark] received: $x")
          r(x)
        }
      }
  }
}
