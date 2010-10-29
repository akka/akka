package akka.dispatch

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

import org.scalatest.junit.JUnitSuite
import org.junit.{Test, Before}

import akka.actor.Actor
import Actor._

// FIXME use this test when we have removed the MessageInvoker classes
/*
class ThreadBasedDispatcherSpec extends JUnitSuite {
  private var threadingIssueDetected: AtomicBoolean = null
  val key1 = actorOf(new Actor { def receive = { case _ => {}} })
  val key2 = actorOf(new Actor { def receive = { case _ => {}} })
  val key3 = actorOf(new Actor { def receive = { case _ => {}} })

  class TestMessageHandle(handleLatch: CountDownLatch) extends MessageInvoker {
    val guardLock: Lock = new ReentrantLock

    def invoke(message: MessageInvocation) {
      try {
        if (threadingIssueDetected.get) return
        if (guardLock.tryLock) {
          handleLatch.countDown
        } else {
          threadingIssueDetected.set(true)
        }
      } catch {
        case e: Exception => threadingIssueDetected.set(true)
      } finally {
        guardLock.unlock
      }
    }
  }

  @Before
  def setUp = {
    threadingIssueDetected = new AtomicBoolean(false)
  }

  @Test
  def shouldMessagesDispatchedToTheSameHandlerAreExecutedSequentially = {
    internalTestMessagesDispatchedToTheSameHandlerAreExecutedSequentially
  }

  @Test
  def shouldMessagesDispatchedToHandlersAreExecutedInFIFOOrder = {
    internalTestMessagesDispatchedToHandlersAreExecutedInFIFOOrder
  }

  private def internalTestMessagesDispatchedToTheSameHandlerAreExecutedSequentially(): Unit = {
    val guardLock = new ReentrantLock
    val handleLatch = new CountDownLatch(100)
    val dispatcher = new ThreadBasedDispatcher("name", new TestMessageHandle(handleLatch))
    dispatcher.start
    for (i <- 0 until 100) {
      dispatcher.dispatch(new MessageInvocation(key1, new Object, None, None))
    }
    assert(handleLatch.await(5, TimeUnit.SECONDS))
    assert(!threadingIssueDetected.get)
  }

  private def internalTestMessagesDispatchedToHandlersAreExecutedInFIFOOrder(): Unit = {
    val handleLatch = new CountDownLatch(100)
    val dispatcher = new ThreadBasedDispatcher("name", new MessageInvoker {
      var currentValue = -1;
      def invoke(message: MessageInvocation) {
        if (threadingIssueDetected.get) return
        val messageValue = message.message.asInstanceOf[Int]
        if (messageValue.intValue == currentValue + 1) {
          currentValue = messageValue.intValue
          handleLatch.countDown
        } else threadingIssueDetected.set(true)
      }
    })
    dispatcher.start
    for (i <- 0 until 100) {
      dispatcher.dispatch(new MessageInvocation(key1, i, None, None))
    }
    assert(handleLatch.await(5, TimeUnit.SECONDS))
    assert(!threadingIssueDetected.get)
    dispatcher.postStop
  }
}
*/
