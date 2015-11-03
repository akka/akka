/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl

import java.net.InetSocketAddress

import language.implicitConversions
import language.higherKinds
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import com.typesafe.config.Config
import akka.stream.scaladsl.{ Flow, Source }
import akka.stream.stage._
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
import scala.reflect.ClassTag
import scala.util.{ Failure, Success }
import scala.util.matching.Regex
import akka.event.LoggingAdapter
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
  private[http] implicit def enhanceInetSocketAddress(address: InetSocketAddress): EnhancedInetSocketAddress =
    new EnhancedInetSocketAddress(address)
  private[http] implicit def enhanceByteStrings(byteStrings: TraversableOnce[ByteString]): EnhancedByteStringTraversableOnce =
    new EnhancedByteStringTraversableOnce(byteStrings)
  private[http] implicit def enhanceByteStringsMat[Mat](byteStrings: Source[ByteString, Mat]): EnhancedByteStringSource[Mat] =
    new EnhancedByteStringSource(byteStrings)

  private[http] def headAndTailFlow[T]: Flow[Source[T, Any], (T, Source[T, Unit]), Unit] =
    Flow[Source[T, Any]]
      .flatMapConcat {
        _.prefixAndTail(1)
          .filter(_._1.nonEmpty)
          .map { case (prefix, tail) ⇒ (prefix.head, tail) }
      }

  private[http] def printEvent[T](marker: String): Flow[T, T, Unit] =
    Flow[T].transform(() ⇒ new PushPullStage[T, T] {
      override def onPush(element: T, ctx: Context[T]): SyncDirective = {
        println(s"$marker: $element")
        ctx.push(element)
      }
      override def onPull(ctx: Context[T]): SyncDirective = {
        println(s"$marker: PULL")
        ctx.pull()
      }
      override def onUpstreamFailure(cause: Throwable, ctx: Context[T]): TerminationDirective = {
        println(s"$marker: Error $cause")
        super.onUpstreamFailure(cause, ctx)
      }
      override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = {
        println(s"$marker: Complete")
        super.onUpstreamFinish(ctx)
      }
      override def onDownstreamFinish(ctx: Context[T]): TerminationDirective = {
        println(s"$marker: Cancel")
        super.onDownstreamFinish(ctx)
      }
    })

  private[this] var eventStreamLogger: ActorRef = _
  private[http] def installEventStreamLoggerFor(channel: Class[_])(implicit system: ActorSystem): Unit = {
    synchronized {
      if (eventStreamLogger == null)
        eventStreamLogger = system.actorOf(Props[util.EventStreamLogger].withDeploy(Deploy.local), name = "event-stream-logger")
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

  private[http] def errorLogger[T](log: LoggingAdapter, msg: String): PushStage[T, T] =
    new PushStage[T, T] {
      override def onPush(element: T, ctx: Context[T]): SyncDirective = ctx.push(element)
      override def onUpstreamFailure(cause: Throwable, ctx: Context[T]): TerminationDirective = {
        log.error(cause, msg)
        super.onUpstreamFailure(cause, ctx)
      }
    }

  private[this] val _identityFunc: Any ⇒ Any = x ⇒ x
  /** Returns a constant identity function to avoid allocating the closure */
  private[http] def identityFunc[T]: T ⇒ T = _identityFunc.asInstanceOf[T ⇒ T]

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
  import akka.stream.{ Attributes, Outlet, Inlet, FlowShape }
  import scala.concurrent.duration.FiniteDuration

  private[http] class ToStrict(timeout: FiniteDuration, contentType: ContentType)
    extends GraphStage[FlowShape[ByteString, HttpEntity.Strict]] {

    val in = Inlet[ByteString]("in")
    val out = Outlet[HttpEntity.Strict]("out")
    override val shape = FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new TimerGraphStageLogic(shape) {
      var bytes = ByteString.newBuilder
      private var emptyStream = false

      override def preStart(): Unit = scheduleOnce("ToStrictTimeoutTimer", timeout)

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (emptyStream) {
            push(out, HttpEntity.Strict(contentType, ByteString.empty))
            completeStage()
          } else pull(in)
        }
      })

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          bytes ++= grab(in)
          pull(in)
        }
        override def onUpstreamFinish(): Unit = {
          if (isAvailable(out)) {
            push(out, HttpEntity.Strict(contentType, bytes.result()))
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

  // Provisioning of actor names composed of a common prefix + a counter. According to #16613 not in scope as public API.
  private[http] final class SeqActorName(prefix: String) extends AtomicInteger {
    def next(): String = prefix + '-' + getAndIncrement()
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

  private[http] class ReadTheDocumentationException(message: String) extends RuntimeException(message)
}
