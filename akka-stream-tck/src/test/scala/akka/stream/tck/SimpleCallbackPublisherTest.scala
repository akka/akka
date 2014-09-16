/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.scaladsl.Flow
import org.reactivestreams._

class SimpleCallbackPublisherTest extends AkkaPublisherVerification[Int] {

  def createPublisher(elements: Long): Publisher[Int] = {
    val iter = Iterator from 0
    val iter2 = if (elements > 0) iter take elements.toInt else iter
    Flow(() ⇒ if (iter2.hasNext) Some(iter2.next()) else None).toPublisher()
  }

}