package akka.actor

import akka.testkit.AkkaSpec
import akka.dispatch.UnboundedMailbox
import akka.util.duration._

object ConsistencySpec {
  class CacheMisaligned(var value: Long, var padding1: Long, var padding2: Long, var padding3: Int) //Vars, no final fences

  class ConsistencyCheckingActor extends Actor {
    var left = new CacheMisaligned(42, 0, 0, 0) //var
    var right = new CacheMisaligned(0, 0, 0, 0) //var
    def receive = {
      case "check" ⇒
        var shouldBeFortyTwo = left.value + right.value
        if (shouldBeFortyTwo != 42)
          sender ! "Test failed"
        else {
          left.value += 1
          right.value -= 1
        }
      case "done" ⇒ sender ! "done"; self.stop()
    }
  }
}

class ConsistencySpec extends AkkaSpec {
  import ConsistencySpec._
  "The Akka actor model implementation" must {
    "provide memory consistency" in {
      val dispatcher = system
        .dispatcherFactory
        .newDispatcher("consistency-dispatcher", 1, UnboundedMailbox())
        .withNewThreadPoolWithArrayBlockingQueueWithCapacityAndFairness(1000, true)
        .setCorePoolSize(10)
        .setMaxPoolSize(10)
        .setKeepAliveTimeInMillis(1)
        .setAllowCoreThreadTimeout(true)
        .build

      val props = Props[ConsistencyCheckingActor].withDispatcher(dispatcher)
      val actors = Vector.fill(3)(system.actorOf(props))
      for (i ← 1 to 1000000) actors(i % actors.size).tell("check", testActor)
      for (a ← actors) a.tell("done", testActor)

      for (a ← actors) expectMsg(5 minutes, "done")
    }
  }
}