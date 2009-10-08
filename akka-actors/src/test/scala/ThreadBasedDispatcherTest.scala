package se.scalablesolutions.akka.dispatch

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import org.junit.{Test, Before}
import org.junit.Assert._
import junit.framework.TestCase
import se.scalablesolutions.akka.actor.Actor

class ThreadBasedDispatcherTest extends TestCase {
  private var threadingIssueDetected: AtomicBoolean = null
  val key1 = new Actor { def receive: PartialFunction[Any, Unit] = { case _ => {}} }
  val key2 = new Actor { def receive: PartialFunction[Any, Unit] = { case _ => {}} }
  val key3 = new Actor { def receive: PartialFunction[Any, Unit] = { case _ => {}} }
  
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
      dispatcher.messageQueue.append(new MessageInvocation(key1, new Object, None, None))
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
      dispatcher.messageQueue.append(new MessageInvocation(key1, new Integer(i), None, None))
    }
    assertTrue(handleLatch.await(5, TimeUnit.SECONDS))
    assertFalse(threadingIssueDetected.get)
    dispatcher.shutdown
  }
}
