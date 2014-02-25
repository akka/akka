package rx.async.tck

import java.util.concurrent._
import org.testng.annotations.AfterClass
import org.testng.Assert
import scala.annotation.tailrec
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import scala.collection.JavaConverters._
import rx.async.spi.{ Subscription, Subscriber, Publisher }

trait TestEnvironment {
  import TestEnvironment._

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

  def subscribe[T](pub: Publisher[T], sub: TestSubscriber[T], timeoutMillis: Int = 100): Unit = {
    pub.subscribe(sub)
    sub.subscription.expectCompletion(timeoutMillis, s"Could not subscribe $sub to Publisher $pub")
    verifyNoAsyncErrors()
  }

  def newManualSubscriber[T](pub: Publisher[T], timeoutMillis: Int = 100): ManualSubscriber[T] = {
    val sub = new ManualSubscriber[T] with SubscriptionSupport[T]
    subscribe(pub, sub, timeoutMillis)
    sub
  }

  def verifyNoAsyncErrors(): Unit =
    asyncErrors.asScala.foreach {
      case e: AssertionError ⇒ throw e
      case e                 ⇒ fail("Async error during test execution: " + e)
    }
}

object TestEnvironment {

  class TestSubscriber[T] extends Subscriber[T] {
    @volatile var subscription = new Promise[Subscription]
    def onError(cause: Throwable): Unit = fail(s"Unexpected Subscriber::onError($cause)")
    def onComplete(): Unit = fail("Unexpected Subscriber::onComplete()")
    def onNext(element: T): Unit = fail(s"Unexpected Subscriber::onNext($element)")
    def onSubscribe(subscription: Subscription): Unit = fail(s"Unexpected Subscriber::onSubscribe($subscription)")
    def cancel(): Unit =
      if (subscription.isCompleted) {
        subscription.value.cancel()
        subscription = new Promise[Subscription]
      } else fail("Cannot cancel a subscription before having received it")
  }

  class ManualSubscriber[T] extends TestSubscriber[T] {
    val received = new Receptacle[T]
    override def onNext(element: T) = received.add(element)
    override def onComplete() = received.complete()
    def requestMore(elements: Int): Unit = subscription.value.requestMore(elements)
    def requestNextElement(timeoutMillis: Int = 100, errorMsg: String = "Did not receive expected element"): T = {
      requestMore(1)
      nextElement(timeoutMillis, errorMsg)
    }
    def requestNextElementOrEndOfStream(timeoutMillis: Int = 100, errorMsg: String = "Did not receive expected stream completion"): Option[T] = {
      requestMore(1)
      nextElementOrEndOfStream(timeoutMillis, errorMsg)
    }
    def requestNextElements(elements: Int, timeoutMillis: Int = 100, errorMsg: String = "Did not receive expected elements"): Seq[T] = {
      requestMore(elements)
      nextElements(elements, timeoutMillis, errorMsg)
    }
    def nextElement(timeoutMillis: Int = 100, errorMsg: String = "Did not receive expected element"): T =
      received.next(timeoutMillis, errorMsg)
    def nextElementOrEndOfStream(timeoutMillis: Int = 100, errorMsg: String = "Did not receive expected stream completion"): Option[T] =
      received.nextOrEndOfStream(timeoutMillis, errorMsg)
    def nextElements(elements: Int, timeoutMillis: Int = 100, errorMsg: String = "Did not receive expected element or completion"): Seq[T] =
      received.nextN(elements, timeoutMillis, errorMsg)
    def expectCompletion(timeoutMillis: Int = 100, errorMsg: String = "Did not receive expected stream completion"): Unit =
      received.expectCompletion(timeoutMillis, errorMsg)
    def expectNone(withinMillis: Int = 100, errorMsg: T ⇒ String = "Did not expect an element but got " + _): Unit =
      received.expectNone(withinMillis, errorMsg)
  }

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
    def assertCompleted(errorMsg: String): Unit = if (!isCompleted) fail(errorMsg)
    def assertUncompleted(errorMsg: String): Unit = if (isCompleted) fail(errorMsg)
    def expectCompletion(timeoutMillis: Int, errorMsg: String): Unit =
      if (!isCompleted) abq.poll(timeoutMillis, TimeUnit.MILLISECONDS) match {
        case null ⇒ fail(s"$errorMsg within $timeoutMillis ms")
        case x    ⇒ _value = x
      }
  }

  // a "Promise" for multiple values, which also supports "end-of-stream reached"
  class Receptacle[T](val queueSize: Int = 16) {
    private val abq = new ArrayBlockingQueue[Option[T]](queueSize)
    def add(value: T): Unit = abq.add(Some(value))
    def complete(): Unit = abq.add(None)
    def next(timeoutMillis: Int, errorMsg: String): T =
      abq.poll(timeoutMillis, TimeUnit.MILLISECONDS) match {
        case null    ⇒ fail(s"$errorMsg within $timeoutMillis ms")
        case Some(x) ⇒ x
        case None    ⇒ fail(s"Expected element but got end-of-stream")
      }
    def nextOrEndOfStream(timeoutMillis: Int, errorMsg: String): Option[T] =
      abq.poll(timeoutMillis, TimeUnit.MILLISECONDS) match {
        case null ⇒ fail(s"$errorMsg within $timeoutMillis ms")
        case x    ⇒ x
      }
    def nextN(elements: Int, timeoutMillis: Int, errorMsg: String): Seq[T] = {
      @tailrec def rec(remaining: Int, result: Seq[T] = Nil): Seq[T] =
        if (remaining > 0)
          rec(remaining - 1, result :+ next(timeoutMillis, errorMsg)) // TODO: fix error messages showing wrong timeout info
        else result
      rec(elements)
    }
    def expectCompletion(timeoutMillis: Int, errorMsg: String): Unit =
      abq.poll(timeoutMillis, TimeUnit.MILLISECONDS) match {
        case null    ⇒ fail(s"$errorMsg within $timeoutMillis ms")
        case Some(x) ⇒ fail(s"Expected end-of-stream but got $x")
        case None    ⇒ // ok
      }
    def expectNone(withinMillis: Int, errorMsg: T ⇒ String): Unit = {
      Thread.sleep(withinMillis)
      abq.poll() match {
        case Some(x)     ⇒ fail(errorMsg(x))
        case null | None ⇒ // ok
      }
    }
  }

  def fail(msg: String): Nothing = Assert.fail(msg).asInstanceOf[Nothing]

  def expectThrowingOf[T <: Exception: ClassTag](errorMsg: String)(block: ⇒ Unit): Unit =
    try {
      block
      fail(errorMsg)
    } catch {
      case e: Exception if implicitly[ClassTag[T]].runtimeClass.isInstance(e) ⇒ // ok
      case NonFatal(e) ⇒ fail(s"$errorMsg but $e")
    }
}