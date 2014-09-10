/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.impl.BlackholeSubscriber
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber

class BlackholeSubscriberTest extends AkkaSubscriberBlackboxVerification[Int] {

  override def createSubscriber(): Subscriber[Int] =
    new BlackholeSubscriber[Int](2)

  override def createHelperPublisher(elements: Long): Publisher[Int] = createSimpleIntPublisher(elements)
}

