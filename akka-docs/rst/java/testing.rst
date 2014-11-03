.. _akka-testkit-java:

##############################
Testing Actor Systems
##############################

As with any piece of software, automated tests are a very important part of the
development cycle. The actor model presents a different view on how units of
code are delimited and how they interact, which has an influence on how to
perform tests.

.. note::

  Due to the conciseness of test DSLs available for Scala (`ScalaTest`_,
  `Specs2`_, `ScalaCheck`_), it may be a good idea to write the test suite in
  that language even if the main project is written in Java. If that is not
  desirable, you can also use :class:`TestKit` and friends from Java, albeit
  with more verbose syntax which is covered below. Munish Gupta has `published
  a nice post
  <http://www.akkaessentials.in/2012/05/using-testkit-with-java.html>`_ showing
  several patterns you may find useful.

.. _ScalaTest:  http://scalatest.org/
.. _Specs2:     http://specs2.org/
.. _ScalaCheck: http://code.google.com/p/scalacheck/

Akka comes with a dedicated module :mod:`akka-testkit` for supporting tests at
different levels, which fall into two clearly distinct categories:

 - Testing isolated pieces of code without involving the actor model, meaning
   without multiple threads; this implies completely deterministic behavior
   concerning the ordering of events and no concurrency concerns and will be
   called **Unit Testing** in the following.
 - Testing (multiple) encapsulated actors including multi-threaded scheduling;
   this implies non-deterministic order of events but shielding from
   concurrency concerns by the actor model and will be called **Integration
   Testing** in the following.

There are of course variations on the granularity of tests in both categories,
where unit testing reaches down to white-box tests and integration testing can
encompass functional tests of complete actor networks. The important
distinction lies in whether concurrency concerns are part of the test or not.
The tools offered are described in detail in the following sections.

.. note::

   Be sure to add the module :mod:`akka-testkit` to your dependencies.

Synchronous Unit Testing with :class:`TestActorRef`
===================================================

Testing the business logic inside :class:`Actor` classes can be divided into
two parts: first, each atomic operation must work in isolation, then sequences
of incoming events must be processed correctly, even in the presence of some
possible variability in the ordering of events. The former is the primary use
case for single-threaded unit testing, while the latter can only be verified in
integration tests.

Normally, the :class:`ActorRef` shields the underlying :class:`Actor` instance
from the outside, the only communications channel is the actor's mailbox. This
restriction is an impediment to unit testing, which led to the inception of the
:class:`TestActorRef`. This special type of reference is designed specifically
for test purposes and allows access to the actor in two ways: either by
obtaining a reference to the underlying actor instance, or by invoking or
querying the actor's behaviour (:meth:`receive`). Each one warrants its own
section below.

Obtaining a Reference to an :class:`Actor`
------------------------------------------

Having access to the actual :class:`Actor` object allows application of all
traditional unit testing techniques on the contained methods. Obtaining a
reference is done like this:

.. includecode:: code/docs/testkit/TestKitDocTest.java#test-actor-ref

Since :class:`TestActorRef` is generic in the actor type it returns the
underlying actor with its proper static type. From this point on you may bring
any unit testing tool to bear on your actor as usual.

Testing the Actor's Behavior
----------------------------

When the dispatcher invokes the processing behavior of an actor on a message,
it actually calls :meth:`apply` on the current behavior registered for the
actor. This starts out with the return value of the declared :meth:`receive`
method, but it may also be changed using :meth:`become` and :meth:`unbecome` in
response to external messages. All of this contributes to the overall actor
behavior and it does not lend itself to easy testing on the :class:`Actor`
itself. Therefore the :class:`TestActorRef` offers a different mode of
operation to complement the :class:`Actor` testing: it supports all operations
also valid on normal :class:`ActorRef`. Messages sent to the actor are
processed synchronously on the current thread and answers may be sent back as
usual. This trick is made possible by the :class:`CallingThreadDispatcher`
described below (see `CallingThreadDispatcher`_); this dispatcher is set
implicitly for any actor instantiated into a :class:`TestActorRef`.

.. includecode:: code/docs/testkit/TestKitDocTest.java#test-behavior

