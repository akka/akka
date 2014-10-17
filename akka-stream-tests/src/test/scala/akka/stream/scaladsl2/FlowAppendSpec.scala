/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.actor.ActorSystem
import akka.stream.MaterializerSettings
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import org.reactivestreams.Subscriber
import org.scalatest.Matchers

class FlowAppendSpec extends AkkaSpec with River {

  val settings = MaterializerSettings(system)
  implicit val materializer = FlowMaterializer(settings)

  "Flow" should {
    "append Flow" in riverOf[String] { subscriber ⇒
      val flow = Flow[Int].connect(otherFlow)
      Source(elements).connect(flow).connect(Sink(subscriber)).run()
    }

    "append Sink" in riverOf[String] { subscriber ⇒
      val sink = Flow[Int].connect(otherFlow.connect(Sink(subscriber)))
      Source(elements).connect(sink).run()
    }
  }

  "Source" should {
    "append Flow" in riverOf[String] { subscriber ⇒
      Source(elements)
        .connect(otherFlow)
        .connect(Sink(subscriber)).run()
    }

    "append Sink" in riverOf[String] { subscriber ⇒
      Source(elements)
        .connect(otherFlow.connect(Sink(subscriber)))
        .run()
    }
  }

}

trait River { self: Matchers ⇒

  val elements = (1 to 10)
  val otherFlow = Flow[Int].map(_.toString)

  def riverOf[T](flowConstructor: Subscriber[T] ⇒ Unit)(implicit system: ActorSystem) = {
    val subscriber = StreamTestKit.SubscriberProbe[T]()

    flowConstructor(subscriber)

    val subscription = subscriber.expectSubscription()
    subscription.request(elements.size)
    subscriber.probe.receiveN(elements.size) should be(elements.map(_.toString).map(StreamTestKit.OnNext(_)))
    subscription.request(1)
    subscriber.expectComplete()
  }
}
