/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import akka.stream.testkit.StreamSpec

import scala.util.control.NoStackTrace
import akka.stream.{ ActorAttributes, Attributes, Supervision }
import akka.stream.stage._
import akka.testkit.AkkaSpec

class InterpreterSupervisionSpec extends StreamSpec with GraphInterpreterSpecKit {
  import Supervision.stoppingDecider
  import Supervision.resumingDecider
  import Supervision.restartingDecider

  val TE = new Exception("TEST") with NoStackTrace {
    override def toString = "TE"
  }

  class ResumingMap[In, Out](_f: In ⇒ Out) extends Map(_f) {

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      super.createLogic(inheritedAttributes.and(ActorAttributes.supervisionStrategy(resumingDecider)))
  }

  "Interpreter error handling" must {

    "handle external failure" in new OneBoundedSetup[Int](Map((x: Int) ⇒ x + 1)) {
      lastEvents() should be(Set.empty)

      upstream.onError(TE)
      lastEvents() should be(Set(OnError(TE)))
    }

    "emit failure when op throws" in new OneBoundedSetup[Int](Map((x: Int) ⇒ if (x == 0) throw TE else x)) {
      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(2)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(0) // boom
      lastEvents() should be(Set(Cancel, OnError(TE)))
    }

    "emit failure when op throws in middle of the chain" in new OneBoundedSetup[Int](
      Map((x: Int) ⇒ x + 1),
      Map((x: Int) ⇒ if (x == 0) throw TE else x + 10),
      Map((x: Int) ⇒ x + 100)) {

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(113)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(-1) // boom
      lastEvents() should be(Set(Cancel, OnError(TE)))
    }

    "resume when Map throws" in new OneBoundedSetup[Int](
      new ResumingMap((x: Int) ⇒ if (x == 0) throw TE else x)
    ) {
      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(2)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(0) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(3)
      lastEvents() should be(Set(OnNext(3)))

      // try one more time
      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(0) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(4)
      lastEvents() should be(Set(OnNext(4)))
    }

    "resume when Map throws in middle of the chain" in new OneBoundedSetup[Int](
      new ResumingMap((x: Int) ⇒ x + 1),
      new ResumingMap((x: Int) ⇒ if (x == 0) throw TE else x + 10),
      new ResumingMap((x: Int) ⇒ x + 100)
    ) {

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(OnNext(113)))

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(-1) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(3)
      lastEvents() should be(Set(OnNext(114)))
    }

    "resume when Map throws before Grouped" in new OneBoundedSetup[Int](
      new ResumingMap((x: Int) ⇒ x + 1),
      new ResumingMap((x: Int) ⇒ if (x <= 0) throw TE else x + 10),
      Grouped(3)) {

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(-1) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(3)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(4)
      lastEvents() should be(Set(OnNext(Vector(13, 14, 15))))
    }

    "complete after resume when Map throws before Grouped" in new OneBoundedSetup[Int](
      new ResumingMap((x: Int) ⇒ x + 1),
      new ResumingMap((x: Int) ⇒ if (x <= 0) throw TE else x + 10),
      Grouped(1000)) {

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne))
      upstream.onNext(2)
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(-1) // boom
      lastEvents() should be(Set(RequestOne))

      upstream.onNext(3)
      lastEvents() should be(Set(RequestOne))

      upstream.onComplete()
      lastEvents() should be(Set(OnNext(Vector(13, 14)), OnComplete))
    }

    "fail when Expand `seed` throws" in new OneBoundedSetup[Int](
      new Expand((in: Int) ⇒ if (in == 2) throw TE else Iterator(in) ++ Iterator.continually(-math.abs(in)))) {

      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne, OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(-1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(-1)))

      upstream.onNext(2) // boom
      lastEvents() should be(Set(OnError(TE), Cancel))
    }

    "fail when Expand `extrapolate` throws" in new OneBoundedSetup[Int](
      new Expand((in: Int) ⇒ if (in == 2) Iterator.continually(throw TE) else Iterator(in) ++ Iterator.continually(-math.abs(in)))) {

      lastEvents() should be(Set(RequestOne))

      upstream.onNext(1)
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(RequestOne, OnNext(1)))

      downstream.requestOne()
      lastEvents() should be(Set(OnNext(-1)))

      upstream.onNext(2) // boom
      lastEvents() should be(Set.empty)

      downstream.requestOne()
      lastEvents() should be(Set(OnError(TE), Cancel))
    }
  }

}
