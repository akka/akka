package akka.stream.testkit

import akka.stream.{ ActorMaterializer, ActorMaterializerSettings, Inlet, Outlet }
import akka.stream.scaladsl._
import org.reactivestreams.Publisher
import scala.collection.immutable
import scala.util.control.NoStackTrace
import akka.stream.testkit.Utils._

abstract class TwoStreamsSetup extends AkkaSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)

  implicit val materializer = ActorMaterializer(settings)

  val TestException = new RuntimeException("test") with NoStackTrace

  type Outputs

  abstract class Fixture(b: FlowGraph.Builder[_]) {
    def left: Inlet[Int]
    def right: Inlet[Int]
    def out: Outlet[Outputs]
  }

  def fixture(b: FlowGraph.Builder[_]): Fixture

  def setup(p1: Publisher[Int], p2: Publisher[Int]) = {
    val subscriber = TestSubscriber.probe[Outputs]()
    FlowGraph.closed() { implicit b ⇒
      import FlowGraph.Implicits._
      val f = fixture(b)

      Source(p1) ~> f.left
      Source(p2) ~> f.right
      f.out ~> Sink(subscriber)

    }.run()

    subscriber
  }

  def failedPublisher[T]: Publisher[T] = TestPublisher.error[T](TestException)

  def completedPublisher[T]: Publisher[T] = TestPublisher.empty[T]

  def nonemptyPublisher[T](elems: immutable.Iterable[T]): Publisher[T] = Source(elems).runWith(Sink.publisher)

  def soonToFailPublisher[T]: Publisher[T] = TestPublisher.lazyError[T](TestException)

  def soonToCompletePublisher[T]: Publisher[T] = TestPublisher.lazyEmpty[T]

  def commonTests() = {
    "work with two immediately completed publishers" in assertAllStagesStopped {
      val subscriber = setup(completedPublisher, completedPublisher)
      subscriber.expectSubscriptionAndComplete()
    }

    "work with two delayed completed publishers" in assertAllStagesStopped {
      val subscriber = setup(soonToCompletePublisher, soonToCompletePublisher)
      subscriber.expectSubscriptionAndComplete()
    }

    "work with one immediately completed and one delayed completed publisher" in assertAllStagesStopped {
      val subscriber = setup(completedPublisher, soonToCompletePublisher)
      subscriber.expectSubscriptionAndComplete()
    }

    "work with two immediately failed publishers" in assertAllStagesStopped {
      val subscriber = setup(failedPublisher, failedPublisher)
      subscriber.expectSubscriptionAndError(TestException)
    }

    "work with two delayed failed publishers" in assertAllStagesStopped {
      val subscriber = setup(soonToFailPublisher, soonToFailPublisher)
      subscriber.expectSubscriptionAndError(TestException)
    }

    // Warning: The two test cases below are somewhat implementation specific and might fail if the implementation
    // is changed. They are here to be an early warning though.
    "work with one immediately failed and one delayed failed publisher (case 1)" in assertAllStagesStopped {
      val subscriber = setup(soonToFailPublisher, failedPublisher)
      subscriber.expectSubscriptionAndError(TestException)
    }

    "work with one immediately failed and one delayed failed publisher (case 2)" in assertAllStagesStopped {
      val subscriber = setup(failedPublisher, soonToFailPublisher)
      subscriber.expectSubscriptionAndError(TestException)
    }
  }

}
