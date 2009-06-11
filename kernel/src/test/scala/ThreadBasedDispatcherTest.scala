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

  //@Test
  def testMessagesDispatchedToHandlersAreExecutedInFIFOOrder = {
    internalTestMessagesDispatchedToHandlersAreExecutedInFIFOOrder
  }

  private def internalTestMessagesDispatchedToTheSameHandlerAreExecutedSequentially: Unit = {
    val guardLock = new ReentrantLock
    val handleLatch = new CountDownLatch(100)
    val key = "key"
    val dispatcher = new EventBasedThreadPoolDispatcher
    dispatcher.registerHandler(key, new MessageHandler {
      def handle(message: MessageHandle) {
        try {
          if (threadingIssueDetected.get) return
          if (guardLock.tryLock) {
            Thread.sleep(100)
            handleLatch.countDown
          } else threadingIssueDetected.set(true)
        } catch {
          case e: Exception => threadingIssueDetected.set(true)
        } finally {
          guardLock.unlock
        }
      }
    })
    dispatcher.start
    for (i <- 0 until 100) {
      dispatcher.messageQueue.append(new MessageHandle(key, new Object, new NullFutureResult))
    }
    assertTrue(handleLatch.await(5000, TimeUnit.SECONDS))
    assertFalse(threadingIssueDetected.get)
    //dispatcher.shutdown
  }

  private def internalTestMessagesDispatchedToDifferentHandlersAreExecutedConcurrently: Unit = {
    val handlersBarrier = new CyclicBarrier(3)
    val key1 = "key1"
    val key2 = "key2"
    val dispatcher = new EventBasedThreadPoolDispatcher
    dispatcher.registerHandler(key1, new MessageHandler {
      def handle(message: MessageHandle) = synchronized {
        try {handlersBarrier.await(1, TimeUnit.SECONDS)}
        catch {case e: Exception => threadingIssueDetected.set(true)}
      }
    })
    dispatcher.registerHandler(key2, new MessageHandler {
      def handle(message: MessageHandle) = synchronized {
        try {handlersBarrier.await(1, TimeUnit.SECONDS)}
        catch {case e: Exception => threadingIssueDetected.set(true)}
      }
    })
    dispatcher.start
    dispatcher.messageQueue.append(new MessageHandle(key1, "Sending Message 1", new NullFutureResult))
    dispatcher.messageQueue.append(new MessageHandle(key2, "Sending Message 2", new NullFutureResult))
    handlersBarrier.await(5, TimeUnit.SECONDS)
    assertFalse(threadingIssueDetected.get)
    //dispatcher.shutdown
  }

  private def internalTestMessagesDispatchedToHandlersAreExecutedInFIFOOrder: Unit = {
    val handleLatch = new CountDownLatch(200)
    val key1 = "key1"
    val key2 = "key2"
    val dispatcher = new EventBasedThreadPoolDispatcher
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
          currentValue = messageValue .intValue
          handleLatch.countDown
        } else threadingIssueDetected.set(true)
      }
    })
    dispatcher.start
    for (i <- 0 until 100) {
      dispatcher.messageQueue.append(new MessageHandle(key1, new Integer(i), new NullFutureResult))
      dispatcher.messageQueue.append(new MessageHandle(key2, new Integer(i), new NullFutureResult))
    }
    assertTrue(handleLatch.await(10, TimeUnit.SECONDS))
    assertFalse(threadingIssueDetected.get)
    dispatcher.shutdown
  }
}
