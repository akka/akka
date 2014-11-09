package akka.stream.scaladsl

import akka.stream.scaladsl.FlowGraphImplicits._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.TwoStreamsSetup

class GraphZipSpec extends TwoStreamsSetup {

  override type Outputs = (Int, Int)
  val op = Zip[Int, Int]
  override def operationUnderTestLeft() = op.left
  override def operationUnderTestRight() = op.right

  "Zip" must {

    "work in the happy case" in {
      val probe = StreamTestKit.SubscriberProbe[(Int, String)]()

      FlowGraph { implicit b ⇒
        val zip = Zip[Int, String]

        Source(1 to 4) ~> zip.left
        Source(List("A", "B", "C", "D", "E", "F")) ~> zip.right

        zip.out ~> Sink(probe)
      }.run()

      val subscription = probe.expectSubscription()

      subscription.request(2)
      probe.expectNext((1, "A"))
      probe.expectNext((2, "B"))

      subscription.request(1)
      probe.expectNext((3, "C"))
      subscription.request(1)
      probe.expectNext((4, "D"))

      probe.expectComplete()
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
