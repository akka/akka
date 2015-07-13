/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.impl.Stages.Identity
import akka.stream.scaladsl.Flow
import org.reactivestreams.Processor
import akka.stream.Attributes

class FusableProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = ActorMaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = ActorMaterializer(settings)(system)

    // withAttributes "wraps" the underlying identity and protects it from automatic removal
    Flow[Int].andThen[Int](Identity()).named("identity").toProcessor.run()
  }

  override def createElement(element: Int): Int = element

}
