/*
 * Copyright (C) 2014-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import org.reactivestreams.Subscriber

import akka.stream.scaladsl._

class FoldSinkSubscriberTest extends AkkaSubscriberBlackboxVerification[Int] {

  override def createSubscriber(): Subscriber[Int] =
    Flow[Int].to(Sink.fold(0)(_ + _)).runWith(Source.asSubscriber)

  override def createElement(element: Int): Int = element
}
