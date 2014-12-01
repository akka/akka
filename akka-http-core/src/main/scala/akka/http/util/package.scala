/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import language.implicitConversions
import language.higherKinds
import scala.collection.immutable
import java.nio.charset.Charset
import com.typesafe.config.Config
import akka.stream.{ FlowMaterializer, FlattenStrategy }
import akka.stream.scaladsl.{ Flow, Source }
import akka.stream.stage._
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
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
  private[http] implicit def enhanceByteStrings(byteStrings: Source[ByteString]): EnhancedByteStringSource =
    new EnhancedByteStringSource(byteStrings)

  private[http] implicit class SourceWithHeadAndTail[T](val underlying: Source[Source[T]]) extends AnyVal {
    def headAndTail: Source[(T, Source[T])] =
      underlying.map { _.prefixAndTail(1).map { case (prefix, tail) ⇒ (prefix.head, tail) } }
        .flatten(FlattenStrategy.concat)
  }

  private[http] implicit class FlowWithHeadAndTail[In, Out](val underlying: Flow[In, Source[Out]]) extends AnyVal {
    def headAndTail: Flow[In, (Out, Source[Out])] =
      underlying.map { _.prefixAndTail(1).map { case (prefix, tail) ⇒ (prefix.head, tail) } }
        .flatten(FlattenStrategy.concat)
  }

  private[http] implicit class EnhancedSource[T](val underlying: Source[T]) {
    def printEvent(marker: String): Source[T] =
      underlying.transform(() ⇒ new PushStage[T, T] {
        override def onPush(element: T, ctx: Context[T]): Directive = {
          println(s"$marker: $element")
          ctx.push(element)
        }
        override def onUpstreamFailure(cause: Throwable, ctx: Context[T]): TerminationDirective = {
          println(s"$marker: Failure $cause")
          super.onUpstreamFailure(cause, ctx)
        }
        override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = {
          println(s"$marker: Terminated")
          super.onUpstreamFinish(ctx)
        }
      })

    /**
     * Drain this stream into a Vector and provide it as a future value.
     *
     * FIXME: Should be part of akka-streams
     */
    def collectAll(implicit materializer: FlowMaterializer): Future[immutable.Seq[T]] =
      underlying.fold(Vector.empty[T])(_ :+ _)
  }

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
      override def onPush(element: ByteString, ctx: Context[ByteString]): Directive = ctx.push(element)
      override def onUpstreamFailure(cause: Throwable, ctx: Context[ByteString]): TerminationDirective = {
        log.error(cause, msg)
        super.onUpstreamFailure(cause, ctx)
      }
    }

  private[this] val _identityFunc: Any ⇒ Any = x ⇒ x
  /** Returns a constant identity function to avoid allocating the closure */
  def identityFunc[T]: T ⇒ T = _identityFunc.asInstanceOf[T ⇒ T]

  def humanReadableByteCount(bytes: Long, si: Boolean): String = {
    val unit = if (si) 1000 else 1024
    if (bytes >= unit) {
      val exp = (math.log(bytes) / math.log(unit)).toInt
      val pre = if (si) "kMGTPE".charAt(exp - 1).toString else "KMGTPE".charAt(exp - 1).toString + 'i'
      "%.1f %sB" format (bytes / math.pow(unit, exp), pre)
    } else bytes.toString + "  B"
  }
}

