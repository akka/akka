/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import language.implicitConversions
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.charset.Charset
import com.typesafe.config.Config
import org.reactivestreams.Publisher
import akka.event.LoggingAdapter
import akka.util.ByteString
import akka.actor._
import akka.stream.scaladsl.Flow
import akka.stream.{ Transformer, FlattenStrategy, FlowMaterializer }
import scala.collection.LinearSeq
import scala.util.matching.Regex

package object util {
  private[http] val UTF8 = Charset.forName("UTF8")

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
  private[http] implicit def enhanceSeq[T](seq: Seq[T]): EnhancedSeq[T] = seq match {
    case x: LinearSeq[_]  ⇒ new EnhancedLinearSeq[T](x)
    case x: IndexedSeq[_] ⇒ new EnhancedIndexedSeq[T](x)
  }

  private[http] implicit class FlowWithHeadAndTail[T](val underlying: Flow[Publisher[T]]) extends AnyVal {
    def headAndTail(materializer: FlowMaterializer): Flow[(T, Publisher[T])] =
      underlying.map { p ⇒
        Flow(p).prefixAndTail(1).map { case (prefix, tail) ⇒ (prefix.head, tail) }.toPublisher(materializer)
      }.flatten(FlattenStrategy.Concat())
  }

  private[http] implicit class FlowWithPrintEvent[T](val underlying: Flow[T]) {
    def printEvent(marker: String): Flow[T] =
      underlying.transform {
        new Transformer[T, T] {
          def onNext(element: T) = {
            println(s"$marker: $element")
            element :: Nil
          }
          override def onTermination(e: Option[Throwable]) = {
            println(s"$marker: Terminated with error $e")
            Nil
          }
        }
      }
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

