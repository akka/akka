package akka.actor

import akka.testkit.AkkaSpec
import akka.dispatch.UnboundedMailbox
import scala.util.duration._

object ConsistencySpec {
  val config = """
      consistency-dispatcher {
        throughput = 1
        keep-alive-time = 1 ms
        executor = "thread-pool-executor"
        thread-pool-executor {
          core-pool-size-min = 10
          core-pool-size-max = 10
          max-pool-size-min = 10
          max-pool-size-max = 10
          task-queue-type = array
          task-queue-size = 7
        }
      }
    """
  class CacheMisaligned(var value: Long, var padding1: Long, var padding2: Long, var padding3: Int) //Vars, no final fences

  class ConsistencyCheckingActor extends Actor {
    var left = new CacheMisaligned(42, 0, 0, 0) //var
    var right = new CacheMisaligned(0, 0, 0, 0) //var
    var lastStep = -1L
    def receive = {
      case step: Long ⇒

        if (lastStep != (step - 1))
          sender.tell("Test failed: Last step %s, this step %s".format(lastStep, step))

        var shouldBeFortyTwo = left.value + right.value
        if (shouldBeFortyTwo != 42)
          sender ! "Test failed: 42 failed"
        else {
          left.value += 1
          right.value -= 1
        }

        lastStep = step
      case "done" ⇒ sender ! "done"; context.stop(self)
    }
  }
}

class ConsistencySpec extends AkkaSpec(ConsistencySpec.config) {
  import ConsistencySpec._
  "The Akka actor model implementation" must {
    "provide memory consistency" in {
      val noOfActors = 7
      val props = Props[ConsistencyCheckingActor].withDispatcher("consistency-dispatcher")
      val actors = Vector.fill(noOfActors)(system.actorOf(props))

      for (i ← 0L until 600000L) {
        actors.foreach(_.tell(i, testActor))
      }

      for (a ← actors) { a.tell("done", testActor) }

      for (a ← actors) expectMsg(5 minutes, "done")
    }
  }
}