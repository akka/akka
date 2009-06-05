package se.scalablesolutions.akka.kernel.reactor

import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import org.junit.Before
import org.junit.Test
import org.junit.Assert._

class ThreadBasedDispatcherTest {
  private var threadingIssueDetected: AtomicBoolean = null

  @Before
  def setUp = {
    threadingIssueDetected = new AtomicBoolean(false)
  }

  @Test
  def testMessagesDispatchedToTheSameHandlerAreExecutedSequentially = {
    internalTestMessagesDispatchedToTheSameHandlerAreExecutedSequentially
  }

  @Test
  def testMessagesDispatchedToDifferentHandlersAreExecutedConcurrently = {
    internalTestMessagesDispatchedToDifferentHandlersAreExecutedConcurrently
  }

  @Test
  def testMessagesDispatchedToHandlersAreExecutedInFIFOOrder = {
    internalTestMessagesDispatchedToHandlersAreExecutedInFIFOOrder
  }

  private def internalTestMessagesDispatchedToTheSameHandlerAreExecutedSequentially: Unit = {
    val guardLock = new ReentrantLock
    val handleLatch = new CountDownLatch(10)
    val key = "key"
    ThreadBasedDispatcher.registerHandler(key, new MessageHandler {
      def handle(message: MessageHandle) {
        try {
          if (threadingIssueDetected.get) return
          if (guardLock.tryLock) {
            Thread.sleep(100)
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
    })
    ThreadBasedDispatcher.start
    for (i <- 0 until 100) {
      ThreadBasedDispatcher.messageQueue.append(new MessageHandle(key, new Object, new NullFutureResult))
    }
    assertTrue(handleLatch.await(5, TimeUnit.SECONDS))
    assertFalse(threadingIssueDetected.get)
  }

  private def internalTestMessagesDispatchedToDifferentHandlersAreExecutedConcurrently: Unit = {
    val handlersBarrier = new CyclicBarrier(3)
    val key1 = "key1"
    val key2 = "key2"
    ThreadBasedDispatcher.registerHandler(key1, new MessageHandler {
      def handle(message: MessageHandle) = synchronized {
        try {handlersBarrier.await(1, TimeUnit.SECONDS)}
        catch {case e: Exception => threadingIssueDetected.set(true)}
      }
    })
    ThreadBasedDispatcher.registerHandler(key2, new MessageHandler {
      def handle(message: MessageHandle) = synchronized {
        try {handlersBarrier.await(1, TimeUnit.SECONDS)}
        catch {case e: Exception => threadingIssueDetected.set(true)}
      }
    })
    ThreadBasedDispatcher.start
    ThreadBasedDispatcher.messageQueue.append(new MessageHandle(key1, new Object, new NullFutureResult))
    ThreadBasedDispatcher.messageQueue.append(new MessageHandle(key2, new Object, new NullFutureResult))
    handlersBarrier.await(1, TimeUnit.SECONDS)
    assertFalse(threadingIssueDetected.get)
  }

  private def internalTestMessagesDispatchedToHandlersAreExecutedInFIFOOrder: Unit = {
    val handleLatch = new CountDownLatch(200)
    val key1 = "key1"
    val key2 = "key2"
    ThreadBasedDispatcher.registerHandler(key1, new MessageHandler {
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
    ThreadBasedDispatcher.registerHandler(key2, new MessageHandler {
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
    ThreadBasedDispatcher.start
    for (i <- 0 until 100) {
      ThreadBasedDispatcher.messageQueue.append(new MessageHandle(key1, new Integer(i), new NullFutureResult))
      ThreadBasedDispatcher.messageQueue.append(new MessageHandle(key2, new Integer(i), new NullFutureResult))
    }
    assertTrue(handleLatch.await(5, TimeUnit.SECONDS))
    assertFalse(threadingIssueDetected.get)
  }
}