As the :class:`TestActorRef` is a subclass of :class:`LocalActorRef` with a few
special extras, also aspects like supervision and restarting work properly, but
beware that execution is only strictly synchronous as long as all actors
involved use the :class:`CallingThreadDispatcher`. As soon as you add elements
which include more sophisticated scheduling you leave the realm of unit testing
as you then need to think about asynchronicity again (in most cases the problem
will be to wait until the desired effect had a chance to happen).

One more special aspect which is overridden for single-threaded tests is the
:meth:`receiveTimeout`, as including that would entail asynchronous queuing of
:obj:`ReceiveTimeout` messages, violating the synchronous contract.

.. note::

   To summarize: :class:`TestActorRef` overwrites two fields: it sets the
   dispatcher to :obj:`CallingThreadDispatcher.global` and it sets the
   :obj:`receiveTimeout` to None.

The Way In-Between: Expecting Exceptions
----------------------------------------

If you want to test the actor behavior, including hotswapping, but without
involving a dispatcher and without having the :class:`TestActorRef` swallow
any thrown exceptions, then there is another mode available for you: just use
the :meth:`receive` method on :class:`TestActorRef`, which will be forwarded to the
underlying actor:

.. includecode:: code/docs/testkit/TestKitDocTest.java#test-expecting-exceptions

Use Cases
---------

You may of course mix and match both modi operandi of :class:`TestActorRef` as
suits your test needs:

 - one common use case is setting up the actor into a specific internal state
   before sending the test message
 - another is to verify correct internal state transitions after having sent
   the test message

Feel free to experiment with the possibilities, and if you find useful
patterns, don't hesitate to let the Akka forums know about them! Who knows,
common operations might even be worked into nice DSLs.

Asynchronous Integration Testing with :class:`JavaTestKit`
==========================================================

When you are reasonably sure that your actor's business logic is correct, the
next step is verifying that it works correctly within its intended environment.
The definition of the environment depends of course very much on the problem at
hand and the level at which you intend to test, ranging for
functional/integration tests to full system tests. The minimal setup consists
of the test procedure, which provides the desired stimuli, the actor under
test, and an actor receiving replies.  Bigger systems replace the actor under
test with a network of actors, apply stimuli at varying injection points and
arrange results to be sent from different emission points, but the basic
principle stays the same in that a single procedure drives the test.

The :class:`JavaTestKit` class contains a collection of tools which makes this
common task easy.

.. includecode:: code/docs/testkit/TestKitSampleTest.java#fullsample

The :class:`JavaTestKit` contains an actor named :obj:`testActor` which is the
entry point for messages to be examined with the various ``expectMsg...``
assertions detailed below. The test actor’s reference is obtained using the
:meth:`getRef()` method as demonstrated above.  The :obj:`testActor` may also
be passed to other actors as usual, usually subscribing it as notification
listener. There is a whole set of examination methods, e.g. receiving all
consecutive messages matching certain criteria, receiving a whole sequence of
fixed messages or classes, receiving nothing for some time, etc.

The ActorSystem passed in to the constructor of JavaTestKit is accessible via the
:meth:`getSystem()` method.

.. note::

  Remember to shut down the actor system after the test is finished (also in
  case of failure) so that all actors—including the test actor—are stopped.

Built-In Assertions
-------------------

The above mentioned :meth:`expectMsgEquals` is not the only method for
formulating assertions concerning received messages, the full set is this:

.. includecode:: code/docs/testkit/TestKitDocTest.java#test-expect

In these examples, the maximum durations you will find mentioned below are left
out, in which case they use the default value from configuration item
``akka.test.single-expect-default`` which itself defaults to 3 seconds (or they
obey the innermost enclosing :class:`Within` as detailed :ref:`below
<JavaTestKit.within>`). The full signatures are:

  * :meth:`public <T> T expectMsgEquals(Duration max, T msg)`

    The given message object must be received within the specified time; the
    object will be returned.

  * :meth:`public Object expectMsgAnyOf(Duration max, Object... msg)`

    An object must be received within the given time, and it must be equal
    (compared with ``equals()``) to at least one of the passed reference
    objects; the received object will be returned.

  * :meth:`public Object[] expectMsgAllOf(Duration max, Object... msg)`

    A number of objects matching the size of the supplied object array must be
    received within the given time, and for each of the given objects there
    must exist at least one among the received ones which equals it (compared
    with ``equals()``). The full sequence of received objects is returned in
    the order received.

  * :meth:`public <T> T expectMsgClass(Duration max, Class<T> c)`

    An object which is an instance of the given :class:`Class` must be received
    within the allotted time frame; the object will be returned. Note that this
    does a conformance check, if you need the class to be equal you need to
    verify that afterwards.

  * :meth:`public <T> T expectMsgAnyClassOf(Duration max, Class<? extends T>... c)`

    An object must be received within the given time, and it must be an
    instance of at least one of the supplied :class:`Class` objects; the
    received object will be returned. Note that this does a conformance check,
    if you need the class to be equal you need to verify that afterwards.

    .. note::

      Because of a limitation in Java’s type system it may be necessary to add
      ``@SuppressWarnings("unchecked")`` when using this method.

  * :meth:`public void expectNoMsg(Duration max)`

    No message must be received within the given time. This also fails if a
    message has been received before calling this method which has not been
    removed from the queue using one of the other methods.

  * :meth:`Object[] receiveN(int n, Duration max)`

    ``n`` messages must be received within the given time; the received
    messages are returned.

