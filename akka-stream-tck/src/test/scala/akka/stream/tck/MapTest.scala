/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import org.reactivestreams.Processor
import akka.stream.Attributes

class MapTest extends AkkaIdentityProcessorVerification[Int] {

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    implicit val materializer = ActorMaterializer()(system)

    processorFromFlow(
      Flow[Int].map(elem ⇒ elem).named("identity"))
  }

  override def createElement(element: Int): Int = element

}
