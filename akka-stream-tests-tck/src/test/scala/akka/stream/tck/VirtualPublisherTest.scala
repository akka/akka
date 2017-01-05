/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import org.reactivestreams.Processor
import akka.stream.impl.VirtualProcessor

class VirtualProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    implicit val materializer = ActorMaterializer()(system)

    val identity = Flow[Int].map(elem ⇒ elem).named("identity").toProcessor.run()
    val left, right = new VirtualProcessor[Int]
    left.subscribe(identity)
    identity.subscribe(right)
    processorFromSubscriberAndPublisher(left, right)
  }

  override def createElement(element: Int): Int = element

}

class VirtualProcessorSingleTest extends AkkaIdentityProcessorVerification[Int] {

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] =
    new VirtualProcessor[Int]

  override def createElement(element: Int): Int = element

}
