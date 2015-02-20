/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.stream.Supervision

class InterpreterStressSpec extends InterpreterSpecKit {
  import Supervision.stoppingDecider

  val chainLength = 1000 * 1000
  val halfLength = chainLength / 2
  val repetition = 100

  "Interpreter" must {

    "work with a massive chain of maps" in new TestSetup(Seq.fill(chainLength)(Map((x: Int) ⇒ x + 1, stoppingDecider))) {
      lastEvents() should be(Set.empty)
      val tstamp = System.nanoTime()

      var i = 0
      while (i < repetition) {
        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))

        upstream.onNext(i)
        lastEvents() should be(Set(OnNext(i + chainLength)))
        i += 1
      }

      upstream.onComplete()
      lastEvents() should be(Set(OnComplete))

      val time = (System.nanoTime() - tstamp) / (1000.0 * 1000.0 * 1000.0)
      // FIXME: Not a real benchmark, should be replaced by a proper JMH bench
      info(s"Chain finished in $time seconds ${(chainLength * repetition) / (time * 1000 * 1000)} million maps/s")
    }

    "work with a massive chain of maps with early complete" in new TestSetup(Seq.fill(halfLength)(Map((x: Int) ⇒ x + 1, stoppingDecider)) ++
      Seq(Take(repetition / 2)) ++
      Seq.fill(halfLength)(Map((x: Int) ⇒ x + 1, stoppingDecider))) {

      lastEvents() should be(Set.empty)
      val tstamp = System.nanoTime()

      var i = 0
      while (i < (repetition / 2) - 1) {
        downstream.requestOne()
        lastEvents() should be(Set(RequestOne))

        upstream.onNext(i)
        lastEvents() should be(Set(OnNext(i + chainLength)))
        i += 1
      }

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(Cancel, OnComplete, OnNext(0 + chainLength)))

      val time = (System.nanoTime() - tstamp) / (1000.0 * 1000.0 * 1000.0)
      // FIXME: Not a real benchmark, should be replaced by a proper JMH bench
      info(s"Chain finished in $time seconds ${(chainLength * repetition) / (time * 1000 * 1000)} million maps/s")
    }

    "work with a massive chain of takes" in new TestSetup(Seq.fill(chainLength)(Take(1))) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(0)
      lastEvents() should be(Set(Cancel, OnNext(0), OnComplete))

    }

    "work with a massive chain of drops" in new TestSetup(Seq.fill(chainLength / 1000)(Drop(1))) {
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))

      var i = 0
      while (i < (chainLength / 1000)) {
        upstream.onNext(0)
        lastEvents() should be(Set(RequestOne))
        i += 1
      }

      upstream.onNext(0)
      lastEvents() should be(Set(OnNext(0)))

    }

    "work with a massive chain of conflates by overflowing to the heap" in new TestSetup(Seq.fill(100000)(Conflate(
      (in: Int) ⇒ in,
      (agg: Int, in: Int) ⇒ agg + in,
      Supervision.stoppingDecider)),
      forkLimit = 100,
      overflowToHeap = true) {

      lastEvents() should be(Set(RequestOne))

      var i = 0
      while (i < repetition) {
        upstream.onNext(1)
        lastEvents() should be(Set(RequestOne))
        i += 1
      }

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(repetition)))

    }

  }

}
