/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import akka.stream.impl.ConstantFun
import akka.stream.stage._
import akka.testkit.AkkaSpec
import akka.testkit.EventFilter

import akka.stream.Supervision

class InterpreterSpec extends AkkaSpec with GraphInterpreterSpecKit {
  import Supervision.stoppingDecider

  /*
   * These tests were written for the previous version of the interpreter, the so called OneBoundedInterpreter.
   * These stages are now properly emulated by the GraphInterpreter and many of the edge cases were relevant to
   * the execution model of the old one. Still, these tests are very valuable, so please do not remove.
   */

  val takeOne = Take(1)
  val takeTwo = Take(2)

  "Interpreter" must {

    "implement map correctly" in new OneBoundedSetup[Int](Seq(Map((x: Int) ⇒ x + 1, stoppingDecider))) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(2)))

      upstream.onComplete()
      lastEvents() should be(Set(OnComplete))
    }

    "implement chain of maps correctly" in new OneBoundedSetup[Int](Seq(
      Map((x: Int) ⇒ x + 1, stoppingDecider),
      Map((x: Int) ⇒ x * 2, stoppingDecider),
      Map((x: Int) ⇒ x + 1, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(3)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(5)))

      downstream.cancel()
      lastEvents() should be(Set(Cancel))
    }

    "work with only boundary ops" in new OneBoundedSetup[Int](Seq.empty) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(0)))

      upstream.onComplete()
      lastEvents() should be(Set(OnComplete))
    }

    "implement one-to-many many-to-one chain correctly" in new OneBoundedSetup[Int](Seq(
      Doubler(),
      Filter((x: Int) ⇒ x != 0, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onComplete()
      lastEvents() should be(Set(OnComplete))
    }

    "implement many-to-one one-to-many chain correctly" in new OneBoundedSetup[Int](Seq(
      Filter((x: Int) ⇒ x != 0, stoppingDecider),
      Doubler())) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      downstream.cancel()
      lastEvents() should be(Set(Cancel))
    }

    "implement take" in new OneBoundedSetup[Int](takeTwo) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(0)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(1), Cancel, OnComplete))
    }

    "implement take inside a chain" in new OneBoundedSetup[Int](
      Filter((x: Int) ⇒ x != 0, stoppingDecider).toGS,
      takeTwo,
      Map((x: Int) ⇒ x + 1, stoppingDecider).toGS) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(2)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(Cancel, OnComplete, OnNext(3)))
    }

    "implement fold" in new OneBoundedSetup[Int](Seq(Fold(0, (agg: Int, x: Int) ⇒ agg + x, stoppingDecider))) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      upstream.onComplete()
      lastEvents() should be(Set(OnNext(3), OnComplete))
    }

    "implement fold with proper cancel" in new OneBoundedSetup[Int](Seq(Fold(0, (agg: Int, x: Int) ⇒ agg + x, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      downstream.cancel()
      lastEvents() should be(Set(Cancel))
    }

    "work if fold completes while not in a push position" in new OneBoundedSetup[Int](Seq(Fold(0, (agg: Int, x: Int) ⇒ agg + x, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      upstream.onComplete()
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(OnComplete, OnNext(0)))
    }

    "implement grouped" in new OneBoundedSetup[Int](Seq(Grouped(3))) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(Vector(0, 1, 2))))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(3)
      lastEvents() should be(Set(RequestOne))

      upstream.onComplete()
      lastEvents() should be(Set(OnNext(Vector(3)), OnComplete))
    }

    "implement batch (conflate)" in new OneBoundedSetup[Int](Batch(
      1L,
      ConstantFun.zeroLong,
      (in: Int) ⇒ in,
      (agg: Int, x: Int) ⇒ agg + x)) {

      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set.empty)

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(0), RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(3)))

      downstream.requestOne()
      lastEvents() should be(Set.empty)

      upstream.onNext(4)
      lastEvents() should be(Set(OnNext(4), RequestOne))

      downstream.cancel()
      lastEvents() should be(Set(Cancel))
    }

    "implement expand" in new OneBoundedSetup[Int](new Expand(Iterator.continually(_: Int))) {

      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne, OnNext(0)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(0)))

      upstream.onNext(1)
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne, OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))

      upstream.onComplete()
      lastEvents() should be(Set(OnComplete))
    }

    "work with batch-batch (conflate-conflate)" in new OneBoundedSetup[Int](
      Batch(
        1L,
        ConstantFun.zeroLong,
        (in: Int) ⇒ in,
        (agg: Int, x: Int) ⇒ agg + x),
      Batch(
        1L,
        ConstantFun.zeroLong,
        (in: Int) ⇒ in,
        (agg: Int, x: Int) ⇒ agg + x)) {

      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set.empty)

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(0), RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(3)))

      downstream.requestOne()
      lastEvents() should be(Set.empty)

      upstream.onNext(4)
      lastEvents() should be(Set(OnNext(4), RequestOne))

      downstream.cancel()
      lastEvents() should be(Set(Cancel))

    }

    "work with expand-expand" in new OneBoundedSetup[Int](
      new Expand(Iterator.from),
      new Expand(Iterator.from)) {

      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(0)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))

      upstream.onNext(10)
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne, OnNext(2))) // One element is still in the pipeline

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(10)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(11)))

      upstream.onComplete()
      downstream.requestOne()
      // This is correct! If you don't believe, run the interpreter with Debug on
      lastEvents() should be(Set(OnComplete, OnNext(12)))
    }

    "implement batch-expand (conflate-expand)" in new OneBoundedSetup[Int](
      Batch(
        1L,
        ConstantFun.zeroLong,
        (in: Int) ⇒ in,
        (agg: Int, x: Int) ⇒ agg + x),
      new Expand(Iterator.continually(_: Int))) {

      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(0)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(2)))

      downstream.cancel()
      lastEvents() should be(Set(Cancel))
    }

    "implement doubler-conflate (doubler-batch)" in new OneBoundedSetup[Int](
      Doubler().toGS,
      Batch(
        1L,
        ConstantFun.zeroLong,
        (in: Int) ⇒ in,
        (agg: Int, x: Int) ⇒ agg + x)) {
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(6)))

    }

    // Note, the new interpreter has no jumpback table, still did not want to remove the test
    "work with jumpback table and completed elements" in new OneBoundedSetup[Int](Seq(
      Map((x: Int) ⇒ x, stoppingDecider),
      Map((x: Int) ⇒ x, stoppingDecider),
      KeepGoing(),
      Map((x: Int) ⇒ x, stoppingDecider),
      Map((x: Int) ⇒ x, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(2)))

      upstream.onComplete()
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(2)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(2)))

    }

    "work with pushAndFinish if upstream completes with pushAndFinish" in new OneBoundedSetup[Int](Seq(
      new PushFinishStage)) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNextAndComplete(0)
      lastEvents() should be(Set(OnNext(0), OnComplete))
    }

    "work with pushAndFinish if indirect upstream completes with pushAndFinish" in new OneBoundedSetup[Int](Seq(
      Map((x: Any) ⇒ x, stoppingDecider),
      new PushFinishStage,
      Map((x: Any) ⇒ x, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNextAndComplete(1)
      lastEvents() should be(Set(OnNext(1), OnComplete))
    }

    "work with pushAndFinish if upstream completes with pushAndFinish and downstream immediately pulls" in new OneBoundedSetup[Int](Seq(
      new PushFinishStage,
      Fold(0, (x: Int, y: Int) ⇒ x + y, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNextAndComplete(1)
      lastEvents() should be(Set(OnNext(1), OnComplete))
    }

    "report error if pull is called while op is terminating" in new OneBoundedSetup[Int](Seq(new PushPullStage[Any, Any] {
      override def onPull(ctx: Context[Any]): SyncDirective = ctx.pull()
      override def onPush(elem: Any, ctx: Context[Any]): SyncDirective = ctx.pull()
      override def onUpstreamFinish(ctx: Context[Any]): TerminationDirective = ctx.absorbTermination()
    })) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      EventFilter[IllegalArgumentException](pattern = ".*Cannot pull closed port.*", occurrences = 1).intercept {
        upstream.onComplete()
      }
      val ev = lastEvents()
      ev.nonEmpty should be(true)
      ev.forall {
        case OnError(_: IllegalArgumentException) ⇒ true
        case _                                    ⇒ false
      } should be(true)
    }

    "implement take-take" in new OneBoundedSetup[Int](
      takeOne,
      takeOne) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(OnNext(1), OnComplete, Cancel))

    }

    "implement take-take with pushAndFinish from upstream" in new OneBoundedSetup[Int](
      takeOne,
      takeOne) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNextAndComplete(1)
      lastEvents() should be(Set(OnNext(1), OnComplete))

    }

    class InvalidAbsorbTermination extends PushPullStage[Int, Int] {
      override def onPull(ctx: Context[Int]): SyncDirective = ctx.pull()
      override def onPush(elem: Int, ctx: Context[Int]): SyncDirective = ctx.push(elem)
      override def onDownstreamFinish(ctx: Context[Int]): TerminationDirective = ctx.absorbTermination()
    }

    "not allow absorbTermination from onDownstreamFinish()" in new OneBoundedSetup[Int](Seq(
      new InvalidAbsorbTermination)) {
      lastEvents() should be(Set.empty)

      EventFilter[UnsupportedOperationException]("It is not allowed to call absorbTermination() from onDownstreamFinish.", occurrences = 1).intercept {
        downstream.cancel()
        lastEvents() should be(Set(Cancel))
      }

    }

  }

  private[akka] case class Doubler[T]() extends PushPullStage[T, T] {
    var oneMore: Boolean = false
    var lastElem: T = _

    override def onPush(elem: T, ctx: Context[T]): SyncDirective = {
      lastElem = elem
      oneMore = true
      ctx.push(elem)
    }

    override def onPull(ctx: Context[T]): SyncDirective = {
      if (oneMore) {
        oneMore = false
        ctx.push(lastElem)
      } else ctx.pull()
    }
  }

  private[akka] case class KeepGoing[T]() extends PushPullStage[T, T] {
    var lastElem: T = _

    override def onPush(elem: T, ctx: Context[T]): SyncDirective = {
      lastElem = elem
      ctx.push(elem)
    }

    override def onPull(ctx: Context[T]): SyncDirective = {
      if (ctx.isFinishing) {
        ctx.push(lastElem)
      } else ctx.pull()
    }

    override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = ctx.absorbTermination()
  }

  // This test is related to issue #17351
  private[akka] class PushFinishStage(onPostStop: () ⇒ Unit = () ⇒ ()) extends PushStage[Any, Any] {
    override def onPush(elem: Any, ctx: Context[Any]): SyncDirective =
      ctx.pushAndFinish(elem)

    override def onUpstreamFinish(ctx: Context[Any]): TerminationDirective =
      ctx.fail(akka.stream.testkit.Utils.TE("Cannot happen"))

    override def postStop(): Unit =
      onPostStop()
  }

}
