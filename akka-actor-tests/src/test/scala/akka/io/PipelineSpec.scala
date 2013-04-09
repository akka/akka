/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import akka.testkit.AkkaSpec
import akka.util.ByteString
import scala.annotation.tailrec
import java.nio.ByteOrder
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.Try
import scala.util.Success

class PipelineSpec extends AkkaSpec {

  trait Level1
  trait Level2
  trait Level3
  trait Level4

  trait LevelFactory[Lvl] {
    def msgA: Lvl
    def msgB: Lvl
  }

  implicit object Level1 extends LevelFactory[Level1] {
    object msgA extends Level1 { override def toString = "Lvl1msgA" }
    object msgB extends Level1 { override def toString = "Lvl1msgB" }
  }

  implicit object Level2 extends LevelFactory[Level2] {
    object msgA extends Level2 { override def toString = "Lvl2msgA" }
    object msgB extends Level2 { override def toString = "Lvl2msgB" }
  }

  implicit object Level3 extends LevelFactory[Level3] {
    object msgA extends Level3 { override def toString = "Lvl3msgA" }
    object msgB extends Level3 { override def toString = "Lvl3msgB" }
  }

  implicit object Level4 extends LevelFactory[Level4] {
    object msgA extends Level4 { override def toString = "Lvl4msgA" }
    object msgB extends Level4 { override def toString = "Lvl4msgB" }
  }

  val ctx = new PipelineContext {}

  "A Pipeline" must {

    "be correctly evaluated if single stage" in {
      val PipelinePorts(cmd, evt, _) =
        PipelineFactory.buildFunctionTriple(ctx, stage[Level2, Level1](1, 0, false))
      cmd(Level2.msgA) must be(Nil -> Seq(Level1.msgA))
      evt(Level1.msgA) must be(Seq(Level2.msgA) -> Nil)
      cmd(Level2.msgB) must be(Nil -> Seq(Level1.msgB))
      evt(Level1.msgB) must be(Seq(Level2.msgB) -> Nil)
    }

    "be correctly evaluated when two combined" in {
      val stage1 = stage[Level3, Level2](1, 0, false)
      val stage2 = stage[Level2, Level1](1, 0, false)
      val PipelinePorts(cmd, evt, _) = PipelineFactory.buildFunctionTriple(ctx, stage1 >> stage2)
      cmd(Level3.msgA) must be(Nil -> Seq(Level1.msgA))
      evt(Level1.msgA) must be(Seq(Level3.msgA) -> Nil)
      cmd(Level3.msgB) must be(Nil -> Seq(Level1.msgB))
      evt(Level1.msgB) must be(Seq(Level3.msgB) -> Nil)
    }

    "be correctly evaluated when three combined" in {
      val stage1 = stage[Level4, Level3](1, 0, false)
      val stage2 = stage[Level3, Level2](2, 0, false)
      val stage3 = stage[Level2, Level1](1, 0, false)
      val PipelinePorts(cmd, evt, _) = PipelineFactory.buildFunctionTriple(ctx, stage1 >> stage2 >> stage3)
      cmd(Level4.msgA) must be(Nil -> Seq(Level1.msgA, Level1.msgA))
      evt(Level1.msgA) must be(Seq(Level4.msgA, Level4.msgA) -> Nil)
      cmd(Level4.msgB) must be(Nil -> Seq(Level1.msgB, Level1.msgB))
      evt(Level1.msgB) must be(Seq(Level4.msgB, Level4.msgB) -> Nil)
    }

    "be correctly evaluated with back-scatter" in {
      val stage1 = stage[Level4, Level3](1, 0, true)
      val stage2 = stage[Level3, Level2](1, 1, true)
      val stage3 = stage[Level2, Level1](1, 0, false)
      val PipelinePorts(cmd, evt, _) = PipelineFactory.buildFunctionTriple(ctx, stage1 >> stage2 >> stage3)
      cmd(Level4.msgA) must be(Seq(Level4.msgB) -> Seq(Level1.msgA))
      evt(Level1.msgA) must be(Seq(Level4.msgA) -> Seq(Level1.msgB))
    }

    "handle management commands" in {
      val stage1 = stage[Level4, Level3](1, 0, true, { case "doit" ⇒ Seq(Left(Level4.msgA), Right(Level3.msgA)) })
      val stage2 = stage[Level3, Level2](2, 0, true, { case "doit" ⇒ Seq(Left(Level3.msgA), Right(Level2.msgA)) })
      val stage3 = stage[Level2, Level1](1, 0, true, { case "doit" ⇒ Seq(Left(Level2.msgA), Right(Level1.msgA)) })
      val PipelinePorts(cmd, evt, mgmt) = PipelineFactory.buildFunctionTriple(ctx, stage1 >> stage2 >> stage3)
      mgmt(42: java.lang.Integer) must be(Seq() -> Seq())
      val (events, commands) = mgmt("doit")
      events must have size 4
      events count (_ == Level4.msgA) must be === 3
      events count (_ == Level4.msgB) must be === 1
      commands must have size 4
      commands count (_ == Level1.msgA) must be === 3
      commands count (_ == Level1.msgB) must be === 1
    }

  }