For cases which require more refined conditions there are constructs which take
code blocks:

  * **ExpectMsg<T>**

    .. includecode:: code/docs/testkit/TestKitDocTest.java#test-expectmsg

    The :meth:`match(Object in)` method will be evaluated once a message has
    been received within the allotted time (which may be given as constructor
    argument). If it throws ``noMatch()`` (where it is sufficient to call that
    method; the ``throw`` keyword is only needed in cases where the compiler
    would otherwise complain about wrong return types—Java is lacking Scala’s
    notion of a type which signifies “will not ever return normally”), then the
    expectation fails with an :class:`AssertionError`, otherwise the matched
    and possibly transformed object is stored for retrieval using the
    :meth:`get()` method.

  * **ReceiveWhile<T>**

    .. includecode:: code/docs/testkit/TestKitDocTest.java#test-receivewhile

    This construct works like ExpectMsg, but it continually collects messages
    as long as they match the criteria, and it does not fail when a
    non-matching one is encountered. Collecting messages also ends when the
    time is up, when too much time passes between messages or when enough
    messages have been received.

    .. includecode:: code/docs/testkit/TestKitDocTest.java#test-receivewhile-full
       :exclude: match-elided

    The need to specify the ``String`` result type twice results from the need
    to create a correctly typed array and Java’s inability to infer the class’s
    type argument.

  * **AwaitCond**

    .. includecode:: code/docs/testkit/TestKitDocTest.java#test-awaitCond

    This general construct is not connected with the test kit’s message
    reception, the embedded condition can compute the boolean result from
    anything in scope.

    * **AwaitAssert**

    .. includecode:: code/docs/testkit/TestKitDocTest.java#test-awaitAssert

    This general construct is not connected with the test kit’s message
    reception, the embedded assert can check anything in scope.

There are also cases where not all messages sent to the test kit are actually
relevant to the test, but removing them would mean altering the actors under
test. For this purpose it is possible to ignore certain messages:

  * **IgnoreMsg**

    .. includecode:: code/docs/testkit/TestKitDocTest.java#test-ignoreMsg

Expecting Log Messages
----------------------

Since an integration test does not allow to the internal processing of the
participating actors, verifying expected exceptions cannot be done directly.
Instead, use the logging system for this purpose: replacing the normal event
handler with the :class:`TestEventListener` and using an :class:`EventFilter`
allows assertions on log messages, including those which are generated by
exceptions:

.. includecode:: code/docs/testkit/TestKitDocTest.java#test-event-filter

If a number of occurrences is specific—as demonstrated above—then ``exec()``
will block until that number of matching messages have been received or the
timeout configured in ``akka.test.filter-leeway`` is used up (time starts
counting after the ``run()`` method returns). In case of a timeout the test
fails.

.. note::

   Be sure to exchange the default logger with the
   :class:`TestEventListener` in your ``application.conf`` to enable this
   function::

     akka.loggers = [akka.testkit.TestEventListener]

.. _JavaTestKit.within:

Timing Assertions
-----------------

Another important part of functional testing concerns timing: certain events
must not happen immediately (like a timer), others need to happen before a
deadline. Therefore, all examination methods accept an upper time limit within
the positive or negative result must be obtained. Lower time limits need to be
checked external to the examination, which is facilitated by a new construct
for managing time constraints:

