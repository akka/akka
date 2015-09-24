/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.stream.testkit.AkkaSpec

class GraphInterpreterPortsSpec extends AkkaSpec with GraphInterpreterSpecKit {

  "Port states" must {

    "properly transition on push and pull" in new PortTestSetup {
      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      in.pull()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      stepAll()

      lastEvents() should be(Set(RequestOne(out)))
      out.isAvailable should be(true)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      out.push(0)

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      stepAll()

      lastEvents() should be(Set(OnNext(in, 0)))
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(true)
      in.hasBeenPulled should be(false)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { out.push(0) }

      in.grab() should ===(0)

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      // Cycle completed
    }

    "drop ungrabbed element on pull" in new PortTestSetup {
      in.pull()
      step()
      clearEvents()
      out.push(0)
      step()

      lastEvents() should be(Set(OnNext(in, 0)))

      in.pull()

      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

    "propagate complete while downstream is active" in new PortTestSetup {
      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(false)

      out.complete()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { out.push(0) }

      stepAll()

      lastEvents() should be(Set(OnComplete(in)))
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      in.cancel() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      out.complete() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

    "propagate complete while upstream is active" in new PortTestSetup {
      in.pull()
      stepAll()

      lastEvents() should be(Set(RequestOne(out)))
      out.isAvailable should be(true)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)

      out.complete()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { out.push(0) }

      stepAll()

      lastEvents() should be(Set(OnComplete(in)))
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      in.cancel() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      out.complete() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

    }

    "propagate complete while pull is in flight" in new PortTestSetup {
      in.pull()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)

      out.complete()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { out.push(0) }

      stepAll()

      lastEvents() should be(Set(OnComplete(in)))
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      in.cancel() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      out.complete() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

    "propagate complete while push is in flight" in new PortTestSetup {
      in.pull()
      stepAll()
      clearEvents()

      out.push(0)

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)

      out.complete()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { out.push(0) }

      step()

      lastEvents() should be(Set(OnNext(in, 0)))
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(true)
      in.hasBeenPulled should be(false)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      in.grab() should ===(0)
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      step()

      lastEvents() should be(Set(OnComplete(in)))
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      out.complete() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

    "propagate complete while push is in flight and keep ungrabbed element" in new PortTestSetup {
      in.pull()
      stepAll()
      clearEvents()

      out.push(0)
      out.complete()
      step()

      lastEvents() should be(Set(OnNext(in, 0)))
      step()

      lastEvents() should be(Set(OnComplete(in)))
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(true)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      in.grab() should ===(0)
    }

    "propagate cancel while downstream is active" in new PortTestSetup {
      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(false)

      in.cancel()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      stepAll()

      lastEvents() should be(Set(Cancel(out)))
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      out.complete() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      in.cancel() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

    "propagate cancel while upstream is active" in new PortTestSetup {
      in.pull()
      stepAll()

      lastEvents() should be(Set(RequestOne(out)))
      out.isAvailable should be(true)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)

      in.cancel()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(true)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      stepAll()

      lastEvents() should be(Set(Cancel(out)))
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      out.complete() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      in.cancel() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

    }

    "propagate cancel while pull is in flight" in new PortTestSetup {
      in.pull()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)

      in.cancel()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      stepAll()

      lastEvents() should be(Set(Cancel(out)))
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      out.complete() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      in.cancel() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

    "propagate cancel while push is in flight" in new PortTestSetup {
      in.pull()
      stepAll()
      clearEvents()

      out.push(0)

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)

      in.cancel()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }

      stepAll()

      lastEvents() should be(Set(Cancel(out)))
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      out.complete() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      in.cancel() // This should have no effect now
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

    "clear ungrabbed element even when cancelled" in new PortTestSetup {
      in.pull()
      stepAll()
      clearEvents()
      out.push(0)
      stepAll()

      lastEvents() should be(Set(OnNext(in, 0)))

      in.cancel()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { in.grab() }

      stepAll()
      lastEvents() should be(Set(Cancel(out)))
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

    "ignore any completion if they are concurrent (cancel first)" in new PortTestSetup {
      in.cancel()
      out.complete()

      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

    "ignore any completion if they are concurrent (complete first)" in new PortTestSetup {
      out.complete()
      in.cancel()

      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

    "ignore completion from a push-complete if cancelled while in flight" in new PortTestSetup {
      in.pull()
      stepAll()
      clearEvents()

      out.push(0)
      out.complete()
      in.cancel()

      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

    "ignore completion from a push-complete if cancelled after onPush" in new PortTestSetup {
      in.pull()
      stepAll()
      clearEvents()

      out.push(0)
      out.complete()

      step()

      lastEvents() should be(Set(OnNext(in, 0)))
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(true)
      in.hasBeenPulled should be(false)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      in.grab() should ===(0)

      in.cancel()
      stepAll()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

  }

}
