/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.actor.{ ActorRef, DeathPactException, PoisonPill, Terminated }
import akka.event.Logging
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream._
import akka.testkit.{ TestActors, TestProbe }

import scala.concurrent.duration._

class FlowDeathPactSpec extends StreamSpec with ScriptedTest {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val materializer = ActorMaterializer(settings)

  "A FlowDeathPact" must {

    "complete when contrActor terminates" in {
      val devil = system.actorOf(TestActors.echoActorProps)

      val deathPact: FlowDeathPact[String] = FlowDeathPact.completeStage(devil)
      val probe = Source.maybe[String].via(deathPact).runWith(TestSink.probe)

      probe.expectSubscription()
      probe.expectNoMsg(100.millis)

      devil ! PoisonPill
      probe.expectComplete()
    }

    "fail with DeathPactException when contrActor terminates" in {
      val devil = system.actorOf(TestActors.echoActorProps)

      val deathPact: FlowDeathPact[String] = FlowDeathPact.failStage(devil)
      val probe = Source.maybe[String].via(deathPact).runWith(TestSink.probe)

      probe.expectSubscription()

      devil ! PoisonPill
      val ex = probe.expectError()
      ex.getClass should ===(classOf[DeathPactException])
    }

    "inform contrActor that FlowDeathPact was Signed" in {
      val contrActor = TestProbe()

      val deathPact: FlowDeathPact[String] = FlowDeathPact.failStage(contrActor.ref)
      val probe = Source.empty.via(deathPact).runWith(TestSink.probe)

      contrActor.expectMsg(FlowDeathPact.Signed)

      probe.expectSubscriptionAndComplete()
    }

    "inform contrActor that when stream Failed" in {
      val contrActor = TestProbe()

      val deathPact: FlowDeathPact[String] = FlowDeathPact.failStage(contrActor.ref)
      val cause = new Exception("BOOM!")
      val probe = Source.failed(cause).via(deathPact).runWith(TestSink.probe)

      contrActor.expectMsg(FlowDeathPact.Signed)
      val failed = contrActor.expectMsgType[FlowDeathPact.Failed]
      failed.cause should ===(cause)

      probe.expectSubscription().request(1)
      probe.expectError(cause)
    }

    "inform contrActor that when stream Completed" in {
      val contrActor = TestProbe()

      val deathPact: FlowDeathPact[String] = FlowDeathPact.failStage(contrActor.ref)
      val probe = Source.empty.via(deathPact).runWith(TestSink.probe)

      contrActor.expectMsg(FlowDeathPact.Signed)
      contrActor.expectMsg(FlowDeathPact.Completed)
      info("Stage sender: " + contrActor.lastSender)
      probe.expectSubscriptionAndComplete()
    }

  }

}

/** INTERNAL API */
/*private[akka]*/ final class FlowDeathPact[T](ref: ActorRef, onTerminated: GraphStageLogic ⇒ Unit)
  extends GraphStage[FlowShape[T, T]] {

  val in = Inlet[T]("FlowDeathPact.in")
  val out = Outlet[T]("FlowDeathPact.out")
  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) 
    with InHandler with OutHandler {

    override def preStart(): Unit = {
      getStageActor({
        case (_, Terminated(`ref`)) ⇒ onTerminated(this)
        case _                      ⇒ // ignore...
      })
      stageActor.watch(ref)
      ref.tell(FlowDeathPact.Signed, stageActor.ref)
    }

    override def onPush(): Unit = push(out, grab(in))
    override def onPull(): Unit = pull(in)

    override def onUpstreamFinish(): Unit = {
      ref.tell(FlowDeathPact.Completed, stageActor.ref)
      super.onUpstreamFinish()
    }
    override def onUpstreamFailure(ex: Throwable): Unit = {
      ref.tell(FlowDeathPact.Failed(ex), stageActor.ref)
      super.onUpstreamFailure(ex)
    }

    setHandlers(in, out, this)
  }
}

object FlowDeathPact {
  sealed trait FlowDeathPactMessage extends Serializable
  final case object Signed extends FlowDeathPactMessage
  final case object Completed extends FlowDeathPactMessage
  final case class Failed(cause: Throwable) extends FlowDeathPactMessage

  def completeStage[T](ref: ActorRef): FlowDeathPact[T] =
    new FlowDeathPact(ref, _.completeStage())

  def failStage[T](ref: ActorRef): FlowDeathPact[T] =
    new FlowDeathPact(ref, _.failStage(DeathPactException(ref)))
}
