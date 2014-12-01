/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.scaladsl.OperationAttributes._
import akka.stream.FlowMaterializer
import akka.stream.testkit.AkkaSpec
import akka.actor.ActorRef
import akka.testkit.TestProbe

object FlowSectionSpec {
  val config = "my-dispatcher = ${akka.test.stream-dispatcher}"
}

class FlowSectionSpec extends AkkaSpec(FlowSectionSpec.config) {

  implicit val mat = FlowMaterializer()

  "A flow" can {

    "have an op with a name" in {
      val n = "Converter to Int"
      val f = Flow[Int].section(name(n))(_.map(_.toInt))
      f.toString should include(n)
    }

    "have an op with a different dispatcher" in {
      val flow = Flow[Int].section(dispatcher("my-dispatcher"))(_.map(sendThreadNameTo(testActor)))

      Source.singleton(1).via(flow).to(Sink.ignore).run()

      receiveN(1).foreach {
        case s: String ⇒ s should include("my-dispatcher")
      }
    }

    "have an op section with a name" in {
      val n = "Uppercase reverser"
      val f = Flow[String].
        map(_.toLowerCase()).
        section(name(n)) {
          _.map(_.toUpperCase).
            map(_.reverse)
        }.
        map(_.toLowerCase())
      f.toString should include(n)
    }

    "have an op section with a different dispatcher and name" in {
      val defaultDispatcher = TestProbe()
      val customDispatcher = TestProbe()

      val f = Flow[Int].
        map(sendThreadNameTo(defaultDispatcher.ref)).
        section(dispatcher("my-dispatcher") and name("separate-disptacher")) {
          _.map(sendThreadNameTo(customDispatcher.ref)).
            map(sendThreadNameTo(customDispatcher.ref))
        }.
        map(sendThreadNameTo(defaultDispatcher.ref))

      Source(0 to 2).via(f).runWith(Sink.ignore)

      defaultDispatcher.receiveN(6).foreach {
        case s: String ⇒ s should include("akka.test.stream-dispatcher")
      }

      customDispatcher.receiveN(6).foreach {
        case s: String ⇒ s should include("my-dispatcher")
      }
    }

    def sendThreadNameTo[T](probe: ActorRef)(element: T) = {
      probe ! Thread.currentThread.getName
      element
    }

  }

}
