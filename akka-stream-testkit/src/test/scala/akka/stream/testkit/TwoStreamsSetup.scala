package akka.stream.testkit

import akka.stream.{ ActorFlowMaterializer, ActorFlowMaterializerSettings, Inlet, Outlet }
import akka.stream.scaladsl._
import org.reactivestreams.Publisher
import scala.collection.immutable
import scala.util.control.NoStackTrace

abstract class TwoStreamsSetup extends AkkaSpec {

  val settings = ActorFlowMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = ActorFlowMaterializer(settings)

  val TestException = new RuntimeException("test") with NoStackTrace

  type Outputs

  abstract class Fixture(b: FlowGraph.Builder) {
    def left: Inlet[Int]
    def right: Inlet[Int]
    def out: Outlet[Outputs]
  }

  def fixture(b: FlowGraph.Builder): Fixture

  def setup(p1: Publisher[Int], p2: Publisher[Int]) = {
    val subscriber = StreamTestKit.SubscriberProbe[Outputs]()
    FlowGraph.closed() { implicit b ⇒
      import FlowGraph.Implicits._
      val f = fixture(b)

      Source(p1) ~> f.left
      Source(p2) ~> f.right
      f.out ~> Sink(subscriber)

    }.run()

    subscriber
  }

  def failedPublisher[T]: Publisher[T] = StreamTestKit.errorPublisher[T](TestException)

  def completedPublisher[T]: Publisher[T] = StreamTestKit.emptyPublisher[T]

  def nonemptyPublisher[T](elems: immutable.Iterable[T]): Publisher[T] = Source(elems).runWith(Sink.publisher())

  def soonToFailPublisher[T]: Publisher[T] = StreamTestKit.lazyErrorPublisher[T](TestException)

  def soonToCompletePublisher[T]: Publisher[T] = StreamTestKit.lazyEmptyPublisher[T]

  def commonTests() = {
    "work with two immediately completed publishers" in {
      val subscriber = setup(completedPublisher, completedPublisher)
      subscriber.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "work with two delayed completed publishers" in {
      val subscriber = setup(soonToCompletePublisher, soonToCompletePublisher)
      subscriber.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "work with one immediately completed and one delayed completed publisher" in {
      val subscriber = setup(completedPublisher, soonToCompletePublisher)
      subscriber.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "work with two immediately failed publishers" in {
      val subscriber = setup(failedPublisher, failedPublisher)
      subscriber.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    "work with two delayed failed publishers" in {
      val subscriber = setup(soonToFailPublisher, soonToFailPublisher)
      subscriber.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    // Warning: The two test cases below are somewhat implementation specific and might fail if the implementation
    // is changed. They are here to be an early warning though.
    "work with one immediately failed and one delayed failed publisher (case 1)" in {
      val subscriber = setup(soonToFailPublisher, failedPublisher)
      subscriber.expectErrorOrSubscriptionFollowedByError(TestException)
    }

    "work with one immediately failed and one delayed failed publisher (case 2)" in {
      val subscriber = setup(failedPublisher, soonToFailPublisher)
      subscriber.expectErrorOrSubscriptionFollowedByError(TestException)
    }
  }

}