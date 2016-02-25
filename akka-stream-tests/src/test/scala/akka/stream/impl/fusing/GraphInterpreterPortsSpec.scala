/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import akka.testkit.AkkaSpec
import akka.stream.testkit.Utils._

class GraphInterpreterPortsSpec extends AkkaSpec with GraphInterpreterSpecKit {

  "Port states" must {

    // FIXME test failure scenarios

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

    "propagate complete while push is in flight and pulled after the push" in new PortTestSetup {
      in.pull()
      stepAll()
      clearEvents()

      out.push(0)
      out.complete()
      step()

      lastEvents() should be(Set(OnNext(in, 0)))
      in.grab() should ===(0)

      in.pull()
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
    }

    "ignore pull while completing" in new PortTestSetup {
      out.complete()
      in.pull()
      // While the pull event is not enqueued at this point, we should still report the state correctly
      in.hasBeenPulled should be(true)

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

    "ignore push while cancelling" in new PortTestSetup {
      in.pull()
      stepAll()
      clearEvents()

      in.cancel()
      out.push(0)
      // While the push event is not enqueued at this point, we should still report the state correctly
      out.isAvailable should be(false)

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

    "not allow to grab element before it arrives" in new PortTestSetup {
      in.pull()
      stepAll()
      out.push(0)

      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

    "not allow to grab element if already cancelled" in new PortTestSetup {
      in.pull()
      stepAll()

      out.push(0)
      in.cancel()

      stepAll()

      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

    "propagate failure while downstream is active" in new PortTestSetup {
      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(false)

      out.fail(TE("test"))

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { out.push(0) }

      stepAll()

      lastEvents() should be(Set(OnError(in, TE("test"))))
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

    "propagate failure while upstream is active" in new PortTestSetup {
      in.pull()
      stepAll()

      lastEvents() should be(Set(RequestOne(out)))
      out.isAvailable should be(true)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)

      out.fail(TE("test"))

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { out.push(0) }

      stepAll()

      lastEvents() should be(Set(OnError(in, TE("test"))))
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

    "propagate failure while pull is in flight" in new PortTestSetup {
      in.pull()

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(false)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)

      out.fail(TE("test"))

      lastEvents() should be(Set.empty)
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(true)
      in.isClosed should be(false)
      an[IllegalArgumentException] should be thrownBy { out.push(0) }

      stepAll()

      lastEvents() should be(Set(OnError(in, TE("test"))))
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

    "propagate failure while push is in flight" in new PortTestSetup {
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

      out.fail(TE("test"))

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

      lastEvents() should be(Set(OnError(in, TE("test"))))
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

    "propagate failure while push is in flight and keep ungrabbed element" in new PortTestSetup {
      in.pull()
      stepAll()
      clearEvents()

      out.push(0)
      out.fail(TE("test"))
      step()

      lastEvents() should be(Set(OnNext(in, 0)))
      step()

      lastEvents() should be(Set(OnError(in, TE("test"))))
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(true)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      in.grab() should ===(0)
    }

    "ignore pull while failing" in new PortTestSetup {
      out.fail(TE("test"))
      in.pull()
      in.hasBeenPulled should be(true)

      stepAll()

      lastEvents() should be(Set(OnError(in, TE("test"))))
      out.isAvailable should be(false)
      out.isClosed should be(true)
      in.isAvailable should be(false)
      in.hasBeenPulled should be(false)
      in.isClosed should be(true)
      an[IllegalArgumentException] should be thrownBy { in.pull() }
      an[IllegalArgumentException] should be thrownBy { out.push(0) }
      an[IllegalArgumentException] should be thrownBy { in.grab() }
    }

    "ignore any failure completion if they are concurrent (cancel first)" in new PortTestSetup {
      in.cancel()
      out.fail(TE("test"))

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

    "ignore any failure completion if they are concurrent (complete first)" in new PortTestSetup {
      out.fail(TE("test"))
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

    "ignore failure from a push-then-fail if cancelled while in flight" in new PortTestSetup {
      in.pull()
      stepAll()
      clearEvents()

      out.push(0)
      out.fail(TE("test"))
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

    "ignore failure from a push-then-fail if cancelled after onPush" in new PortTestSetup {
      in.pull()
      stepAll()
      clearEvents()

      out.push(0)
      out.fail(TE("test"))

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
