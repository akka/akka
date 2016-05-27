/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._

import akka.actor.Address
import akka.actor.InternalActorRef
import akka.remote.UniqueAddress
import akka.remote.artery.InboundControlJunction.ControlMessageObserver
import akka.remote.artery.SystemMessageDelivery._
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.scaladsl.TestSource
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.testkit.TestProbe

object InboundControlJunctionSpec {
  case object Control1 extends ControlMessage
  case object Control2 extends ControlMessage
  case object Control3 extends ControlMessage
}

class InboundControlJunctionSpec extends AkkaSpec with ImplicitSender {
  import InboundControlJunctionSpec._

  val matSettings = ActorMaterializerSettings(system).withFuzzing(true)
  implicit val mat = ActorMaterializer(matSettings)(system)

  val addressA = UniqueAddress(Address("artery", "sysA", "hostA", 1001), 1)
  val addressB = UniqueAddress(Address("artery", "sysB", "hostB", 1002), 2)

  "Control messages" must {

    "be emitted via side channel" in {
      val observerProbe = TestProbe()
      val inboundContext = new TestInboundContext(localAddress = addressB)
      val recipient = null.asInstanceOf[InternalActorRef] // not used

      val ((upstream, controlSubject), downstream) = TestSource.probe[AnyRef]
        .map(msg ⇒ InboundEnvelope(recipient, addressB.address, msg, None, addressA.uid))
        .viaMat(new InboundControlJunction)(Keep.both)
        .map { case InboundEnvelope(_, _, msg, _, _) ⇒ msg }
        .toMat(TestSink.probe[Any])(Keep.both)
        .run()

      controlSubject.attach(new ControlMessageObserver {
        override def notify(env: InboundEnvelope) {
          observerProbe.ref ! env.message
        }
      })

      downstream.request(10)
      upstream.sendNext("msg1")
      downstream.expectNext("msg1")
      upstream.sendNext(Control1)
      upstream.sendNext(Control2)
      observerProbe.expectMsg(Control1)
      observerProbe.expectMsg(Control2)
      upstream.sendNext("msg2")
      downstream.expectNext("msg2")
      upstream.sendNext(Control3)
      observerProbe.expectMsg(Control3)
      downstream.cancel()
    }

  }

}
