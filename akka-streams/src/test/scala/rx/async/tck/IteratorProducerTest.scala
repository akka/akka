package rx.async.tck

import akka.streams.Producer
import org.junit.Test
import org.junit.Assert._
import org.scalatest.junit.JUnitSuiteLike
import rx.async.spi.Publisher

class IteratorProducerTest extends PublisherVerification[Int] with JUnitSuiteLike {
  import TestCaseEnvironment._

  def createPublisher(elements: Int): Publisher[Int] =
    Producer(Stream from 1000 take elements).getPublisher

  @Test
  def onNextMustBeCalledInLockstepOnAllActiveSubscribers(): Unit = {
    val pub = createPublisher(5)
    val sub1 = new FullManualSubscriber[Int]
    subscribe(pub, sub1)
    val sub2 = new FullManualSubscriber[Int]
    subscribe(pub, sub2)
    val sub3 = new FullManualSubscriber[Int]
    subscribe(pub, sub3)

    sub1.requestOne()
    sub2.requestN(2) // now only sub3 is holding us back
    sub1.expectNone(withinMillis = 50, x ⇒ s"Publisher $pub produced $x on 1st subscriber outside of lockstep")
    sub2.expectNone(withinMillis = 50, x ⇒ s"Publisher $pub produced $x on 2nd subscriber outside of lockstep")
    sub3.expectNone(withinMillis = 50, x ⇒ s"Publisher $pub produced unrequested $x on 3rd subscriber")

    sub3.requestOne() // now one can be released
    val x1 = sub1.expectOne(timeoutMillis = 50, s"Publisher $pub did not produce requested element on 1st subscriber")
    val x2 = sub2.expectOne(timeoutMillis = 50, s"Publisher $pub did not produce requested element on 2nd subscriber")
    val x3 = sub3.expectOne(timeoutMillis = 50, s"Publisher $pub did not produce requested element on 3rd subscriber")
    assertEquals("Element for 1st subscriber did not match element for 2nd subscriber", x1, x2)
    assertEquals("Element for 1st subscriber did not match element for 3rd subscriber", x1, x3)
    sub1.expectNone(withinMillis = 50, x ⇒ s"Publisher $pub produced unrequested $x on 1st subscriber")
    sub2.expectNone(withinMillis = 50, x ⇒ s"Publisher $pub produced $x on 2nd subscriber outside of lockstep")
    sub3.expectNone(withinMillis = 50, x ⇒ s"Publisher $pub produced unrequested $x on 3rd subscriber")

    sub3.requestOne() // now only sub1 is holding us back
    sub1.expectNone(withinMillis = 50, x ⇒ s"Publisher $pub produced unrequested $x on 1st subscriber")
    sub2.expectNone(withinMillis = 50, x ⇒ s"Publisher $pub produced $x on 2nd subscriber outside of lockstep")
    sub3.expectNone(withinMillis = 50, x ⇒ s"Publisher $pub produced $x on 3rd subscriber outside of lockstep")

    sub1.requestOne() // now one can be released
    val y1 = sub1.expectOne(timeoutMillis = 50, s"Publisher $pub did not produce requested element on 1st subscriber")
    val y2 = sub2.expectOne(timeoutMillis = 50, s"Publisher $pub did not produce requested element on 2nd subscriber")
    val y3 = sub3.expectOne(timeoutMillis = 50, s"Publisher $pub did not produce requested element on 3rd subscriber")
    assertEquals("Element for 1st subscriber did not match element for 2nd subscriber", y1, y2)
    assertEquals("Element for 1st subscriber did not match element for 3rd subscriber", y1, y3)
    sub1.expectNone(withinMillis = 50, x ⇒ s"Publisher $pub produced unrequested $x on 1st subscriber")
    sub2.expectNone(withinMillis = 50, x ⇒ s"Publisher $pub produced unrequested $x on 2nd subscriber")
    sub3.expectNone(withinMillis = 50, x ⇒ s"Publisher $pub produced unrequested $x on 3rd subscriber")

    verifyNoAsyncErrors()
  }
}
