/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import java.nio.charset.Charset

import akka.actor._
import akka.event.LoggingAdapter
import akka.stream.scaladsl2.{ ProcessorFlow, FlowOps, FlattenStrategy, FlowWithSource }
import akka.stream.Transformer
import akka.util.ByteString
import com.typesafe.config.Config

import scala.language.implicitConversions
import scala.util.matching.Regex

package object util {
  private[http] val UTF8 = Charset.forName("UTF8")
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

  private[http] implicit class FlowWithSourceHeadAndTail[A, T](val underlying: FlowWithSource[A, FlowWithSource[T, T]]) extends AnyVal {
    def headAndTail: FlowWithSource[A, (T, FlowWithSource[T, T])] =
      underlying.map { _.prefixAndTail(1).map { case (prefix, tail) ⇒ (prefix.head, tail) } }
        .flatten(FlattenStrategy.concat)
  }

  private[http] implicit class FlowProcessorWithHeadAndTail[A, T](val underlying: ProcessorFlow[A, FlowWithSource[T, T]]) extends AnyVal {
    def headAndTail: ProcessorFlow[A, (T, FlowWithSource[T, T])] =
      underlying.map { _.prefixAndTail(1).map { case (prefix, tail) ⇒ (prefix.head, tail) } }
        .flatten(FlattenStrategy.concat)
  }

  private[http] implicit class FlowWithPrintEvent[T](val underlying: FlowWithSource[_, T]) {
    def printEvent(marker: String): FlowWithSource[_, T] =
      underlying.transform("transform",
        () ⇒ new Transformer[T, T] {
          def onNext(element: T) = {
            println(s"$marker: $element")
            element :: Nil
          }
          override def onTermination(e: Option[Throwable]) = {
            println(s"$marker: Terminated with error $e")
            Nil
          }
        })
  }

  private[http] def errorLogger(log: LoggingAdapter, msg: String): Transformer[ByteString, ByteString] =
    new Transformer[ByteString, ByteString] {
      def onNext(element: ByteString) = element :: Nil
      override def onError(cause: Throwable): Unit = log.error(cause, msg)
    }

  private[this] val _identityFunc: Any ⇒ Any = x ⇒ x
  /** Returns a constant identity function to avoid allocating the closure */
  def identityFunc[T]: T ⇒ T = _identityFunc.asInstanceOf[T ⇒ T]
}

