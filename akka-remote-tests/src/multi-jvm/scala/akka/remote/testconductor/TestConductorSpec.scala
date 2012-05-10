package akka.remote.testconductor

import akka.remote.AkkaRemoteSpec
import com.typesafe.config.ConfigFactory
import akka.remote.AbstractRemoteActorMultiJvmSpec
import akka.actor.Props
import akka.actor.Actor
import akka.dispatch.Await
import akka.dispatch.Await.Awaitable
import akka.util.Duration
import akka.util.duration._
import akka.testkit.ImplicitSender

object TestConductorMultiJvmSpec extends AbstractRemoteActorMultiJvmSpec {
  override def NrOfNodes = 2
  override def commonConfig = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.actor.provider = akka.remote.RemoteActorRefProvider
    akka.remote {
      transport = akka.remote.testconductor.TestConductorTransport
      log-received-messages = on
      log-sent-messages = on
    }
    akka.actor.debug {
      receive = on
      fsm = on
    }
    akka.testconductor {
      host = localhost
      port = 4712
    }
  """)
  def nameConfig(n: Int) = ConfigFactory.parseString("akka.testconductor.name = node" + n).withFallback(nodeConfigs(n))

  implicit def awaitHelper[T](w: Awaitable[T]) = new AwaitHelper(w)
  class AwaitHelper[T](w: Awaitable[T]) {
    def await: T = Await.result(w, Duration.Inf)
  }
}

class TestConductorMultiJvmNode1 extends AkkaRemoteSpec(TestConductorMultiJvmSpec.nameConfig(0)) {

  import TestConductorMultiJvmSpec._

  val nodes = NrOfNodes

  val tc = TestConductor(system)

  val echo = system.actorOf(Props(new Actor {
    def receive = {
      case x ⇒ testActor ! x; sender ! x
    }
  }), "echo")

  "running a test with barrier" in {
    tc.startController(2).await
    tc.enter("begin")
  }

  "throttling" in {
    expectMsg("start")
    tc.throttle("node1", "node0", Direction.Send, 0.01).await
    tc.enter("throttled_send")
    within(0.6 seconds, 2 seconds) {
      receiveN(10) must be(0 to 9)
    }
    tc.enter("throttled_send2")
    tc.throttle("node1", "node0", Direction.Send, -1).await
    
    tc.throttle("node1", "node0", Direction.Receive, 0.01).await
    tc.enter("throttled_recv")
    receiveN(10, 500 millis) must be(10 to 19)
    tc.enter("throttled_recv2")
    tc.throttle("node1", "node0", Direction.Receive, -1).await
  }
}

class TestConductorMultiJvmNode2 extends AkkaRemoteSpec(TestConductorMultiJvmSpec.nameConfig(1)) with ImplicitSender {

  import TestConductorMultiJvmSpec._

  val nodes = NrOfNodes

  val tc = TestConductor(system)
  
  val echo = system.actorFor("akka://" + akkaSpec(0) + "/user/echo")

  "running a test with barrier" in {
    tc.startClient(4712).await
    tc.enter("begin")
  }

  "throttling" in {
    echo ! "start"
    expectMsg("start")
    tc.enter("throttled_send")
    for (i <- 0 to 9) echo ! i
    expectMsg(500 millis, 0)
    within(0.6 seconds, 2 seconds) {
      receiveN(9) must be(1 to 9)
    }
    tc.enter("throttled_send2", "throttled_recv")
    for (i <- 10 to 19) echo ! i
    expectMsg(500 millis, 10)
    within(0.6 seconds, 2 seconds) {
      receiveN(9) must be(11 to 19)
    }
    tc.enter("throttled_recv2")
  }

}
