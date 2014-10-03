/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream.scaladsl2.FlowMaterializer
import org.reactivestreams.{ Subscriber, Publisher }

import akka.stream.javadsl
import akka.stream.scaladsl2

import scala.concurrent.Future

abstract class Drain[-In] extends javadsl.SinkAdapter[In] {
  protected def delegate: scaladsl2.Drain[In]

  override def runWith[T](tap: javadsl.TapWithKey[In, T], materializer: FlowMaterializer): T = {
    val sTap = tap.asScala
    sTap.connect(asScala).run()(materializer).materializedTap(sTap).asInstanceOf[T]
  }

  override def runWith(tap: javadsl.SimpleTap[In], materializer: FlowMaterializer): Unit = {
    tap.asScala.connect(asScala).run()(materializer)
  }
}

abstract class SimpleDrain[-In] extends javadsl.Drain[In] {
  override def asScala: scaladsl2.SimpleDrain[In] = super.asScala.asInstanceOf[scaladsl2.SimpleDrain[In]]
}

abstract class DrainWithKey[-In, M] extends javadsl.Drain[In] {
  override def asScala: scaladsl2.DrainWithKey[In] = super.asScala.asInstanceOf[scaladsl2.DrainWithKey[In]]
}

// adapters //

object SubscriberDrain {
  def create[In](subs: Subscriber[In]): SubscriberDrain[In] =
    new SubscriberDrain(scaladsl2.SubscriberDrain[In](subs))
}
final class SubscriberDrain[In](protected val delegate: scaladsl2.SubscriberDrain[In]) extends javadsl.DrainWithKey[In, Subscriber[In]]

object PublisherDrain {
  def create[In](): PublisherDrain[In] =
    new PublisherDrain(scaladsl2.PublisherDrain[In]())
}
final class PublisherDrain[In](protected val delegate: scaladsl2.PublisherDrain[In]) extends javadsl.DrainWithKey[In, Publisher[In]]

object FanoutPublisherDrain {
  def create[In](initialBufferSize: Int, maximumBufferSize: Int): FanoutPublisherDrain[In] =
    new FanoutPublisherDrain(scaladsl2.PublisherDrain.withFanout[In](initialBufferSize, maximumBufferSize))
}
final class FanoutPublisherDrain[In](protected val delegate: scaladsl2.FanoutPublisherDrain[In]) extends javadsl.DrainWithKey[In, Publisher[In]]

object FutureDrain {
  def create[In](): FutureDrain[In] =
    new FutureDrain[In](scaladsl2.FutureDrain[In]())
}
final class FutureDrain[In](protected val delegate: scaladsl2.FutureDrain[In]) extends javadsl.DrainWithKey[In, Future[In]]

object BlackholeDrain {
  def create[In](): BlackholeDrain[In] =
    new BlackholeDrain[In](scaladsl2.BlackholeDrain)
}
final class BlackholeDrain[In](protected val delegate: scaladsl2.BlackholeDrain.type) extends javadsl.SimpleDrain[In]

object OnCompleteDrain {
  def create[In](onComplete: akka.dispatch.OnComplete[Unit]): OnCompleteDrain[In] =
    new OnCompleteDrain[In](scaladsl2.OnCompleteDrain[In](x ⇒ onComplete.apply(x)))
}
final class OnCompleteDrain[In](protected val delegate: scaladsl2.OnCompleteDrain[In]) extends javadsl.SimpleDrain[In]

object ForeachDrain {
  def create[In](f: japi.Procedure[In]): ForeachDrain[In] =
    new ForeachDrain[In](new scaladsl2.ForeachDrain[In](x ⇒ f(x)))
}
final class ForeachDrain[In](protected val delegate: scaladsl2.ForeachDrain[In]) extends javadsl.DrainWithKey[In, Future[Unit]]

object FoldDrain {
  def create[U, In](zero: U, f: japi.Function2[U, In, U]): FoldDrain[U, In] =
    new FoldDrain[U, In](new scaladsl2.FoldDrain[U, In](zero)(f.apply))
}
final class FoldDrain[U, In](protected val delegate: scaladsl2.FoldDrain[U, In]) extends javadsl.DrainWithKey[In, Future[U]]
