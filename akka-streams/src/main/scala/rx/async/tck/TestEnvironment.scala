package rx.async.tck

import java.util.concurrent._
import org.testng.Assert.fail
import scala.annotation.tailrec
import scala.reflect.ClassTag
import scala.util.control.NonFatal
import scala.collection.JavaConverters._
import rx.async.spi.{ Subscription, Subscriber, Publisher }

trait TestEnvironment {

  val asyncErrors = new CopyOnWriteArrayList[Throwable]()

  // don't use the name `fail` as it would collide with other `fail` definitions like the one in scalatest's traits
  def flop(msg: String): Nothing =
    try fail(msg).asInstanceOf[Nothing]
    catch {
      case t: Throwable ⇒
        asyncErrors.add(t)
        throw t
    }

  def expectThrowingOf[T <: Exception: ClassTag](errorMsg: String)(block: ⇒ Unit): Unit =
    try {
      block
      flop(errorMsg)
    } catch {
      case e: Exception if implicitly[ClassTag[T]].runtimeClass.isInstance(e) ⇒ // ok
      case NonFatal(e) ⇒ flop(s"$errorMsg but $e")
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

  class TestSubscriber[T] extends Subscriber[T] {
    @volatile var subscription = new Promise[Subscription]
    def onError(cause: Throwable): Unit = flop(s"Unexpected Subscriber::onError($cause)")
    def onComplete(): Unit = flop("Unexpected Subscriber::onComplete()")
    def onNext(element: T): Unit = flop(s"Unexpected Subscriber::onNext($element)")
    def onSubscribe(subscription: Subscription): Unit = flop(s"Unexpected Subscriber::onSubscribe($subscription)")
    def cancel(): Unit =
      if (subscription.isCompleted) {
        subscription.value.cancel()
        subscription = new Promise[Subscription]
      } else flop("Cannot cancel a subscription before having received it")
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
    def expectNext(expected: T, timeoutMillis: Int = 100): Unit = {
      val received = nextElement(timeoutMillis, "Did not receive expected element on downstream")
      if (received != expected) flop(s"Expected element $expected on downstream but received $received")
    }
    def expectCompletion(timeoutMillis: Int = 100, errorMsg: String = "Did not receive expected stream completion"): Unit =
      received.expectCompletion(timeoutMillis, errorMsg)
    def expectNone(withinMillis: Int = 100, errorMsg: T ⇒ String = "Did not expect an element but got " + _): Unit =
      received.expectNone(withinMillis, errorMsg)
  }

  trait SubscriptionSupport[T] extends TestSubscriber[T] {
    abstract override def onNext(element: T) =
      if (subscription.isCompleted) super.onNext(element)
      else flop(s"Subscriber::onNext($element) called before Subscriber::onSubscribe")
    abstract override def onComplete() =
      if (subscription.isCompleted) super.onComplete()
      else flop("Subscriber::onComplete() called before Subscriber::onSubscribe")
    abstract override def onSubscribe(s: Subscription) =
      if (!subscription.isCompleted) subscription.complete(s)
      else flop("Subscriber::onSubscribe called on an already-subscribed Subscriber")
    abstract override def onError(cause: Throwable) =
      if (subscription.isCompleted) super.onError(cause)
      else flop(s"Subscriber::onError($cause) called before Subscriber::onSubscribe")
  }

  trait ErrorCollection[T] extends TestSubscriber[T] {
    val error = new Promise[Throwable]
    override def onError(cause: Throwable): Unit = error.complete(cause)
    def expectError(cause: Throwable, timeoutMillis: Int = 100): Unit = {
      error.expectCompletion(timeoutMillis, "Did not receive expected error on downstream")
      if (error.value != cause) flop(s"Expected error $cause but got ${error.value}")
    }
  }

  class ManualPublisher[T] extends Publisher[T] {
    var subscriber: Option[Subscriber[T]] = None
    val requests = new Receptacle[Int]()
    val cancelled = new Latch
    def subscribe(s: Subscriber[T]): Unit =
      if (subscriber.isEmpty) {
        subscriber = Some(s)
        s.onSubscribe {
          new Subscription {
            def requestMore(elements: Int): Unit = requests.add(elements)
            def cancel(): Unit = cancelled.close()
          }
        }
      } else flop("TestPublisher doesn't support more than one Subscriber")
    def sendNext(element: T): Unit =
      if (subscriber.isDefined) subscriber.get.onNext(element)
      else flop(s"Cannot sendNext before subscriber subscription")
    def sendCompletion(): Unit =
      if (subscriber.isDefined) subscriber.get.onComplete()
      else flop(s"Cannot sendCompletion before subscriber subscription")
    def sendError(cause: Throwable): Unit =
      if (subscriber.isDefined) subscriber.get.onError(cause)
      else flop(s"Cannot sendError before subscriber subscription")
    def nextRequestMore(timeoutMillis: Int = 100): Int =
      requests.next(timeoutMillis, "Did not receive expected `requestMore` call")
    def expectRequestMore(expected: Int, timeoutMillis: Int = 100): Unit = {
      val requested = nextRequestMore(timeoutMillis)
      if (requested != expected) flop(s"Received `requestMore($requested)` on upstream but expected `requestMore($expected)`")
    }
    def expectNoRequestMore(timeoutMillis: Int = 100): Unit =
      requests.expectNone(timeoutMillis, "Received an unexpected `requestMore" + _ + "` call")
    def expectCancelling(timeoutMillis: Int = 100): Unit =
      cancelled.expectClose(timeoutMillis, "Did not receive expected cancelling of upstream subscription")
  }

  // like a CountDownLatch, but resettable and with some convenience methods
  class Latch() {
    @volatile private var countDownLatch = new CountDownLatch(1)
    def reOpen() = countDownLatch = new CountDownLatch(1)
    def isClosed: Boolean = countDownLatch.getCount == 0
    def close() = countDownLatch.countDown()
    def assertClosed(openErrorMsg: String): Unit = if (!isClosed) flop(openErrorMsg)
    def assertOpen(closedErrorMsg: String): Unit = if (isClosed) flop(closedErrorMsg)
    def expectClose(timeoutMillis: Int, notClosedErrorMsg: String): Unit = {
      countDownLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
      if (countDownLatch.getCount > 0) flop(s"$notClosedErrorMsg within $timeoutMillis ms")
    }
  }

  // simple promise for *one* value, which cannot be reset
  class Promise[T] {
    private val abq = new ArrayBlockingQueue[T](1)
    @volatile private var _value: T = _
    def value: T = if (isCompleted) _value else flop("Cannot access promise value before completion")
    def isCompleted: Boolean = _value.asInstanceOf[AnyRef] ne null
    def complete(value: T): Unit = abq.add(value)
    def assertCompleted(errorMsg: String): Unit = if (!isCompleted) flop(errorMsg)
    def assertUncompleted(errorMsg: String): Unit = if (isCompleted) flop(errorMsg)
    def expectCompletion(timeoutMillis: Int, errorMsg: String): Unit =
      if (!isCompleted) abq.poll(timeoutMillis, TimeUnit.MILLISECONDS) match {
        case null ⇒ flop(s"$errorMsg within $timeoutMillis ms")
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
        case null    ⇒ flop(s"$errorMsg within $timeoutMillis ms")
        case Some(x) ⇒ x
        case None    ⇒ flop(s"Expected element but got end-of-stream")
      }
    def nextOrEndOfStream(timeoutMillis: Int, errorMsg: String): Option[T] =
      abq.poll(timeoutMillis, TimeUnit.MILLISECONDS) match {
        case null ⇒ flop(s"$errorMsg within $timeoutMillis ms")
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
        case null    ⇒ flop(s"$errorMsg within $timeoutMillis ms")
        case Some(x) ⇒ flop(s"Expected end-of-stream but got $x")
        case None    ⇒ // ok
      }
    def expectNone(withinMillis: Int, errorMsg: T ⇒ String): Unit = {
      Thread.sleep(withinMillis)
      abq.poll() match {
        case null    ⇒ // ok
        case Some(x) ⇒ flop(errorMsg(x))
        case None    ⇒ flop("Expected no element but got end-of-stream")
      }
    }
  }
}