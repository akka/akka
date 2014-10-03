/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import akka.stream.javadsl
import akka.stream.scaladsl2

import org.reactivestreams.{ Publisher, Subscriber }

import concurrent.duration.FiniteDuration
import scala.concurrent.Future

abstract class Tap[+Out] extends javadsl.SourceAdapter[Out] {
  def delegate: scaladsl2.Source[Out]
}

abstract class SimpleTap[+Out] extends Tap[Out] {
  override def asScala: scaladsl2.SimpleTap[Out] = super.asScala.asInstanceOf[scaladsl2.SimpleTap[Out]]
}

abstract class TapWithKey[+Out, T] extends Tap[Out] {
  override def asScala: scaladsl2.TapWithKey[Out] = super.asScala.asInstanceOf[scaladsl2.TapWithKey[Out]]
}

// adapters //

object SubscriberTap {
  def create[O]() = new SubscriberTap(new scaladsl2.SubscriberTap[O])
}
final case class SubscriberTap[O](delegate: scaladsl2.SubscriberTap[O]) extends javadsl.TapWithKey[O, Subscriber[O]]

object PublisherTap {
  def create[O](p: Publisher[O]) = new PublisherTap(new scaladsl2.PublisherTap[O](p))
}
final case class PublisherTap[O](delegate: scaladsl2.PublisherTap[O]) extends javadsl.TapWithKey[O, Publisher[O]]

object IteratorTap {
  import collection.JavaConverters._
  def create[O](iterator: java.util.Iterator[O]) = new IteratorTap(new scaladsl2.IteratorTap[O](iterator.asScala))
}
final case class IteratorTap[O](delegate: scaladsl2.IteratorTap[O]) extends javadsl.SimpleTap[O]

object IterableTap {
  def create[O](iterable: java.lang.Iterable[O]) = new IterableTap(new scaladsl2.IterableTap[O](akka.stream.javadsl.japi.Util.immutableIterable(iterable)))
}
final case class IterableTap[O](delegate: scaladsl2.IterableTap[O]) extends javadsl.SimpleTap[O]

object ThunkTap {
  def create[O](f: japi.Creator[akka.japi.Option[O]]) = new ThunkTap(new scaladsl2.ThunkTap[O](() ⇒ f.create()))
}
final case class ThunkTap[O](delegate: scaladsl2.ThunkTap[O]) extends javadsl.SimpleTap[O]

object FutureTap {
  def create[O](future: Future[O]) = new FutureTap(new scaladsl2.FutureTap[O](future))
}
final case class FutureTap[O](delegate: scaladsl2.FutureTap[O]) extends javadsl.SimpleTap[O]

object TickTap {
  def create[O](initialDelay: FiniteDuration, interval: FiniteDuration, tick: japi.Creator[O]) =
    new TickTap(new scaladsl2.TickTap[O](initialDelay, interval, () ⇒ tick.create()))
}
final case class TickTap[O](delegate: scaladsl2.TickTap[O]) extends javadsl.SimpleTap[O]
