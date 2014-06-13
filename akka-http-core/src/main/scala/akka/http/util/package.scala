/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import language.implicitConversions
import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.charset.Charset
import com.typesafe.config.Config
import org.reactivestreams.api.Producer
import akka.event.LoggingAdapter
import akka.util.ByteString
import akka.actor.{ ActorRefFactory, ActorContext, ActorSystem }
import akka.stream.scaladsl.Flow
import akka.stream.{ Transformer, FlattenStrategy, FlowMaterializer }

package object util {
  private[http] val UTF8 = Charset.forName("UTF8")

  private[http] def actorSystem(implicit refFactory: ActorRefFactory): ActorSystem =
    refFactory match {
      case x: ActorContext ⇒ actorSystem(x.system)
      case x: ActorSystem  ⇒ x
      case _               ⇒ throw new IllegalStateException
    }

  private[http] implicit def enhanceByteArray(array: Array[Byte]): EnhancedByteArray = new EnhancedByteArray(array)
  private[http] implicit def enhanceConfig(config: Config): EnhancedConfig = new EnhancedConfig(config)
  private[http] implicit def enhanceString_(s: String): EnhancedString = new EnhancedString(s)

  private[http] implicit class FlowWithHeadAndTail[T](val underlying: Flow[Producer[T]]) extends AnyVal {
    def headAndTail(materializer: FlowMaterializer): Flow[(T, Producer[T])] =
      underlying.map { p ⇒
        Flow(p).prefixAndTail(1).map { case (prefix, tail) ⇒ (prefix.head, tail) }.toProducer(materializer)
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
}

