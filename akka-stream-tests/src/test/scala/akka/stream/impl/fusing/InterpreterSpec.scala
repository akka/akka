/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.stream.Supervision

class InterpreterSpec extends InterpreterSpecKit {
  import Supervision.stoppingDecider

  "Interpreter" must {

    "implement map correctly" in new TestSetup(Seq(Map((x: Int) ⇒ x + 1, stoppingDecider))) {
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

    "implement chain of maps correctly" in new TestSetup(Seq(
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

    "work with only boundary ops" in new TestSetup(Seq.empty) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(0)))

      upstream.onComplete()
      lastEvents() should be(Set(OnComplete))
    }

    "implement one-to-many many-to-one chain correctly" in new TestSetup(Seq(
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

    "implement many-to-one one-to-many chain correctly" in new TestSetup(Seq(
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

    "implement take" in new TestSetup(Seq(Take(2))) {

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

    "implement take inside a chain" in new TestSetup(Seq(
      Filter((x: Int) ⇒ x != 0, stoppingDecider),
      Take(2),
      Map((x: Int) ⇒ x + 1, stoppingDecider))) {

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

    "implement fold" in new TestSetup(Seq(Fold(0, (agg: Int, x: Int) ⇒ agg + x, stoppingDecider))) {
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

    "implement fold with proper cancel" in new TestSetup(Seq(Fold(0, (agg: Int, x: Int) ⇒ agg + x, stoppingDecider))) {

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

    "work if fold completes while not in a push position" in new TestSetup(Seq(Fold(0, (agg: Int, x: Int) ⇒ agg + x, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      upstream.onComplete()
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(OnComplete, OnNext(0)))
    }

    "implement grouped" in new TestSetup(Seq(Grouped(3))) {
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

    "implement conflate" in new TestSetup(Seq(Conflate(
      (in: Int) ⇒ in,
      (agg: Int, x: Int) ⇒ agg + x,
      stoppingDecider))) {

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

    "implement expand" in new TestSetup(Seq(Expand(
      (in: Int) ⇒ in,
      (agg: Int) ⇒ (agg, agg)))) {

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

    "work with conflate-conflate" in new TestSetup(Seq(
      Conflate(
        (in: Int) ⇒ in,
        (agg: Int, x: Int) ⇒ agg + x,
        stoppingDecider),
      Conflate(
        (in: Int) ⇒ in,
        (agg: Int, x: Int) ⇒ agg + x,
        stoppingDecider))) {

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

    "work with expand-expand" in new TestSetup(Seq(
      Expand(
        (in: Int) ⇒ in,
        (agg: Int) ⇒ (agg, agg + 1)),
      Expand(
        (in: Int) ⇒ in,
        (agg: Int) ⇒ (agg, agg + 1)))) {

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

    "implement conflate-expand" in new TestSetup(Seq(
      Conflate(
        (in: Int) ⇒ in,
        (agg: Int, x: Int) ⇒ agg + x,
        stoppingDecider),
      Expand(
        (in: Int) ⇒ in,
        (agg: Int) ⇒ (agg, agg)))) {

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

    "implement expand-conflate" in {
      pending
      // Needs to detect divergent loops
    }

    "implement doubler-conflate" in new TestSetup(Seq(
      Doubler(),
      Conflate(
        (in: Int) ⇒ in,
        (agg: Int, x: Int) ⇒ agg + x,
        stoppingDecider))) {
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(6)))

    }

    "work with jumpback table and completed elements" in new TestSetup(Seq(
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

    "work with pushAndFinish if upstream completes with pushAndFinish" in new TestSetup(Seq(
      new PushFinishStage)) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNextAndComplete("foo")
      lastEvents() should be(Set(OnNext("foo"), OnComplete))
    }

    "work with pushAndFinish if indirect upstream completes with pushAndFinish" in new TestSetup(Seq(
      Map((x: Any) ⇒ x, stoppingDecider),
      new PushFinishStage,
      Map((x: Any) ⇒ x, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNextAndComplete("foo")
      lastEvents() should be(Set(OnNext("foo"), OnComplete))
    }

    "work with pushAndFinish if upstream completes with pushAndFinish and downstream immediately pulls" in new TestSetup(Seq(
      new PushFinishStage,
      Fold("", (x: String, y: String) ⇒ x + y, stoppingDecider))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNextAndComplete("foo")
      lastEvents() should be(Set(OnNext("foo"), OnComplete))
    }

    "implement expand-filter" in pending

    "implement take-conflate" in pending

    "implement conflate-take" in pending

    "implement take-expand" in pending

    "implement expand-take" in pending

    "implement take-take" in new TestSetup(Seq(
      Take(1),
      Take(1))) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext("foo")
      lastEvents() should be(Set(OnNext("foo"), OnComplete, Cancel))

    }

    "implement take-take with pushAndFinish from upstream" in new TestSetup(Seq(
      Take(1),
      Take(1))) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNextAndComplete("foo")
      lastEvents() should be(Set(OnNext("foo"), OnComplete))

    }

    "implement take-drop" in pending

    "implement drop-take" in pending

    "work with keep-going ops" in pending

  }

}
