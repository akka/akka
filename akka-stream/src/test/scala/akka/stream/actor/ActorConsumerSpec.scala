/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.actor

import scala.concurrent.duration._
import akka.actor.ActorRef
import akka.actor.Props
import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec

object ActorConsumerSpec {
  class TestConsumer(probe: ActorRef) extends ActorConsumerDestination {
    import ActorConsumerDestination._

    request(elements = 2)

    def receive = {
      case next @ OnNext(elem)   ⇒ probe ! next
      case complete @ OnComplete ⇒ probe ! complete
      case "ready"               ⇒ request(elements = 2)
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorConsumerSpec extends AkkaSpec {
  import ActorConsumerSpec._
  import ActorConsumerDestination._

  val materializer = FlowMaterializer(MaterializerSettings())

  "An ActorConsumer" must {

    "receive requested elements" in {
      val ref = system.actorOf(Props(classOf[TestConsumer], testActor))
      val consumer = ActorConsumer(ref)
      Flow(List(1, 2, 3)).produceTo(materializer, consumer)
      expectMsg(OnNext(1))
      expectMsg(OnNext(2))
      expectNoMsg(200.millis)
      ref ! "ready"
      expectMsg(OnNext(3))
      expectMsg(OnComplete)
    }

    // FIXME test OnError
    // FIXME test restart and stop
  }

}