.. includecode:: code/docs/testkit/TestKitDocTest.java#test-within

The block in :meth:`Within.run()` must complete after a :ref:`Duration` which
is between :obj:`min` and :obj:`max`, where the former defaults to zero. The
deadline calculated by adding the :obj:`max` parameter to the block's start
time is implicitly available within the block to all examination methods, if
you do not specify it, it is inherited from the innermost enclosing
:meth:`within` block.

It should be noted that if the last message-receiving assertion of the block is
:meth:`expectNoMsg` or :meth:`receiveWhile`, the final check of the
:meth:`within` is skipped in order to avoid false positives due to wake-up
latencies. This means that while individual contained assertions still use the
maximum time bound, the overall block may take arbitrarily longer in this case.

.. note::

   All times are measured using ``System.nanoTime``, meaning that they describe
   wall time, not CPU time or system time.

Accounting for Slow Test Systems
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The tight timeouts you use during testing on your lightning-fast notebook will
invariably lead to spurious test failures on the heavily loaded Jenkins server
(or similar). To account for this situation, all maximum durations are
internally scaled by a factor taken from the :ref:`configuration`,
``akka.test.timefactor``, which defaults to 1.

You can scale other durations with the same factor by using ``dilated`` method
in :class:`JavaTestKit`.

.. includecode:: code/docs/testkit/TestKitDocTest.java#duration-dilation

Using Multiple Probe Actors
---------------------------

When the actors under test are supposed to send various messages to different
destinations, it may be difficult distinguishing the message streams arriving
at the :obj:`testActor` when using the :class:`JavaTestKit` as shown until now.
Another approach is to use it for creation of simple probe actors to be
inserted in the message flows. The functionality is best explained using a
small example:

.. includecode:: code/docs/testkit/TestKitDocTest.java#test-probe

This simple test verifies an equally simple Forwarder actor by injecting a
probe as the forwarder’s target.  Another example would be two actors A and B
which collaborate by A sending messages to B. In order to verify this message
flow, a :class:`TestProbe` could be inserted as target of A, using the
forwarding capabilities or auto-pilot described below to include a real B in
the test setup.

Probes may also be equipped with custom assertions to make your test code even
more concise and clear:

.. includecode:: code/docs/testkit/TestKitDocTest.java
   :include: test-special-probe

You have complete flexibility here in mixing and matching the
:class:`JavaTestKit` facilities with your own checks and choosing an intuitive
name for it. In real life your code will probably be a bit more complicated
than the example given above; just use the power!

.. warning::

  Any message send from a ``TestProbe`` to another actor which runs on the
  CallingThreadDispatcher runs the risk of dead-lock, if that other actor might
  also send to this probe. The implementation of :meth:`TestProbe.watch` and
  :meth:`TestProbe.unwatch` will also send a message to the watchee, which
  means that it is dangerous to try watching e.g. :class:`TestActorRef` from a
  :meth:`TestProbe`.

Watching Other Actors from Probes
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

A :class:`JavaTestKit` can register itself for DeathWatch of any other actor:

.. includecode:: code/docs/testkit/TestKitDocTest.java
   :include: test-probe-watch

Replying to Messages Received by Probes
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The probe stores the sender of the last dequeued message (i.e. after its
``expectMsg*`` reception), which may be retrieved using the
:meth:`getLastSender()` method. This information can also implicitly be used
for having the probe reply to the last received message:

.. includecode:: code/docs/testkit/TestKitDocTest.java#test-probe-reply

Forwarding Messages Received by Probes
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The probe can also forward a received message (i.e. after its ``expectMsg*``
reception), retaining the original sender:

.. includecode:: code/docs/testkit/TestKitDocTest.java#test-probe-forward

Auto-Pilot
^^^^^^^^^^

Receiving messages in a queue for later inspection is nice, but in order to
keep a test running and verify traces later you can also install an
:class:`AutoPilot` in the participating test probes (actually in any
:class:`TestKit`) which is invoked before enqueueing to the inspection queue.
This code can be used to forward messages, e.g. in a chain ``A --> Probe -->
B``, as long as a certain protocol is obeyed.

.. includecode:: code/docs/testkit/TestKitDocTest.java#test-auto-pilot

The :meth:`run` method must return the auto-pilot for the next message, wrapped
in an :class:`Option`; setting it to :obj:`None` terminates the auto-pilot.

