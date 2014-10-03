/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import language.implicitConversions
import java.nio.charset.Charset
import com.typesafe.config.Config
import org.reactivestreams.{ Subscription, Subscriber, Publisher }
import scala.util.matching.Regex
import akka.event.LoggingAdapter
import akka.util.ByteString
import akka.actor._
import akka.stream.scaladsl.Flow
import akka.stream.{ Transformer, FlattenStrategy, FlowMaterializer }

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

  private[http] implicit class FlowWithHeadAndTail[T](val underlying: Flow[Publisher[T]]) extends AnyVal {
    def headAndTail(implicit fm: FlowMaterializer): Flow[(T, Publisher[T])] =
      underlying.map { p ⇒
        Flow(p).prefixAndTail(1).map { case (prefix, tail) ⇒ (prefix.head, tail) }.toPublisher()
      }.flatten(FlattenStrategy.Concat())
  }

  private[http] implicit class FlowWithPrintEvent[T](val underlying: Flow[T]) {
    def printEvent(marker: String): Flow[T] =
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

  // FIXME: This should be fixed by a CancelledDrain once #15903 is done. Currently this is needed for the tests
  private[http] def cancelledSusbcriber[T]: Subscriber[T] = new Subscriber[T] {
    override def onSubscribe(s: Subscription): Unit = s.cancel()
    override def onError(t: Throwable): Unit = ()
    override def onComplete(): Unit = ()
    override def onNext(t: T): Unit = ()
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

