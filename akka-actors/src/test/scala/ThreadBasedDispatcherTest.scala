package se.scalablesolutions.akka.reactor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import org.junit.{Test, Before}
import org.junit.Assert._
import junit.framework.TestCase

class ThreadBasedDispatcherTest extends TestCase {
  private var threadingIssueDetected: AtomicBoolean = null

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
  override def setUp = {
    threadingIssueDetected = new AtomicBoolean(false)
  }

  @Test
  def testMessagesDispatchedToTheSameHandlerAreExecutedSequentially = {
    internalTestMessagesDispatchedToTheSameHandlerAreExecutedSequentially
  }

  @Test
  def testMessagesDispatchedToHandlersAreExecutedInFIFOOrder = {
    internalTestMessagesDispatchedToHandlersAreExecutedInFIFOOrder
  }

  private def internalTestMessagesDispatchedToTheSameHandlerAreExecutedSequentially: Unit = {
    val guardLock = new ReentrantLock
    val handleLatch = new CountDownLatch(100)
    val dispatcher = new ThreadBasedDispatcher("name", new TestMessageHandle(handleLatch))
    dispatcher.start
    for (i <- 0 until 100) {
      dispatcher.messageQueue.append(new MessageInvocation("id", new Object, None, None))
    }
    assertTrue(handleLatch.await(5, TimeUnit.SECONDS))
    assertFalse(threadingIssueDetected.get)
  }

  private def internalTestMessagesDispatchedToHandlersAreExecutedInFIFOOrder: Unit = {
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
      dispatcher.messageQueue.append(new MessageInvocation("id", new java.lang.Integer(i), None, None))
    }
    assertTrue(handleLatch.await(5, TimeUnit.SECONDS))
    assertFalse(threadingIssueDetected.get)
    dispatcher.shutdown
  }
}
