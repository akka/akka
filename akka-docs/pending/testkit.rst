Actor TestKit
=============

Module Stability: **In Progress**

Overview
--------

Testing actors comprises several aspects, which can have different weight according to the concrete project at hand:
* If you have a collection of actors which performs a certain function, you may want to apply defined stimuli and observe the delivery of the desired result messages to a test actor; in this case the ***TestKit*** trait will likely interest you.
* If you encounter undesired behavior (exceptions, dead-locks) and want to nail down the cause, it might help to run the actors in question using the ***CallingThreadDispatcher***; this dispatcher is strictly less powerful than the general purpose ones, but its deterministic behavior and complete message stack can help debugging, unless your setup depends on concurrent execution for correctness.
* For real unit tests of one actor body at a time, there soon will be a special ***TestActorRef*** which allows access to the innards and enables running without a dispatcher.

TestKit
-------

The TestKit is a trait which you can mix into your test class to setup a test harness consisting of an test actor, which is implicitly available as sender reference, methods for querying and asserting features of messages received by said actor, and finally methods which provide a DSL for timing assertions.

Ray Roestenburg has written a great article on using the TestKit: `<http://roestenburg.agilesquad.com/2011/02/unit-testing-akka-actors-with-testkit_12.html>`_. Here is a short teaser:

.. code-block:: scala

  class SomeSpec extends WordSpec with MustMatchers with TestKit {

    val worker = actorOf(...)

    "A Worker" must {
      "send timely replies" in {
        within (50 millis) {
          worker ! "some work"
          expectMsg("some result")
          expectNoMsg
        }
      }
    }
  }

His full example is also available `here <testkit-example>`_.

CallingThreadDispatcher
-----------------------

This special purpose dispatcher was conceived to enable collection of the full stack trace accumulated during processing of a complete message chain. The idea is to run invocations always on the calling thread, except when the target actor is already running on the current thread; in that case it is necessary to queue the invocation and run it after the current invocation on that actor has finished processing. This design implies that any invocation which blocks waiting on some future action to be done by the current thread will dead-lock. Hence, the CallingThreadDispatcher offers strictly more possibilities to dead-lock than a standard dispatcher.

One nice property is that this feature can help verify that your design is dead-lock free: if you run only on this dispatcher and utilize only one thread, then a successful run implies that for the given set of inputs there cannot be a dead-lock. (This is unfortunately not a hard guarantee, as long as your actor behavior depends on the dispatcher used, e.g. you could sabotage it by explicitly dead-locking only if self.dispatcher != CallingThreadDispatcher.)

TestActorRef (coming soon ...)
------------------------------

