/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import scala.util.control.NoStackTrace

class InterpreterSpec extends InterpreterSpecKit {

  "Interpreter" must {

    "implement map correctly" in new TestSetup(Seq(Map((x: Int) ⇒ x + 1))) {
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
      Map((x: Int) ⇒ x + 1),
      Map((x: Int) ⇒ x * 2),
      Map((x: Int) ⇒ x + 1))) {

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
      Filter((x: Int) ⇒ x != 0))) {

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
      Filter((x: Int) ⇒ x != 0),
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
      Filter((x: Int) ⇒ x != 0),
      Take(2),
      Map((x: Int) ⇒ x + 1))) {

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

    "implement fold" in new TestSetup(Seq(Fold(0, (agg: Int, x: Int) ⇒ agg + x))) {
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

    "implement fold with proper cancel" in new TestSetup(Seq(Fold(0, (agg: Int, x: Int) ⇒ agg + x))) {

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

    "work if fold completes while not in a push position" in new TestSetup(Seq(Fold(0, (agg: Int, x: Int) ⇒ agg + x))) {

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
      (agg: Int, x: Int) ⇒ agg + x))) {

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
        (agg: Int, x: Int) ⇒ agg + x),
      Conflate(
        (in: Int) ⇒ in,
        (agg: Int, x: Int) ⇒ agg + x))) {

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
        (agg: Int) ⇒ (agg, agg)),
      Expand(
        (in: Int) ⇒ in,
        (agg: Int) ⇒ (agg, agg)))) {

      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(0)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(0)))

      upstream.onNext(1)
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne, OnNext(0))) // One zero is still in the pipeline

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(1)))

      upstream.onComplete()
      lastEvents() should be(Set(OnComplete))
    }

    "implement conflate-expand" in new TestSetup(Seq(
      Conflate(
        (in: Int) ⇒ in,
        (agg: Int, x: Int) ⇒ agg + x),
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
        (agg: Int, x: Int) ⇒ agg + x))) {
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(6)))

    }

    "implement expand-filter" in pending

    "implement take-conflate" in pending

    "implement conflate-take" in pending

    "implement take-expand" in pending

    "implement expand-take" in pending

    "implement take-take" in pending

    "implement take-drop" in pending

    "implement drop-take" in pending

    val TE = new Exception("TEST") with NoStackTrace {
      override def toString = "TE"
    }

    "handle external failure" in new TestSetup(Seq(Map((x: Int) ⇒ x + 1))) {
      lastEvents() should be(Set.empty)

      upstream.onError(TE)
      lastEvents() should be(Set(OnError(TE)))

    }

    "handle failure inside op" in new TestSetup(Seq(Map((x: Int) ⇒ if (x == 0) throw TE else x))) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(2)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(Cancel, OnError(TE)))

    }

    "handle failure inside op in middle of the chain" in new TestSetup(Seq(
      Map((x: Int) ⇒ x + 1),
      Map((x: Int) ⇒ if (x == 0) throw TE else x),
      Map((x: Int) ⇒ x + 1))) {

      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(4)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(-1)
      lastEvents() should be(Set(Cancel, OnError(TE)))

    }

    "work with keep-going ops" in pending

  }

}
