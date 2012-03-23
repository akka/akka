package akka.amqp

import akka.actor.FSM.Transition
import com.rabbitmq.client.ShutdownSignalException
import akka.actor.ActorSystem
import akka.dispatch.Await
import akka.testkit.{ AkkaSpec, TestLatch, TestKit, TestFSMRef }
import akka.util.duration._

class ChannelSpec extends AkkaSpec {

  "Durable Channel Actor" should {
    implicit val system = ActorSystem("channelspec")
    val channelActor = TestFSMRef(new DurableChannelActor())

    "start in state Unavailable" in {
      channelActor.stateName must be === Unavailable
    }
    "request a channel when connection becomes Connected" in new TestKit(system) {
      within(5 seconds) {
        channelActor ! Transition(testActor, Disconnected, Connected)
        expectMsgType[ConnectionCallback[Unit]]
      }
    }
    "execute registered callbacks and become Available when receiving a Channel" in new AmqpMock {
      channelActor.stateName must be === Unavailable
      val latch = TestLatch(5)
      for (i ← 1 to 5) channelActor ! RegisterCallback(c ⇒ latch.open())
      channelActor ! channel
      Await.ready(latch, 5 seconds).isOpen must be === true
      channelActor.stateName must be === Available

    }
    "register callback and excute immediate when Available" in {
      channelActor.stateName must be === Available
      val latch = TestLatch()
      channelActor ! RegisterCallback(c ⇒ latch.open())
      Await.ready(latch, 5 seconds).isOpen must be === true
    }
    "request new channel when channel brakes and go to Unavailble" in new AmqpMock {
      channelActor ! new ShutdownSignalException(false, false, "Test", channel)
      channelActor.stateName must be === Unavailable
    }
    "go to Unavailable when connection disconnects" in new TestKit(system) with AmqpMock {
      channelActor.setState(Available, Some(channel))
      channelActor ! Transition(testActor, Connected, Disconnected)
      channelActor.stateName must be === Unavailable
    }
  }
}
