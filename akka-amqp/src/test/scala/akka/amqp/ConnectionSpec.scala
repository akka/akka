package akka.amqp

import com.rabbitmq.client.ShutdownSignalException
import akka.actor.FSM.{ UnsubscribeTransitionCallBack, CurrentState, Transition, SubscribeTransitionCallBack }
import akka.dispatch.{ Await, Terminate }
import akka.testkit.{ AkkaSpec, TestKit, TestFSMRef }
import akka.actor.{ ActorSystem, PoisonPill }
import akka.util.duration._

class ConnectionSpec extends AkkaSpec {

  implicit val amqpSystem = ActorSystem("ConnectionSpec")

  "Durable Connection Actor" should {

    val connectionActor = TestFSMRef(new DurableConnectionActor(defaultConnectionProperties))

    "start disconnected" in {
      connectionActor.stateName must be === Disconnected
    }
    "connect" in {
      connectionActor.stateName must be === Disconnected
      connectionActor ! Connect
      connectionActor.stateName must be === Connected
    }
    "reconnect on ShutdownSignalException" in new TestKit(amqpSystem) with AmqpMock {
      try {
        within(5 second) {
          connectionActor ! SubscribeTransitionCallBack(testActor)
          expectMsg(CurrentState(connectionActor, Connected))
          connectionActor ! new ShutdownSignalException(true, false, "Test", connection)
          expectMsg(Transition(connectionActor, Connected, Disconnected))
          expectMsg(Transition(connectionActor, Disconnected, Connected))
        }
      } finally {
        connectionActor ! UnsubscribeTransitionCallBack(testActor)
        testActor ! Terminate()
      }
    }
    "disconnect" in {
      connectionActor.stateName must be === Connected
      connectionActor ! Disconnect
      connectionActor.stateName must be === Disconnected
    }
    "dispose" in {
      connectionActor ! Disconnect
      connectionActor ! PoisonPill
      connectionActor.isTerminated must be === true
    }
    "never connect using non exisiting host addresses" in new TestKit(amqpSystem) {
      val connectionActor = TestFSMRef(new DurableConnectionActor(defaultConnectionProperties.copy(addresses = Seq("no-op:1234"))))
      try {
        connectionActor ! Connect
        within(2 seconds) {
          connectionActor ! SubscribeTransitionCallBack(testActor)
          expectMsg(CurrentState(connectionActor, Disconnected))
        }
        connectionActor.stateName must be === Disconnected
      } finally {
        testActor ! Terminate()
        connectionActor ! Disconnect // to cancel reconnect timer
        connectionActor ! PoisonPill
      }
    }
  }
  "Durable Connection" should {
    "execute callback on connection when connected" in new TestKit(amqpSystem) {
      val durableConnection = new DurableConnection(defaultConnectionProperties)
      try {
        val connectionActor = durableConnection.durableConnectionActor
        connectionActor ! SubscribeTransitionCallBack(testActor)
        expectMsg(CurrentState(connectionActor, Connected))
        val portFuture = durableConnection.withConnection(_.getPort)
        Await.ready(portFuture, 5 seconds).value must be === Some(Right(5672))
      } finally {
        durableConnection.dispose()
      }
    }
  }
}
