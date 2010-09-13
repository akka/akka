package se.scalablesolutions.akka.actor.dispatch

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import se.scalablesolutions.akka.actor.Actor
import Actor._
import java.util.concurrent.{BlockingQueue, CountDownLatch, TimeUnit}
import se.scalablesolutions.akka.util.Duration
import se.scalablesolutions.akka.dispatch.{MessageQueueAppendFailedException, MessageInvocation, MailboxConfig, Dispatchers}
import java.util.concurrent.atomic.{AtomicReference}

object MailboxConfigSpec {

}

class MailboxConfigSpec extends JUnitSuite {
  import MailboxConfigSpec._

  private val unit = TimeUnit.MILLISECONDS

  @Test def shouldCreateUnboundedQueue = {
    val m = MailboxConfig(-1,None,false)
    assert(m.newMailbox().asInstanceOf[BlockingQueue[MessageInvocation]].remainingCapacity === Integer.MAX_VALUE)
  }

  @Test def shouldCreateBoundedQueue = {
    val m = MailboxConfig(1,None,false)
    assert(m.newMailbox().asInstanceOf[BlockingQueue[MessageInvocation]].remainingCapacity === 1)
  }

  @Test(expected = classOf[MessageQueueAppendFailedException]) def shouldThrowMessageQueueAppendFailedExceptionWhenTimeOutEnqueue = {
    val m = MailboxConfig(1,Some(Duration(1,unit)),false)
    val testActor = actorOf( new Actor { def receive = { case _ => }} )
    val mbox = m.newMailbox()
    (1 to 10000) foreach { i => mbox.enqueue(new MessageInvocation(testActor,i,None,None,None)) }
  }


  @Test def shouldBeAbleToDequeueUnblocking = {
    val m = MailboxConfig(1,Some(Duration(1,unit)),false)
    val mbox = m.newMailbox()
    val latch = new CountDownLatch(1)
    val t = new Thread { override def run = {
      mbox.dequeue
      latch.countDown
    }}
    t.start
    val result = latch.await(5000,unit)
    if (!result)
      t.interrupt
    assert(result === true)
  }
}
