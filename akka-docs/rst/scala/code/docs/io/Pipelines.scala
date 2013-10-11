/**
 * Copyright (C) 2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io

import docs.io.japi.LengthFieldFrame;
import akka.testkit.{ AkkaSpec, EventFilter }
import akka.io._
import akka.util._
import akka.actor.{ Actor, ActorRef, Props, PoisonPill }
import scala.util.Success
import scala.util.Try
import scala.concurrent.duration._

class PipelinesDocSpec extends AkkaSpec {

  //#data
  case class Person(first: String, last: String)
  case class HappinessCurve(points: IndexedSeq[Double])
  case class Message(persons: Seq[Person], stats: HappinessCurve)
  //#data

  //#format
  /**
   * This trait is used to formualate a requirement for the pipeline context.
   * In this example it is used to configure the byte order to be used.
   */
  trait HasByteOrder extends PipelineContext {
    def byteOrder: java.nio.ByteOrder
  }

  class MessageStage extends SymmetricPipelineStage[HasByteOrder, Message, ByteString] {

    override def apply(ctx: HasByteOrder) = new SymmetricPipePair[Message, ByteString] {

      implicit val byteOrder = ctx.byteOrder

      /**
       * Append a length-prefixed UTF-8 encoded string to the ByteStringBuilder.
       */
      def putString(builder: ByteStringBuilder, str: String): Unit = {
        val bs = ByteString(str, "UTF-8")
        builder putInt bs.length
        builder ++= bs
      }

      override val commandPipeline = { msg: Message ⇒
        val bs = ByteString.newBuilder

        // first store the persons
        bs putInt msg.persons.size
        msg.persons foreach { p ⇒
          putString(bs, p.first)
          putString(bs, p.last)
        }

        // then store the doubles
        bs putInt msg.stats.points.length
        bs putDoubles (msg.stats.points.toArray)

        // and return the result as a command
        ctx.singleCommand(bs.result)
      }

      //#decoding-omitted
      //#decoding
      def getString(iter: ByteIterator): String = {
        val length = iter.getInt
        val bytes = new Array[Byte](length)
        iter getBytes bytes
        ByteString(bytes).utf8String
      }

      override val eventPipeline = { bs: ByteString ⇒
        val iter = bs.iterator

        val personLength = iter.getInt
        val persons =
          (1 to personLength) map (_ ⇒ Person(getString(iter), getString(iter)))

        val curveLength = iter.getInt
        val curve = new Array[Double](curveLength)
        iter getDoubles curve

        // verify that this was all; could be left out to allow future extensions
        assert(iter.isEmpty)

        ctx.singleEvent(Message(persons, HappinessCurve(curve)))
      }
      //#decoding

      //#mgmt-ticks
      var lastTick = Duration.Zero

      override val managementPort: Mgmt = {
        case TickGenerator.Tick(timestamp) ⇒
          //#omitted
          testActor ! TickGenerator.Tick(timestamp)
          import java.lang.String.{ valueOf ⇒ println }
          //#omitted
          println(s"time since last tick: ${timestamp - lastTick}")
          lastTick = timestamp
          Nil
      }
      //#mgmt-ticks
      //#decoding-omitted
    }
  }
  //#format

  "A MessageStage" must {

    //#message
    val msg =
      Message(
        Seq(
          Person("Alice", "Gibbons"),
          Person("Bob", "Sparsely")),
        HappinessCurve(Array(1.0, 3.0, 5.0)))
    //#message

    //#byteorder
    val ctx = new HasByteOrder {
      def byteOrder = java.nio.ByteOrder.BIG_ENDIAN
    }
    //#byteorder

    "correctly encode and decode" in {
      //#build-pipeline
      val stages =
        new MessageStage >>
          new LengthFieldFrame(10000)

      // using the extractor for the returned case class here
      val PipelinePorts(cmd, evt, mgmt) =
        PipelineFactory.buildFunctionTriple(ctx, stages)

      val encoded: (Iterable[Message], Iterable[ByteString]) = cmd(msg)
      //#build-pipeline
      encoded._1 must have size 0
      encoded._2 must have size 1

      evt(encoded._2.head)._1 must be === Seq(msg)
    }

    "demonstrate Injector/Sink" in {
      val commandHandler = testActor
      val eventHandler = testActor

      //#build-sink
      val stages =
        new MessageStage >>
          new LengthFieldFrame(10000)

      val injector = PipelineFactory.buildWithSinkFunctions(ctx, stages)(
        commandHandler ! _, // will receive messages of type Try[ByteString]
        eventHandler ! _ // will receive messages of type Try[Message]
        )

      injector.injectCommand(msg)
      //#build-sink
      val encoded = expectMsgType[Success[ByteString]].get

      injector.injectEvent(encoded)
      expectMsgType[Try[Message]].get must be === msg
    }

    "demonstrate management port and context" in {
      import TickGenerator.Tick
      val proc = system.actorOf(Props(classOf[P], this, testActor, testActor), "processor")
      expectMsgType[Tick]
      proc ! msg
      val encoded = expectMsgType[ByteString]
      proc ! encoded
      val decoded = expectMsgType[Message]
      decoded must be === msg

      within(1.5.seconds, 3.seconds) {
        expectMsgType[Tick]
        expectMsgType[Tick]
      }
      EventFilter[RuntimeException]("FAIL!", occurrences = 1) intercept {
        proc ! "fail!"
      }
      within(1.5.seconds, 3.seconds) {
        expectMsgType[Tick]
        expectMsgType[Tick]
        proc ! PoisonPill
        expectNoMsg
      }
    }

  }

  //#actor
  class Processor(cmds: ActorRef, evts: ActorRef) extends Actor {

    val ctx = new HasActorContext with HasByteOrder {
      def getContext = Processor.this.context
      def byteOrder = java.nio.ByteOrder.BIG_ENDIAN
    }

    val pipeline = PipelineFactory.buildWithSinkFunctions(ctx,
      new TickGenerator(1000.millis) >>
        new MessageStage >>
        new LengthFieldFrame(10000) //
        )(
        // failure in the pipeline will fail this actor
        cmd ⇒ cmds ! cmd.get,
        evt ⇒ evts ! evt.get)

    def receive = {
      case m: Message               ⇒ pipeline.injectCommand(m)
      case b: ByteString            ⇒ pipeline.injectEvent(b)
      case t: TickGenerator.Trigger ⇒ pipeline.managementCommand(t)
    }
  }
  //#actor

  class P(cmds: ActorRef, evts: ActorRef) extends Processor(cmds, evts) {
    override def receive = ({
      case "fail!" ⇒ throw new RuntimeException("FAIL!")
    }: Receive) orElse super.receive
  }

}