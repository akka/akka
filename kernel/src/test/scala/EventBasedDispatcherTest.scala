package se.scalablesolutions.akka.kernel.reactor

import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import org.junit.{Test, Before}
import org.junit.Assert._

class EventBasedDispatcherTest {
  private var threadingIssueDetected: AtomicBoolean = null

  class TestMessageHandle(handleLatch: CountDownLatch) extends MessageHandler {
    val guardLock: Lock = new ReentrantLock

    def handle(message: MessageHandle) {
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
  def testMessagesDispatchedToTheSameHandlerAreExecutedSequentially = {
    internalTestMessagesDispatchedToTheSameHandlerAreExecutedSequentially
  }

  @Test
  def testMessagesDispatchedToDifferentHandlersAreExecutedSequentially = {
    internalTestMessagesDispatchedToDifferentHandlersAreExecutedSequentially
  }

  @Test
  def testMessagesDispatchedToHandlersAreExecutedInFIFOOrder = {
    internalTestMessagesDispatchedToHandlersAreExecutedInFIFOOrder
  }

  private def internalTestMessagesDispatchedToTheSameHandlerAreExecutedSequentially: Unit = {
    val guardLock = new ReentrantLock
    val handleLatch = new CountDownLatch(100)
    val key = "key"
    val dispatcher = new EventBasedSingleThreadDispatcher
    dispatcher.registerHandler(key, new TestMessageHandle(handleLatch))
    dispatcher.start
    for (i <- 0 until 100) {
      dispatcher.messageQueue.append(new MessageHandle(key, new Object, new NullFutureResult, None))
    }
    assertTrue(handleLatch.await(5, TimeUnit.SECONDS))
    assertFalse(threadingIssueDetected.get)
  }

  private def internalTestMessagesDispatchedToDifferentHandlersAreExecutedSequentially: Unit = {
    val handleLatch = new CountDownLatch(2)
    val key1 = "key1"
    val key2 = "key2"
    val dispatcher = new EventBasedSingleThreadDispatcher
    dispatcher.registerHandler(key1, new TestMessageHandle(handleLatch))
    dispatcher.registerHandler(key2, new TestMessageHandle(handleLatch))
    dispatcher.start
    dispatcher.messageQueue.append(new MessageHandle(key1, new Object, new NullFutureResult, None))
    dispatcher.messageQueue.append(new MessageHandle(key2, new Object, new NullFutureResult, None))
    assertTrue(handleLatch.await(5, TimeUnit.SECONDS))
    assertFalse(threadingIssueDetected.get)
  }

  private def internalTestMessagesDispatchedToHandlersAreExecutedInFIFOOrder: Unit = {
    val handleLatch = new CountDownLatch(200)
    val key1 = "key1"
    val key2 = "key2"
    val dispatcher = new EventBasedSingleThreadDispatcher
    dispatcher.registerHandler(key1, new MessageHandler {
      var currentValue = -1;
      def handle(message: MessageHandle) {
        if (threadingIssueDetected.get) return
        val messageValue = message.message.asInstanceOf[Int]
        if (messageValue.intValue == currentValue + 1) {
          currentValue = messageValue.intValue
          handleLatch.countDown
        } else threadingIssueDetected.set(true)
      }
    })
    dispatcher.registerHandler(key2, new MessageHandler {
      var currentValue = -1;
      def handle(message: MessageHandle) {
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
      dispatcher.messageQueue.append(new MessageHandle(key1, new Integer(i), new NullFutureResult, None))
      dispatcher.messageQueue.append(new MessageHandle(key2, new Integer(i), new NullFutureResult, None))
    }
    assertTrue(handleLatch.await(5, TimeUnit.SECONDS))
    assertFalse(threadingIssueDetected.get)
    dispatcher.shutdown
  }
}
