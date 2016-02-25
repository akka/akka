/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.testkit.TestSubscriber
import org.reactivestreams.Subscriber
import org.scalatest.Matchers
import akka.testkit.AkkaSpec

class FlowAppendSpec extends AkkaSpec with River {

  val settings = ActorMaterializerSettings(system)
  implicit val materializer = ActorMaterializer(settings)

  "Flow" should {
    "append Flow" in riverOf[String] { subscriber ⇒
      val flow = Flow[Int].via(otherFlow)
      Source(elements).via(flow).to(Sink.fromSubscriber(subscriber)).run()
    }

    "append Sink" in riverOf[String] { subscriber ⇒
      val sink = Flow[Int].to(otherFlow.to(Sink.fromSubscriber(subscriber)))
      Source(elements).to(sink).run()
    }
  }

  "Source" should {
    "append Flow" in riverOf[String] { subscriber ⇒
      Source(elements)
        .via(otherFlow)
        .to(Sink.fromSubscriber(subscriber)).run()
    }

    "append Sink" in riverOf[String] { subscriber ⇒
      Source(elements)
        .to(otherFlow.to(Sink.fromSubscriber(subscriber)))
        .run()
    }
  }

}

trait River { self: Matchers ⇒

  val elements = 1 to 10
  val otherFlow = Flow[Int].map(_.toString)

  def riverOf[T](flowConstructor: Subscriber[T] ⇒ Unit)(implicit system: ActorSystem) = {
    val subscriber = TestSubscriber.manualProbe[T]()

    flowConstructor(subscriber)

    val subscription = subscriber.expectSubscription()
    subscription.request(elements.size)
    elements.foreach { el ⇒
      subscriber.expectNext() shouldBe el.toString
    }
    subscription.request(1)
    subscriber.expectComplete()
  }
}
