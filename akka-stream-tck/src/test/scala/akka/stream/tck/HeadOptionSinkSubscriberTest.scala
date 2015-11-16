/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.impl.HeadSink
import akka.stream.scaladsl._
import org.reactivestreams.Subscriber

import scala.concurrent.Promise

class HeadOptionSinkSubscriberTest extends AkkaSubscriberBlackboxVerification[Int] {
  import HeadSink._

  override def createSubscriber(): Subscriber[Int] = new HeadOptionSinkSubscriber[Int]

  override def createElement(element: Int): Int = element
}
