/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.scaladsl._
import org.reactivestreams.Subscriber

class FoldSinkSubscriberTest extends AkkaSubscriberBlackboxVerification[Int] {

  override def createSubscriber(): Subscriber[Int] =
    Flow[Int].to(Sink.fold(0)(_ + _)).runWith(Source.asSubscriber)

  override def createElement(element: Int): Int = element
}
