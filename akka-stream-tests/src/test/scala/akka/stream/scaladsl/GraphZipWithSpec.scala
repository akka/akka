package akka.stream.scaladsl

import akka.stream.scaladsl.FlowGraphImplicits._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.TwoStreamsSetup
import scala.concurrent.duration._

class GraphZipWithSpec extends TwoStreamsSetup {

  override type Outputs = Int
  val op = ZipWith((_: Int) + (_: Int))
  override def operationUnderTestLeft() = op.left
  override def operationUnderTestRight() = op.right

  "ZipWith" must {

    "work in the happy case" in {
      val probe = StreamTestKit.SubscriberProbe[Outputs]()

      FlowGraph { implicit b ⇒
        val zip = ZipWith((_: Int) + (_: Int))

        Source(1 to 4) ~> zip.left
        Source(10 to 40 by 10) ~> zip.right

        zip.out ~> Sink(probe)
      }.run()

      val subscription = probe.expectSubscription()

      subscription.request(2)
      probe.expectNext(11)
      probe.expectNext(22)

      subscription.request(1)
      probe.expectNext(33)
      subscription.request(1)
      probe.expectNext(44)

      probe.expectComplete()
    }

    "work in the sad case" in {
      val probe = StreamTestKit.SubscriberProbe[Outputs]()

      FlowGraph { implicit b ⇒
        val zip = ZipWith[Int, Int, Int]((_: Int) / (_: Int))

        Source(1 to 4) ~> zip.left
        Source(-2 to 2) ~> zip.right

        zip.out ~> Sink(probe)
      }.run()

      val subscription = probe.expectSubscription()

      subscription.request(2)
      probe.expectNext(1 / -2)
      probe.expectNext(2 / -1)

      subscription.request(2)
      probe.expectError() match {
        case a: java.lang.ArithmeticException ⇒ a.getMessage should be("/ by zero")
      }
      probe.expectNoMsg(200.millis)
    }

    commonTests()

    "work with one immediately completed and one nonempty publisher" in {
      val subscriber1 = setup(completedPublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectCompletedOrSubscriptionFollowedByComplete()

      val subscriber2 = setup(nonemptyPublisher(1 to 4), completedPublisher)
      subscriber2.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "work with one delayed completed and one nonempty publisher" in {
      val subscriber1 = setup(soonToCompletePublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectCompletedOrSubscriptionFollowedByComplete()

      val subscriber2 = setup(nonemptyPublisher(1 to 4), soonToCompletePublisher)
      subscriber2.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "work with one immediately failed and one nonempty publisher" in {
      val subscriber1 = setup(failedPublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectErrorOrSubscriptionFollowedByError(TestException)

      val subscriber2 = setup(nonemptyPublisher(1 to 4), failedPublisher)
      subscriber2.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    "work with one delayed failed and one nonempty publisher" in {
      val subscriber1 = setup(soonToFailPublisher, nonemptyPublisher(1 to 4))
      subscriber1.expectErrorOrSubscriptionFollowedByError(TestException)

      val subscriber2 = setup(nonemptyPublisher(1 to 4), soonToFailPublisher)
      val subscription2 = subscriber2.expectErrorOrSubscriptionFollowedByError(TestException)
    }

  }

}