Caution about Timing Assertions
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The behavior of :meth:`within` blocks when using test probes might be perceived
as counter-intuitive: you need to remember that the nicely scoped deadline as
described :ref:`above <JavaTestKit.within>` is local to each probe. Hence, probes
do not react to each other's deadlines or to the deadline set in an enclosing
:class:`JavaTestKit` instance:

.. includecode:: code/docs/testkit/TestKitDocTest.java#test-within-probe

Here, the ``expectMsgEquals`` call will use the default timeout.

Testing parent-child relationships
----------------------------------

The parent of an actor is always the actor that created it. At times this leads to
a coupling between the two that may not be straightforward to test.
Broadly, there are three approaches to improve testability of parent-child
relationships:

1. when creating a child, pass an explicit reference to its parent
2. when creating a parent, tell the parent how to create its child
3. create a fabricated parent when testing

For example, the structure of the code you want to test may follow this pattern:

.. includecode:: code/docs/testkit/ParentChildTest.java#test-example

Using dependency-injection
^^^^^^^^^^^^^^^^^^^^^^^^^^

The first option is to avoid use of the :meth:`context.parent` function and create
a child with a custom parent by passing an explicit reference to its parent instead.

.. includecode:: code/docs/testkit/ParentChildTest.java#test-dependentchild

Alternatively, you can tell the parent how to create its child. There are two ways
to do this: by giving it a :class:`Props` object or by giving it a function which takes care of creating the child actor:

.. includecode:: code/docs/testkit/ParentChildTest.java#test-dependentparent
.. includecode:: code/docs/testkit/ParentChildTest.java#test-dependentparent-generic

Creating the :class:`Actor` is straightforward and the function may look like this in your test code:

.. includecode:: code/docs/testkit/ParentChildTest.java#child-maker-test

And like this in your application code:

.. includecode:: code/docs/testkit/ParentChildTest.java#child-maker-prod

Using a fabricated parent
^^^^^^^^^^^^^^^^^^^^^^^^^

If you prefer to avoid modifying the parent or child constructor you can
create a fabricated parent in your test. This, however, does not enable you to test
the parent actor in isolation.

.. includecode:: code/docs/testkit/ParentChildTest.java#test-fabricated-parent-creator
.. includecode:: code/docs/testkit/ParentChildTest.java#test-fabricated-parent

Which of these methods is the best depends on what is most important to test. The
most generic option is to create the parent actor by passing it a function that is
responsible for the Actor creation, but the fabricated parent is often sufficient.

.. _Java-CallingThreadDispatcher:

CallingThreadDispatcher
=======================

The :class:`CallingThreadDispatcher` serves good purposes in unit testing, as
described above, but originally it was conceived in order to allow contiguous
stack traces to be generated in case of an error. As this special dispatcher
runs everything which would normally be queued directly on the current thread,
the full history of a message's processing chain is recorded on the call stack,
so long as all intervening actors run on this dispatcher.

How to use it
-------------

Just set the dispatcher as you normally would:

.. includecode:: code/docs/testkit/TestKitDocTest.java#calling-thread-dispatcher

How it works
------------

When receiving an invocation, the :class:`CallingThreadDispatcher` checks
whether the receiving actor is already active on the current thread. The
simplest example for this situation is an actor which sends a message to
itself. In this case, processing cannot continue immediately as that would
violate the actor model, so the invocation is queued and will be processed when
the active invocation on that actor finishes its processing; thus, it will be
processed on the calling thread, but simply after the actor finishes its
previous work. In the other case, the invocation is simply processed
immediately on the current thread. Futures scheduled via this dispatcher are
also executed immediately.

This scheme makes the :class:`CallingThreadDispatcher` work like a general
purpose dispatcher for any actors which never block on external events.

In the presence of multiple threads it may happen that two invocations of an
actor running on this dispatcher happen on two different threads at the same
time. In this case, both will be processed directly on their respective
threads, where both compete for the actor's lock and the loser has to wait.
Thus, the actor model is left intact, but the price is loss of concurrency due
to limited scheduling. In a sense this is equivalent to traditional mutex style
concurrency.

