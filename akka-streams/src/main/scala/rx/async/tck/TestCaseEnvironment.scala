package rx.async.tck

import java.util.concurrent._
import org.testng.annotations.AfterClass
import scala.annotation.tailrec
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import scala.collection.JavaConverters._
import rx.async.spi.{ Subscription, Subscriber, Publisher }

abstract class TestCaseEnvironment {
  import TestCaseEnvironment._

  val asyncErrors = new CopyOnWriteArrayList[Throwable]()

  val executor: ExecutorService = Executors.newCachedThreadPool()

  @AfterClass
  def shutdownExecutor(): Unit = {
    executor.shutdown()
    try {
      if (!executor.awaitTermination(500, TimeUnit.MILLISECONDS))
        executor.shutdownNow()
    } catch {
      case _: InterruptedException ⇒ executor.shutdownNow()
    }
  }

  def async[U](block: ⇒ U): Unit =
    executor.execute {
      new Runnable {
        def run(): Unit =
          try block
          catch {
            case e: Throwable ⇒ asyncErrors.add(e)
          }
      }
    }

  def drain[T](pub: Publisher[T], timeoutMillis: Int): Unit = {
    val sub = new FullManualSubscriber[T]
    subscribe(pub, sub)
    while (sub.requestAndExpectOneOrEndOfStream(timeoutMillis = 100, s"Timeout while draining Publisher $pub").isDefined) {}
    sub.expectNone(withinMillis = 100, x ⇒ s"Publisher $pub called `onNext($x)` after `onComplete`")
    verifyNoAsyncErrors()
  }

  def subscribe[T](pub: Publisher[T], sub: TestSubscriber[T]): Unit = {
    pub.subscribe(sub)
    sub.subscription.expectCompletion(timeoutMillis = 100, s"Could not subscribe to Publisher $pub")
    verifyNoAsyncErrors()
  }

  def verifyNoAsyncErrors(): Unit =
    asyncErrors.asScala.foreach {
      case e: AssertionError ⇒ throw e
      case e                 ⇒ fail("Async error during test execution: " + e)
    }

  def verifyNoAsyncErrorsAfter(delayMillis: Int): Unit = {
    Thread.sleep(delayMillis)
    verifyNoAsyncErrors()
  }
}

object TestCaseEnvironment {

  class TestSubscriber[T] extends Subscriber[T] {
    val subscription = new Promise[Subscription]
    def onError(cause: Throwable): Unit = fail(s"Unexpected Subscriber::onError($cause)")
    def onComplete(): Unit = fail("Unexpected Subscriber::onComplete()")
    def onNext(element: T): Unit = fail(s"Unexpected Subscriber::onNext($element)")
    def onSubscribe(subscription: Subscription): Unit = fail(s"Unexpected Subscriber::onSubscribe($subscription)")
  }

  class ManualSubscriber[T] extends TestSubscriber[T] {
    val received = new Receptacle[T]
    override def onNext(element: T) = received.add(element)
    override def onComplete() = received.complete()
    def requestOne(): Unit = requestN(1)
    def requestN(elements: Int): Unit = {
      subscription.value.requestMore(elements)
    }
    def requestAndExpectOne(timeoutMillis: Int, notReceivedErrorMsg: String): T = {
      requestOne()
      expectOne(timeoutMillis, notReceivedErrorMsg)
    }
    def requestAndExpectOneOrEndOfStream(timeoutMillis: Int, notReceivedErrorMsg: String): Option[T] = {
      requestOne()
      expectOneOrEndOfStream(timeoutMillis, notReceivedErrorMsg)
    }
    def requestAndExpectN(elements: Int, timeoutMillis: Int, notReceivedErrorMsg: String): Seq[T] = {
      requestN(elements)
      expectN(elements, timeoutMillis, notReceivedErrorMsg)
    }
    def expectOne(timeoutMillis: Int, notReceivedErrorMsg: String): T =
      received.expectOne(timeoutMillis, notReceivedErrorMsg)
    def expectOneOrEndOfStream(timeoutMillis: Int, notReceivedErrorMsg: String): Option[T] =
      received.expectOneOrEndOfStream(timeoutMillis, notReceivedErrorMsg)
    def expectN(elements: Int, timeoutMillis: Int, notReceivedErrorMsg: String): Seq[T] =
      received.expectN(elements, timeoutMillis, notReceivedErrorMsg)
    def expectCompletion(timeoutMillis: Int, notCompletedErrorMsg: String): Unit =
      received.expectCompletion(timeoutMillis, notCompletedErrorMsg)
    def expectNone(withinMillis: Int, didReceiveErrorMsg: T ⇒ String): Unit =
      received.expectNone(withinMillis, didReceiveErrorMsg)
  }

  class FullManualSubscriber[T] extends ManualSubscriber[T] with SubscriptionSupport[T]

