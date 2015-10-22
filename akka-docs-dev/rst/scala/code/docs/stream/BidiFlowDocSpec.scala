/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.stream.testkit.AkkaSpec
import akka.stream.scaladsl._
import akka.stream._
import akka.util.ByteString
import java.nio.ByteOrder
import akka.stream.stage._
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.Await
import org.scalactic.ConversionCheckedTripleEquals

object BidiFlowDocSpec {
  //#codec
  trait Message
  case class Ping(id: Int) extends Message
  case class Pong(id: Int) extends Message

  //#codec-impl
  def toBytes(msg: Message): ByteString = {
    //#implementation-details-elided
    implicit val order = ByteOrder.LITTLE_ENDIAN
    msg match {
      case Ping(id) => ByteString.newBuilder.putByte(1).putInt(id).result()
      case Pong(id) => ByteString.newBuilder.putByte(2).putInt(id).result()
    }
    //#implementation-details-elided
  }

  def fromBytes(bytes: ByteString): Message = {
    //#implementation-details-elided
    implicit val order = ByteOrder.LITTLE_ENDIAN
    val it = bytes.iterator
    it.getByte match {
      case 1     => Ping(it.getInt)
      case 2     => Pong(it.getInt)
      case other => throw new RuntimeException(s"parse error: expected 1|2 got $other")
    }
    //#implementation-details-elided
  }
  //#codec-impl

  val codecVerbose = BidiFlow.fromGraph(FlowGraph.create() { b =>
    // construct and add the top flow, going outbound
    val outbound = b.add(Flow[Message].map(toBytes))
    // construct and add the bottom flow, going inbound
    val inbound = b.add(Flow[ByteString].map(fromBytes))
    // fuse them together into a BidiShape
    BidiShape.fromFlows(outbound, inbound)
  })

  // this is the same as the above
  val codec = BidiFlow.fromFunctions(toBytes _, fromBytes _)
  //#codec

  //#framing
  val framing = BidiFlow.fromGraph(FlowGraph.create() { b =>
    implicit val order = ByteOrder.LITTLE_ENDIAN

    def addLengthHeader(bytes: ByteString) = {
      val len = bytes.length
      ByteString.newBuilder.putInt(len).append(bytes).result()
    }

    class FrameParser extends PushPullStage[ByteString, ByteString] {
      // this holds the received but not yet parsed bytes
      var stash = ByteString.empty
      // this holds the current message length or -1 if at a boundary
      var needed = -1

      override def onPush(bytes: ByteString, ctx: Context[ByteString]) = {
        stash ++= bytes
        run(ctx)
      }
      override def onPull(ctx: Context[ByteString]) = run(ctx)
      override def onUpstreamFinish(ctx: Context[ByteString]) =
        if (stash.isEmpty) ctx.finish()
        else ctx.absorbTermination() // we still have bytes to emit

      private def run(ctx: Context[ByteString]): SyncDirective =
        if (needed == -1) {
          // are we at a boundary? then figure out next length
          if (stash.length < 4) pullOrFinish(ctx)
          else {
            needed = stash.iterator.getInt
            stash = stash.drop(4)
            run(ctx) // cycle back to possibly already emit the next chunk
          }
        } else if (stash.length < needed) {
          // we are in the middle of a message, need more bytes
          pullOrFinish(ctx)
        } else {
          // we have enough to emit at least one message, so do it
          val emit = stash.take(needed)
          stash = stash.drop(needed)
          needed = -1
          ctx.push(emit)
        }

      /*
       * After having called absorbTermination() we cannot pull any more, so if we need
       * more data we will just have to give up.
       */
      private def pullOrFinish(ctx: Context[ByteString]) =
        if (ctx.isFinishing) ctx.finish()
        else ctx.pull()
    }

    val outbound = b.add(Flow[ByteString].map(addLengthHeader))
    val inbound = b.add(Flow[ByteString].transform(() => new FrameParser))
    BidiShape.fromFlows(outbound, inbound)
  })
  //#framing

  val chopUp = BidiFlow.fromGraph(FlowGraph.create() { b =>
    val f = Flow[ByteString].mapConcat(_.map(ByteString(_)))
    BidiShape.fromFlows(b.add(f), b.add(f))
  })

  val accumulate = BidiFlow.fromGraph(FlowGraph.create() { b =>
    val f = Flow[ByteString].grouped(1000).map(_.fold(ByteString.empty)(_ ++ _))
    BidiShape.fromFlows(b.add(f), b.add(f))
  })
}

class BidiFlowDocSpec extends AkkaSpec with ConversionCheckedTripleEquals {
  import BidiFlowDocSpec._

  implicit val mat = ActorMaterializer()

  "A BidiFlow" must {

    "compose" in {
      //#compose
      /* construct protocol stack
       *         +------------------------------------+
       *         | stack                              |
       *         |                                    |
       *         |  +-------+            +---------+  |
       *    ~>   O~~o       |     ~>     |         o~~O    ~>
       * Message |  | codec | ByteString | framing |  | ByteString
       *    <~   O~~o       |     <~     |         o~~O    <~
       *         |  +-------+            +---------+  |
       *         +------------------------------------+
       */
      val stack = codec.atop(framing)

      // test it by plugging it into its own inverse and closing the right end
      val pingpong = Flow[Message].collect { case Ping(id) => Pong(id) }
      val flow = stack.atop(stack.reversed).join(pingpong)
      val result = Source((0 to 9).map(Ping)).via(flow).grouped(20).runWith(Sink.head)
      Await.result(result, 1.second) should ===((0 to 9).map(Pong))
      //#compose
    }

    "work when chopped up" in {
      val stack = codec.atop(framing)
      val flow = stack.atop(chopUp).atop(stack.reversed).join(Flow[Message].map { case Ping(id) => Pong(id) })
      val f = Source((0 to 9).map(Ping)).via(flow).grouped(20).runWith(Sink.head)
      Await.result(f, 1.second) should ===((0 to 9).map(Pong))
    }

    "work when accumulated" in {
      val stack = codec.atop(framing)
      val flow = stack.atop(accumulate).atop(stack.reversed).join(Flow[Message].map { case Ping(id) => Pong(id) })
      val f = Source((0 to 9).map(Ping)).via(flow).grouped(20).runWith(Sink.head)
      Await.result(f, 1.second) should ===((0 to 9).map(Pong))
    }

  }

}
