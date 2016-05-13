/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._

import akka.actor.Address
import akka.remote.EndpointManager.Send
import akka.remote.RemoteActorRef
import akka.remote.UniqueAddress
import akka.remote.artery.SystemMessageDelivery._
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.scaladsl.TestSource
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender

object OutboundControlJunctionSpec {
  case object Control1 extends ControlMessage
  case object Control2 extends ControlMessage
  case object Control3 extends ControlMessage
}

class OutboundControlJunctionSpec extends AkkaSpec with ImplicitSender {
  import OutboundControlJunctionSpec._

  val matSettings = ActorMaterializerSettings(system).withFuzzing(true)
  implicit val mat = ActorMaterializer(matSettings)(system)

  val addressA = UniqueAddress(Address("akka.artery", "sysA", "hostA", 1001), 1)
  val addressB = UniqueAddress(Address("akka.artery", "sysB", "hostB", 1002), 2)

  "Control messages" must {

    "be injected via side channel" in {
      val inboundContext = new TestInboundContext(localAddress = addressA)
      val outboundContext = inboundContext.association(addressB.address)
      val destination = null.asInstanceOf[RemoteActorRef] // not used

      val ((upstream, controlIngress), downstream) = TestSource.probe[String]
        .map(msg ⇒ Send(msg, None, destination, None))
        .viaMat(new OutboundControlJunction(outboundContext))(Keep.both)
        .map { case Send(msg, _, _, _) ⇒ msg }
        .toMat(TestSink.probe[Any])(Keep.both)
        .run()

      controlIngress.sendControlMessage(Control1)
      downstream.request(1)
      downstream.expectNext(Control1)
      upstream.sendNext("msg1")
      downstream.request(1)
      downstream.expectNext("msg1")
      upstream.sendNext("msg2")
      downstream.request(1)
      downstream.expectNext("msg2")
      controlIngress.sendControlMessage(Control2)
      upstream.sendNext("msg3")
      downstream.request(10)
      downstream.expectNextUnorderedN(List("msg3", Control2))
      controlIngress.sendControlMessage(Control3)
      downstream.expectNext(Control3)
      downstream.cancel()
    }

  }

}
