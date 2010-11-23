package akka.actor.dispatch

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.junit.JUnitSuite
import org.junit.Test
import akka.dispatch.{Dispatchers,ExecutorBasedEventDrivenDispatcher}
import akka.actor.Actor
import Actor._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

object ExecutorBasedEventDrivenDispatcherActorSpec {
  class TestActor extends Actor {
    self.dispatcher = Dispatchers.newExecutorBasedEventDrivenDispatcher(self.uuid.toString).build
    def receive = {
      case "Hello" =>
        self.reply("World")
      case "Failure" =>
        throw new RuntimeException("Expected exception; to test fault-tolerance")
    }
  }

  object OneWayTestActor {
    val oneWay = new CountDownLatch(1)
  }
  class OneWayTestActor extends Actor {
    self.dispatcher = Dispatchers.newExecutorBasedEventDrivenDispatcher(self.uuid.toString).build
    def receive = {
      case "OneWay" => OneWayTestActor.oneWay.countDown
    }
  }
}
class ExecutorBasedEventDrivenDispatcherActorSpec extends JUnitSuite {
  import ExecutorBasedEventDrivenDispatcherActorSpec._

  private val unit = TimeUnit.MILLISECONDS

  @Test def shouldSendOneWay = {
    val actor = actorOf[OneWayTestActor].start
    val result = actor ! "OneWay"
    assert(OneWayTestActor.oneWay.await(1, TimeUnit.SECONDS))
    actor.stop
  }

  @Test def shouldSendReplySync = {
    val actor = actorOf[TestActor].start
    val result = (actor !! ("Hello", 10000)).as[String]
    assert("World" === result.get)
    actor.stop
  }

  @Test def shouldSendReplyAsync = {
    val actor = actorOf[TestActor].start
    val result = actor !! "Hello"
    assert("World" === result.get.asInstanceOf[String])
    actor.stop
  }

  @Test def shouldSendReceiveException = {
    val actor = actorOf[TestActor].start
    try {
      actor !! "Failure"
      fail("Should have thrown an exception")
    } catch {
      case e =>
        assert("Expected exception; to test fault-tolerance" === e.getMessage())
    }
    actor.stop
  }

 @Test def shouldRespectThroughput {
   val throughputDispatcher = Dispatchers.
                                newExecutorBasedEventDrivenDispatcher("THROUGHPUT",101,0,Dispatchers.MAILBOX_TYPE).
                                setCorePoolSize(1).
                                build

   val works   = new AtomicBoolean(true)
   val latch   = new CountDownLatch(100)
   val start   = new CountDownLatch(1)
   val fastOne = actorOf(
                   new Actor {
                     self.dispatcher = throughputDispatcher
                     def receive = { case "sabotage" => works.set(false)  }
                   }).start

   val slowOne = actorOf(
                   new Actor {
                     self.dispatcher = throughputDispatcher
                     def receive = {
                       case "hogexecutor" => start.await
                       case "ping"        => if (works.get) latch.countDown
                     }
                   }).start

   slowOne ! "hogexecutor"
   (1 to 100) foreach { _ => slowOne ! "ping"}
   fastOne ! "sabotage"
   start.countDown
   val result = latch.await(3,TimeUnit.SECONDS)
   fastOne.stop
   slowOne.stop
   assert(result === true)
 }

 @Test def shouldRespectThroughputDeadline {
   val deadlineMs = 100
   val throughputDispatcher = Dispatchers.
                                newExecutorBasedEventDrivenDispatcher("THROUGHPUT",2,deadlineMs,Dispatchers.MAILBOX_TYPE).
                                setCorePoolSize(1).
                                build
   val works   = new AtomicBoolean(true)
   val latch   = new CountDownLatch(1)
   val start   = new CountDownLatch(1)
   val ready   = new CountDownLatch(1)

   val fastOne = actorOf(
                   new Actor {
                     self.dispatcher = throughputDispatcher
                     def receive = { case "ping" => if(works.get) latch.countDown; self.stop  }
                   }).start

   val slowOne = actorOf(
                   new Actor {
                     self.dispatcher = throughputDispatcher
                     def receive = {
                       case "hogexecutor" => ready.countDown; start.await
                       case "ping"        => works.set(false); self.stop
                     }
                   }).start

   slowOne ! "hogexecutor"
   slowOne ! "ping"
   fastOne ! "ping"
   assert(ready.await(2,TimeUnit.SECONDS) === true)
   Thread.sleep(deadlineMs+10) // wait just a bit more than the deadline
   start.countDown
   assert(latch.await(2,TimeUnit.SECONDS) === true)
 }
}
