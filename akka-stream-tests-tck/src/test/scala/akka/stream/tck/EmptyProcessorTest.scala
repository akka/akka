/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.tck

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import org.reactivestreams.Processor
import org.testng.annotations.Test

class EmptyProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  @Test
  @throws[Throwable]
  def reproduce(): Unit = {
    var i = 0
    while (i < 100000) {
      System.out.println("Run " + i)
      this.required_spec109_mustIssueOnSubscribeForNonNullSubscriber()
      i += 1
    }
  }

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    implicit val materializer = ActorMaterializer()(system)

    Flow[Int].toProcessor.run()
  }

  override def createElement(element: Int): Int = element

}
