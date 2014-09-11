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

  "ProcessorFlow" should {
    "append ProcessorFlow" in riverOf[String] { subscriber ⇒
      FlowFrom[Int]
        .append(otherFlow)
        .withSource(IterableSource(elements))
        .publishTo(subscriber)
    }

    "append FlowWithSink" in riverOf[String] { subscriber ⇒
      FlowFrom[Int]
        .append(otherFlow.withSink(SubscriberSink(subscriber)))
        .withSource(IterableSource(elements))
        .run()
    }
  }

  "FlowWithSource" should {
    "append ProcessorFlow" in riverOf[String] { subscriber ⇒
      FlowFrom(elements)
        .append(otherFlow)
        .publishTo(subscriber)
    }

    "append FlowWithSink" in riverOf[String] { subscriber ⇒
      FlowFrom(elements)
        .append(otherFlow.withSink(SubscriberSink(subscriber)))
        .run()
    }
  }

}

trait River { self: Matchers ⇒

  val elements = (1 to 10)
  val otherFlow = FlowFrom[Int].map(_.toString)

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
