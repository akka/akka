package se.scalablesolutions.akka.reactor

import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{Executors, CountDownLatch, CyclicBarrier, TimeUnit}
import org.junit.Before
import org.junit.Test
import org.junit.Assert._
import junit.framework.TestCase

class EventBasedThreadPoolDispatcherTest extends TestCase {
  private var threadingIssueDetected: AtomicBoolean = null

  @Before
  override def setUp = {
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
    val dispatcher = Dispatchers.newEventBasedThreadPoolDispatcher("name")
    dispatcher.withNewThreadPoolWithBoundedBlockingQueue(100)
            .setCorePoolSize(2)
            .setMaxPoolSize(4)
            .setKeepAliveTimeInMillis(60000)
            .setRejectionPolicy(new CallerRunsPolicy)
            .buildThreadPool
    dispatcher.registerHandler(key, new MessageInvoker {
      def invoke(message: MessageInvocation) {
        try {
          if (threadingIssueDetected.get) return
          if (guardLock.tryLock) {
            Thread.sleep(100)
            handleLatch.countDown
          } else {
            threadingIssueDetected.set(true)
            return
          }
        } catch {
          case e: Exception => threadingIssueDetected.set(true); e.printStackTrace
        } finally {
          guardLock.unlock
        }
      }
    })
    dispatcher.start
    for (i <- 0 until 10) {
      dispatcher.messageQueue.append(new MessageInvocation(key, new Object, None, None))
    }
    assertTrue(handleLatch.await(5, TimeUnit.SECONDS))
    assertFalse(threadingIssueDetected.get)
  }

  private def internalTestMessagesDispatchedToDifferentHandlersAreExecutedConcurrently: Unit = {
    val guardLock1 = new ReentrantLock
    val guardLock2 = new ReentrantLock
    val handlersBarrier = new CyclicBarrier(3)
    val key1 = "key1"
    val key2 = "key2"
    val dispatcher = Dispatchers.newEventBasedThreadPoolDispatcher("name")
    dispatcher.withNewThreadPoolWithBoundedBlockingQueue(100)
            .setCorePoolSize(2)
            .setMaxPoolSize(4)
            .setKeepAliveTimeInMillis(60000)
            .setRejectionPolicy(new CallerRunsPolicy)
            .buildThreadPool
    dispatcher.registerHandler(key1, new MessageInvoker {
      def invoke(message: MessageInvocation) = synchronized {
        try {
          if (guardLock1.tryLock) {
            handlersBarrier.await(1, TimeUnit.SECONDS)
          } else {
            threadingIssueDetected.set(true);
          }
        }
        catch {case e: Exception => threadingIssueDetected.set(true)}
      }
    })
    dispatcher.registerHandler(key2, new MessageInvoker {
      def invoke(message: MessageInvocation) = synchronized {
        try {
          if (guardLock2.tryLock) {
            handlersBarrier.await(1, TimeUnit.SECONDS)
          } else {
            threadingIssueDetected.set(true);
          }
        }
        catch {case e: Exception => threadingIssueDetected.set(true)}
      }
    })
    dispatcher.start
    dispatcher.messageQueue.append(new MessageInvocation(key1, "Sending Message 1", None, None))
    dispatcher.messageQueue.append(new MessageInvocation(key1, "Sending Message 1.1", None, None))
    dispatcher.messageQueue.append(new MessageInvocation(key2, "Sending Message 2", None, None))
    dispatcher.messageQueue.append(new MessageInvocation(key2, "Sending Message 2.2", None, None))

    handlersBarrier.await(5, TimeUnit.SECONDS)
    assertFalse(threadingIssueDetected.get)
  }

  private def internalTestMessagesDispatchedToHandlersAreExecutedInFIFOOrder: Unit = {
    val handleLatch = new CountDownLatch(200)
    val key1 = "key1"
    val key2 = "key2"
    val dispatcher = Dispatchers.newEventBasedThreadPoolDispatcher("name")
    dispatcher.withNewThreadPoolWithBoundedBlockingQueue(100)
            .setCorePoolSize(2)
            .setMaxPoolSize(4)
            .setKeepAliveTimeInMillis(60000)
            .setRejectionPolicy(new CallerRunsPolicy)
            .buildThreadPool
    dispatcher.registerHandler(key1, new MessageInvoker {
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
    dispatcher.registerHandler(key2, new MessageInvoker {
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
      dispatcher.messageQueue.append(new MessageInvocation(key1, new java.lang.Integer(i), None, None))
      dispatcher.messageQueue.append(new MessageInvocation(key2, new java.lang.Integer(i), None, None))
    }
    assertTrue(handleLatch.await(5, TimeUnit.SECONDS))
    assertFalse(threadingIssueDetected.get)
  }
}