  def stage[Above: LevelFactory, Below: LevelFactory](forward: Int, backward: Int, invert: Boolean,
                                                      mgmt: SymmetricPipePair[Above, Below]#Mgmt = PartialFunction.empty) =
    new SymmetricPipelineStage[PipelineContext, Above, Below] {
      override def apply(ctx: PipelineContext) = {
        val above = implicitly[LevelFactory[Above]]
        val below = implicitly[LevelFactory[Below]]
        PipePairFactory(
          { a ⇒
            val msgA = a == above.msgA
            val msgAbove = if (invert ^ msgA) above.msgA else above.msgB
            val msgBelow = if (invert ^ msgA) below.msgA else below.msgB
            (for (_ ← 1 to forward) yield Right(msgBelow)) ++ (for (_ ← 1 to backward) yield Left(msgAbove))
          },
          { b ⇒
            val msgA = b == below.msgA
            val msgAbove = if (invert ^ msgA) above.msgA else above.msgB
            val msgBelow = if (invert ^ msgA) below.msgA else below.msgB
            (for (_ ← 1 to forward) yield Left(msgAbove)) ++ (for (_ ← 1 to backward) yield Right(msgBelow))
          },
          mgmt)
      }
    }

}

object PipelineBench extends App {

  val frame = new LengthFieldFrame(32000)
  val frames = frame >> frame >> frame >> frame

  val ctx = new PipelineContext {}
  // this way of creating a pipeline is not user API
  val pipe = frames(ctx)

  val hello = ByteString("hello")
  // ctx.dealias is only necessary because this is a “raw” pipe, not user API
  val bytes = ctx.dealias(pipe.commandPipeline(ByteString("hello"))).head.fold(identity, identity).compact
  println(bytes)
  println(pipe.eventPipeline(bytes))

  class Bytes {
    var pos = 0
    var emitted = 0
    def get(): ByteString = {
      val r = ThreadLocalRandom.current()
      val l = r.nextInt(2 * bytes.length)
      @tailrec def rec(left: Int, acc: ByteString): ByteString = {
        if (pos + left <= bytes.length) {
          val result = acc ++ bytes.slice(pos, pos + left)
          pos = (pos + left) % bytes.length
          result
        } else {
          val oldpos = pos
          pos = 0
          rec(left - bytes.length + oldpos, acc ++ bytes.slice(oldpos, bytes.length))
        }
      }
      emitted += l
      rec(l, ByteString.empty)
    }
  }

  println("warming up")

  val bpp = new Bytes

  {
    println(" ... PipePair")
    val y = for (_ ← 1 to 500000; x ← ctx.dealias(pipe.eventPipeline(bpp.get()))) yield x
    assert(y forall { case Left(b) ⇒ b == ByteString("hello"); case _ ⇒ false })
    assert(y.size == bpp.emitted / bytes.length)
  }

  val PipelinePorts(_, evt, _) = PipelineFactory.buildFunctionTriple(ctx, frames)
  val bft = new Bytes

  {
    println(" ... FunctionTriple")
    val y = for (_ ← 1 to 500000; x ← evt(bft.get())._1) yield x
    assert(y forall (_ == ByteString("hello")))
    assert(y.size == bft.emitted / bytes.length)
  }

  var injected = 0
  val inj = PipelineFactory.buildWithSinkFunctions(ctx, frames)(_ ⇒ Nil, { case Success(bs) if bs == hello ⇒ injected += 1 })
  val bij = new Bytes

  {
    println(" ... Injector")
    for (_ ← 1 to 500000) inj.injectEvent(bij.get())
    assert(injected == bij.emitted / bytes.length)
  }

  val N = 1000000

  {
    val start = System.nanoTime
    val y = for (_ ← 1 to N; x ← ctx.dealias(pipe.eventPipeline(bpp.get()))) yield x
    val time = System.nanoTime - start
    println(s"PipePair: 1 iteration took ${time / N}ns (${y.size})")
  }

  {
    val start = System.nanoTime
    val y = for (_ ← 1 to N; x ← evt(bft.get())._1) yield x
    val time = System.nanoTime - start
    println(s"FunctionTriple: 1 iteration took ${time / N}ns (${y.size})")
  }

  {
    injected = 0
    val start = System.nanoTime
    for (_ ← 1 to N) inj.injectEvent(bij.get())
    val time = System.nanoTime - start
    println(s"Injector: 1 iteration took ${time / N}ns ($injected)")
  }

}
