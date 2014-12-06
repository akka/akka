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

    "zipWith a ETA expanded Person.apply (3 inputs)" in {
      val probe = StreamTestKit.SubscriberProbe[Person]()

      case class Person(name: String, surname: String, int: Int)

      FlowGraph { implicit b ⇒
        val zip = ZipWith(Person.apply _)

        Source.single("Caplin") ~> zip.input1
        Source.single("Capybara") ~> zip.input2
        Source.single(3) ~> zip.input3

        zip.out ~> Sink(probe)
      }.run()

      val subscription = probe.expectSubscription()

      subscription.request(5)
      probe.expectNext(Person("Caplin", "Capybara", 3))

      probe.expectComplete()
    }

    "work with up to 22 inputs" in {
      val probe = StreamTestKit.SubscriberProbe[String]()

      FlowGraph { implicit b ⇒

        val sum22 = (v1: Int, v2: String, v3: Int, v4: String, v5: Int, v6: String, v7: Int, v8: String, v9: Int, v10: String,
          v11: Int, v12: String, v13: Int, v14: String, v15: Int, v16: String, v17: Int, v18: String, v19: Int,
          v20: String, v21: Int, v22: String) ⇒
          v1 + v2 + v3 + v4 + v5 + v6 + v7 + v8 + v9 + v10 +
            v11 + v12 + v13 + v14 + v15 + v16 + v17 + v18 + v19 + v20 +
            v21 + v22

        // odd input ports will be Int, even input ports will be String
        val zip = ZipWith(sum22)

        val one = Source.single(1)

        one ~> zip.input1
        one.map(_.toString) ~> zip.input2
        one ~> zip.input3
        one.map(_.toString) ~> zip.input4
        one ~> zip.input5
        one.map(_.toString) ~> zip.input6
        one ~> zip.input7
        one.map(_.toString) ~> zip.input8
        one ~> zip.input9
        one.map(_.toString) ~> zip.input10
        one ~> zip.input11
        one.map(_.toString) ~> zip.input12
        one ~> zip.input13
        one.map(_.toString) ~> zip.input14
        one ~> zip.input15
        one.map(_.toString) ~> zip.input16
        one ~> zip.input17
        one.map(_.toString) ~> zip.input18
        one ~> zip.input19
        one.map(_.toString) ~> zip.input20
        one ~> zip.input21
        one.map(_.toString) ~> zip.input22

        zip.out ~> Sink(probe)
      }.run()

      val subscription = probe.expectSubscription()

      subscription.request(1)
      probe.expectNext("1" * 22)

      probe.expectComplete()

    }

  }

}
