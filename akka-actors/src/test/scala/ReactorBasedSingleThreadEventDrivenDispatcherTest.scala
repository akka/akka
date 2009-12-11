package se.scalablesolutions.akka.dispatch

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

import org.junit.{Test, Before}
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.Actor

class ReactorBasedSingleThreadEventDrivenDispatcherTest extends JUnitSuite {
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
  def setUp = {
    threadingIssueDetected = new AtomicBoolean(false)
  }

  @Test def shouldMessagesDispatchedToTheSameHandlerAreExecutedSequentially = {
    internalTestMessagesDispatchedToTheSameHandlerAreExecutedSequentially
  }

  @Test def shouldMessagesDispatchedToDifferentHandlersAreExecutedSequentially = {
    internalTestMessagesDispatchedToDifferentHandlersAreExecutedSequentially
  }

  @Test def shouldMessagesDispatchedToHandlersAreExecutedInFIFOOrder = {
    internalTestMessagesDispatchedToHandlersAreExecutedInFIFOOrder
  }

  val key1 = new Actor { def receive = { case _ => {}} }
  val key2 = new Actor { def receive = { case _ => {}} }
  val key3 = new Actor { def receive = { case _ => {}} }

  private def internalTestMessagesDispatchedToTheSameHandlerAreExecutedSequentially: Unit = {
    val guardLock = new ReentrantLock
    val handleLatch = new CountDownLatch(100)
    val dispatcher = new ReactorBasedSingleThreadEventDrivenDispatcher("name")
    dispatcher.registerHandler(key1, new TestMessageHandle(handleLatch))
    dispatcher.start
    for (i <- 0 until 100) {
      dispatcher.dispatch(new MessageInvocation(key1, new Object, None, None, None))
    }
    assert(handleLatch.await(5, TimeUnit.SECONDS))
    assert(!threadingIssueDetected.get)
  }

  private def internalTestMessagesDispatchedToDifferentHandlersAreExecutedSequentially: Unit = {
    val handleLatch = new CountDownLatch(2)
    val dispatcher = new ReactorBasedSingleThreadEventDrivenDispatcher("name")
    dispatcher.registerHandler(key1, new TestMessageHandle(handleLatch))
    dispatcher.registerHandler(key2, new TestMessageHandle(handleLatch))
    dispatcher.start
    dispatcher.dispatch(new MessageInvocation(key1, new Object, None, None, None))
    dispatcher.dispatch(new MessageInvocation(key2, new Object, None, None, None))
    assert(handleLatch.await(5, TimeUnit.SECONDS))
    assert(!threadingIssueDetected.get)
  }

  private def internalTestMessagesDispatchedToHandlersAreExecutedInFIFOOrder: Unit = {
    val handleLatch = new CountDownLatch(200)
    val dispatcher = new ReactorBasedSingleThreadEventDrivenDispatcher("name")
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
      dispatcher.dispatch(new MessageInvocation(key1, new java.lang.Integer(i), None, None, None))
      dispatcher.dispatch(new MessageInvocation(key2, new java.lang.Integer(i), None, None, None))
    }
    assert(handleLatch.await(5, TimeUnit.SECONDS))
    assert(!threadingIssueDetected.get)
    dispatcher.shutdown
  }
}