The other remaining difficulty is correct handling of suspend and resume: when
an actor is suspended, subsequent invocations will be queued in thread-local
queues (the same ones used for queuing in the normal case). The call to
:meth:`resume`, however, is done by one specific thread, and all other threads
in the system will probably not be executing this specific actor, which leads
to the problem that the thread-local queues cannot be emptied by their native
threads. Hence, the thread calling :meth:`resume` will collect all currently
queued invocations from all threads into its own queue and process them.

Limitations
-----------

.. warning::

   In case the CallingThreadDispatcher is used for top-level actors, but
   without going through TestActorRef, then there is a time window during which
   the actor is awaiting construction by the user guardian actor. Sending
   messages to the actor during this time period will result in them being
   enqueued and then executed on the guardian’s thread instead of the caller’s
   thread. To avoid this, use TestActorRef.

If an actor's behavior blocks on a something which would normally be affected
by the calling actor after having sent the message, this will obviously
dead-lock when using this dispatcher. This is a common scenario in actor tests
based on :class:`CountDownLatch` for synchronization:

.. code-block:: scala

   val latch = new CountDownLatch(1)
   actor ! startWorkAfter(latch)   // actor will call latch.await() before proceeding
   doSomeSetupStuff()
   latch.countDown()

The example would hang indefinitely within the message processing initiated on
the second line and never reach the fourth line, which would unblock it on a
normal dispatcher.

Thus, keep in mind that the :class:`CallingThreadDispatcher` is not a
general-purpose replacement for the normal dispatchers. On the other hand it
may be quite useful to run your actor network on it for testing, because if it
runs without dead-locking chances are very high that it will not dead-lock in
production.

.. warning::

   The above sentence is unfortunately not a strong guarantee, because your
   code might directly or indirectly change its behavior when running on a
   different dispatcher. If you are looking for a tool to help you debug
   dead-locks, the :class:`CallingThreadDispatcher` may help with certain error
   scenarios, but keep in mind that it has may give false negatives as well as
   false positives.

Thread Interruptions
--------------------

If the CallingThreadDispatcher sees that the current thread has its
``isInterrupted()`` flag set when message processing returns, it will throw an
:class:`InterruptedException` after finishing all its processing (i.e. all
messages which need processing as described above are processed before this
happens). As :meth:`tell` cannot throw exceptions due to its contract, this
exception will then be caught and logged, and the thread’s interrupted status
will be set again.

If during message processing an :class:`InterruptedException` is thrown then it
will be caught inside the CallingThreadDispatcher’s message handling loop, the
thread’s interrupted flag will be set and processing continues normally.

.. note::

  The summary of these two paragraphs is that if the current thread is
  interrupted while doing work under the CallingThreadDispatcher, then that
  will result in the ``isInterrupted`` flag to be ``true`` when the message
  send returns and no :class:`InterruptedException` will be thrown.

Benefits
--------

To summarize, these are the features with the :class:`CallingThreadDispatcher`
has to offer:

 - Deterministic execution of single-threaded tests while retaining nearly full
   actor semantics
 - Full message processing history leading up to the point of failure in
   exception stack traces
 - Exclusion of certain classes of dead-lock scenarios

.. _actor.logging-java:

Tracing Actor Invocations
=========================

The testing facilities described up to this point were aiming at formulating
assertions about a system’s behavior. If a test fails, it is usually your job
to find the cause, fix it and verify the test again. This process is supported
by debuggers as well as logging, where the Akka toolkit offers the following
options:

* *Logging of exceptions thrown within Actor instances*

  This is always on; in contrast to the other logging mechanisms, this logs at
  ``ERROR`` level.

* *Logging of special messages*

  Actors handle certain special messages automatically, e.g. :obj:`Kill`,
  :obj:`PoisonPill`, etc. Tracing of these message invocations is enabled by
  the setting ``akka.actor.debug.autoreceive``, which enables this on all
  actors.

* *Logging of the actor lifecycle*

  Actor creation, start, restart, monitor start, monitor stop and stop may be traced by
  enabling the setting ``akka.actor.debug.lifecycle``; this, too, is enabled
  uniformly on all actors.

All these messages are logged at ``DEBUG`` level. To summarize, you can enable
full logging of actor activities using this configuration fragment::

  akka {
    loglevel = "DEBUG"
    actor {
      debug {
        autoreceive = on
        lifecycle = on
      }
    }
  }

Configuration
=============

There are several configuration properties for the TestKit module, please refer
to the :ref:`reference configuration <config-akka-testkit>`.

