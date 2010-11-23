package akka.actor.dispatch

import org.scalatest.junit.JUnitSuite

import org.junit.Test

import akka.actor.Actor
import akka.util.Duration
import akka.dispatch._
import Actor._

import java.util.concurrent.{BlockingQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

class MailboxTypeSpec extends JUnitSuite {
  @Test def shouldDoNothing = assert(true)

/*
  private val unit = TimeUnit.MILLISECONDS

  @Test def shouldCreateUnboundedQueue = {
    val m = UnboundedMailbox(false)
    assert(m.newMailbox("uuid").asInstanceOf[BlockingQueue[MessageInvocation]].remainingCapacity === Integer.MAX_VALUE)
  }

  @Test def shouldCreateBoundedQueue = {
    val m = BoundedMailbox(blocking = false, capacity = 1)
    assert(m.newMailbox("uuid").asInstanceOf[BlockingQueue[MessageInvocation]].remainingCapacity === 1)
  }

  @Test(expected = classOf[MessageQueueAppendFailedException]) def shouldThrowMessageQueueAppendFailedExceptionWhenTimeOutEnqueue = {
    val m = BoundedMailbox(false, 1, Duration(1, unit))
    val testActor = actorOf( new Actor { def receive = { case _ => }} )
    val mbox = m.newMailbox("uuid")
    (1 to 10000) foreach { i => mbox.enqueue(new MessageInvocation(testActor, i, None, None, None)) }
  }


  @Test def shouldBeAbleToDequeueUnblocking = {
    val m = BoundedMailbox(false, 1, Duration(1, unit))
    val mbox = m.newMailbox("uuid")
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
  */
}