  trait SubscriptionSupport[T] extends TestSubscriber[T] {
    abstract override def onNext(element: T) =
      if (subscription.isCompleted) super.onNext(element)
      else fail(s"Subscriber::onNext($element) called before Subscriber::onSubscribe")
    abstract override def onComplete() =
      if (subscription.isCompleted) super.onComplete()
      else fail("Subscriber::onComplete() called before Subscriber::onSubscribe")
    abstract override def onSubscribe(s: Subscription) =
      if (!subscription.isCompleted) subscription.complete(s)
      else fail("Subscriber::onSubscribe called on an already-subscribed Subscriber")
    abstract override def onError(cause: Throwable) =
      if (subscription.isCompleted) super.onError(cause)
      else fail(s"Subscriber::onError($cause) called before Subscriber::onSubscribe")
  }

  // like a CountDownLatch, but resettable and with some convenience methods
  class Latch() {
    @volatile private var countDownLatch = new CountDownLatch(1)
    def reOpen() = countDownLatch = new CountDownLatch(1)
    def isClosed: Boolean = countDownLatch.getCount == 0
    def close() = countDownLatch.countDown()
    def assertClosed(openErrorMsg: String): Unit = if (!isClosed) fail(openErrorMsg)
    def assertOpen(closedErrorMsg: String): Unit = if (isClosed) fail(closedErrorMsg)
    def expectClose(timeoutMillis: Int, notClosedErrorMsg: String): Unit = {
      countDownLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
      if (countDownLatch.getCount > 0) fail(s"$notClosedErrorMsg within $timeoutMillis ms")
    }
  }

  // simple promise for *one* value, which cannot be reset
  class Promise[T] {
    private val abq = new ArrayBlockingQueue[T](1)
    @volatile private var _value: T = _
    def value: T = if (isCompleted) _value else fail("Cannot access promise value before completion")
    def isCompleted: Boolean = _value.asInstanceOf[AnyRef] ne null
    def complete(value: T): Unit = abq.add(value)
    def assertCompleted(notCompletedErrorMsg: String): Unit = if (!isCompleted) fail(notCompletedErrorMsg)
    def assertUncompleted(completedErrorMsg: String): Unit = if (isCompleted) fail(completedErrorMsg)
    def expectCompletion(timeoutMillis: Int, notCompletedErrorMsg: String): Unit =
      if (!isCompleted) abq.poll(timeoutMillis, TimeUnit.MILLISECONDS) match {
        case null ⇒ fail(s"$notCompletedErrorMsg within $timeoutMillis ms")
        case x    ⇒ _value = x
      }
  }

  // a "Promise" for multiple values, which also supports "end-of-stream reached"
  class Receptacle[T](val queueSize: Int = 16) {
    private val abq = new ArrayBlockingQueue[Option[T]](queueSize)
    def add(value: T): Unit = abq.add(Some(value))
    def complete(): Unit = abq.add(None)
    def expectOne(timeoutMillis: Int, notReceivedErrorMsg: String): T =
      abq.poll(timeoutMillis, TimeUnit.MILLISECONDS) match {
        case null    ⇒ fail(s"$notReceivedErrorMsg within $timeoutMillis ms")
        case Some(x) ⇒ x
        case None    ⇒ fail(s"Expected element but got end-of-stream")
      }
    def expectOneOrEndOfStream(timeoutMillis: Int, notReceivedErrorMsg: String): Option[T] =
      abq.poll(timeoutMillis, TimeUnit.MILLISECONDS) match {
        case null ⇒ fail(s"$notReceivedErrorMsg within $timeoutMillis ms")
        case x    ⇒ x
      }
    def expectN(elements: Int, timeoutMillis: Int, notReceivedErrorMsg: String): Seq[T] = {
      @tailrec def rec(remaining: Int, result: Seq[T] = Nil): Seq[T] =
        if (remaining > 0)
          rec(remaining - 1, result :+ expectOne(timeoutMillis, notReceivedErrorMsg)) // TODO: fix error messages showing wrong timeout info
        else result
      rec(elements)
    }
    def expectCompletion(timeoutMillis: Int, notCompletedErrorMsg: String): Unit =
      abq.poll(timeoutMillis, TimeUnit.MILLISECONDS) match {
        case null    ⇒ fail(s"$notCompletedErrorMsg within $timeoutMillis ms")
        case Some(x) ⇒ fail(s"Expected end-of-stream but got $x")
        case None    ⇒ // ok
      }
    def expectNone(withinMillis: Int, didReceiveErrorMsg: T ⇒ String): Unit = {
      Thread.sleep(withinMillis)
      abq.poll() match {
        case Some(x)     ⇒ fail(didReceiveErrorMsg(x))
        case null | None ⇒ // ok
      }
    }
  }

  def fail(msg: String): Nothing = org.junit.Assert.fail(msg).asInstanceOf[Nothing]

  def expectThrowingOf[T <: Exception: ClassTag](notThrownErrorMsg: Throwable ⇒ String)(block: ⇒ Unit): Unit =
    try block
    catch {
      case e: Exception if implicitly[ClassTag[T]].runtimeClass.isInstance(e) ⇒ // ok
      case NonFatal(e) ⇒ fail(notThrownErrorMsg(e))
    }
}