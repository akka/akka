/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl

import language.implicitConversions
import language.higherKinds
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import com.typesafe.config.Config
import akka.stream.scaladsl.{ FlattenStrategy, Flow, Source }
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
  private[http] implicit def enhanceByteStrings(byteStrings: TraversableOnce[ByteString]): EnhancedByteStringTraversableOnce =
    new EnhancedByteStringTraversableOnce(byteStrings)
  private[http] implicit def enhanceByteStrings[Mat](byteStrings: Source[ByteString, Mat]): EnhancedByteStringSource[Mat] =
    new EnhancedByteStringSource(byteStrings)

  private[http] implicit class SourceWithHeadAndTail[T, Mat](val underlying: Source[Source[T, Any], Mat]) extends AnyVal {
    def headAndTail: Source[(T, Source[T, Unit]), Mat] =
      underlying.map {
        _.prefixAndTail(1)
          .filter(_._1.nonEmpty)
          .map { case (prefix, tail) ⇒ (prefix.head, tail) }
      }
        .flatten(FlattenStrategy.concat)
  }

  private[http] implicit class FlowWithHeadAndTail[In, Out, Mat](val underlying: Flow[In, Source[Out, Any], Mat]) extends AnyVal {
    def headAndTail: Flow[In, (Out, Source[Out, Unit]), Mat] =
      underlying.map {
        _.prefixAndTail(1)
          .filter(_._1.nonEmpty)
          .map { case (prefix, tail) ⇒ (prefix.head, tail) }
      }
        .flatten(FlattenStrategy.concat)
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
        eventStreamLogger = system.actorOf(Props[util.EventStreamLogger], name = "event-stream-logger")
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

  private[http] def errorLogger(log: LoggingAdapter, msg: String): PushStage[ByteString, ByteString] =
    new PushStage[ByteString, ByteString] {
      override def onPush(element: ByteString, ctx: Context[ByteString]): SyncDirective = ctx.push(element)
      override def onUpstreamFailure(cause: Throwable, ctx: Context[ByteString]): TerminationDirective = {
        log.error(cause, msg)
        super.onUpstreamFailure(cause, ctx)
      }
    }

  private[this] val _identityFunc: Any ⇒ Any = x ⇒ x
  /** Returns a constant identity function to avoid allocating the closure */
  private[http] def identityFunc[T]: T ⇒ T = _identityFunc.asInstanceOf[T ⇒ T]
}

package util {

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
}
