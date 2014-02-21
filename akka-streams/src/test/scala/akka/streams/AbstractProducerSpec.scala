package akka.streams

import org.scalatest.{ ShouldMatchers, WordSpec }
import rx.async.tck.TestCaseEnvironment
import TestCaseEnvironment._

class AbstractProducerSpec extends WordSpec with ShouldMatchers with TestCaseEnvironment {

  "An AbstractProducer" should {
    "pass test scenario 1" in new Test(iSize = 1, mSize = 1) {
      val sub1 = newSubscriber()
      sub1.requestMore(5)

      nextRequestMore() shouldEqual 1
      sendNext('a)
      sub1.nextElement() shouldEqual 'a

      nextRequestMore() shouldEqual 1
      sendNext('b)
      sub1.nextElement() shouldEqual 'b

      nextRequestMore() shouldEqual 1
      val sub2 = newSubscriber()

      // sub1 now has 3 pending
      // sub2 has 0 pending

      sendNext('c)
      sub1.nextElement() shouldEqual 'c
      sub2.expectNone()

      sub2.requestMore(1)
      sub2.nextElement() shouldEqual 'c

      nextRequestMore() shouldEqual 1 // because sub1 still has 2 pending
    }
  }

  class Test(iSize: Int, mSize: Int) extends AbstractStrictProducer[Symbol](iSize, mSize) {
    private val requests = new Receptacle[Int]()
    override protected def requestFromUpstream(elements: Int): Unit = requests.add(elements)
    def nextRequestMore(timeoutMillis: Int = 100): Int =
      requests.next(timeoutMillis, "Did not receive expected `requestMore` call")
    def expectNoRequestMore(timeoutMillis: Int = 100): Unit =
      requests.expectNone(timeoutMillis, "Received an unexpected `requestMore" + _ + "` call")
    def sendNext(element: Symbol): Unit = pushToDownstream(element)
    def newSubscriber() = newManualSubscriber(this)
  }
}