/**
 * Copyright (C) 2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit._
import scala.concurrent.duration._

class FlowAggregateWeightedSpec extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = ActorMaterializer(settings)

  "AggregateWeighted" must {
    "Not aggregate heavy elements" in {
      val publisher = TestPublisher.probe[Int]()
      val subscriber = TestSubscriber.manualProbe[Int]()

      Source.fromPublisher(publisher).aggregateWeighted(max = 3, _ ⇒ 4, seed = i ⇒ i)(aggregate = _ + _).to(Sink.fromSubscriber(subscriber)).run()
      val sub = subscriber.expectSubscription()

      publisher.sendNext(1)
      publisher.sendNext(2)

      sub.request(1)
      subscriber.expectNext(1)

      publisher.sendNext(3)
      subscriber.expectNoMsg(1.second)

      sub.request(2)
      subscriber.expectNext(2)
      subscriber.expectNext(3)

      sub.cancel()
    }
  }
}
