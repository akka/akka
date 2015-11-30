.. _stream-customize-java:

########################
Custom stream processing
########################

While the processing vocabulary of Akka Streams is quite rich (see the :ref:`stream-cookbook-java` for examples) it
is sometimes necessary to define new transformation stages either because some functionality is missing from the
stock operations, or for performance reasons. In this part we show how to build custom processing stages and graph
junctions of various kinds.

.. _graphstage-java:

Custom processing with GraphStage
=================================

The :class:`GraphStage` abstraction can be used to create arbitrary graph processing stages with any number of input
or output ports. It is a counterpart of the ``FlowGraph.create()`` method which creates new stream processing
stages by composing  others. Where :class:`GraphStage` differs is that it creates a stage that is itself not divisible into
smaller ones, and allows state to be maintained inside it in a safe way.

As a first motivating example, we will build a new :class:`Source` that will simply emit numbers from 1 until it is
cancelled. To start, we need to define the "interface" of our stage, which is called *shape* in Akka Streams terminology
(this is explained in more detail in the section :ref:`composition-java`).

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/GraphStageDocTest.java#simple-source

As you see, in itself the :class:`GraphStage` only defines the ports of this stage and a shape that contains the ports.
It also has a user implemented method called ``createLogic``. If you recall, stages are reusable in multiple
materializations, each resulting in a different executing entity. In the case of :class:`GraphStage` the actual running
logic is modeled as an instance of a :class:`GraphStageLogic` which will be created by the materializer by calling
the ``createLogic`` method.

In order to emit from a :class:`Source` in a backpressured stream one needs first to have demand from downstream.
To receive the necessary events one needs to register a subclass of :class:`AbstractOutHandler` with the output port
(:class:`Outlet`). This handler will receive events related to the lifecycle of the port. In our case we need to
override ``onPull()`` which indicates that we are free to emit a single element. There is another callback,
``onDownstreamFinish()`` which is called if the downstream cancelled. Since the default behavior of that callback is
to stop the stage, we don't need to override it. In the ``onPull`` callback we simply emit the next number.

Instances of the above :class:`GraphStage` are subclasses of ``Graph<SourceShape<Int>,Unit>`` which means
that they are already usable in many situations, but do not provide the DSL methods we usually have for other
:class:`Source` s. In order to convert this :class:`Graph` to a proper :class:`Source` we need to wrap it using
``Source.fromGraph`` (see :ref:`composition-java` for more details about graphs and DSLs). Now we can use the
source as any other built-in one:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/GraphStageDocTest.java#simple-source-usage

Port states, AbstractInHandler and AbstractOutHandler
-----------------------------------------------------

In order to interact with a port (:class:`Inlet` or :class:`Outlet`) of the stage we need to be able to receive events
and generate new events belonging to the port. From the :class:`GraphStageLogic` the following operations are available
on an output port:

* ``push(out,elem)`` pushes an element to the output port. Only possible after the port has been pulled by downstream.
* ``complete(out)`` closes the output port normally.
* ``fail(out,exception)`` closes the port with a failure signal.


The events corresponding to an *output* port can be received in an :class:`AbstractOutHandler` instance registered to the
output port using ``setHandler(out,handler)``. This handler has two callbacks:

* ``onPull()`` is called when the output port is ready to emit the next element, ``push(out, elem)`` is now allowed
  to be called on this port.
* ``onDownstreamFinish()`` is called once the downstream has cancelled and no longer allows messages to be pushed to it.
  No more ``onPull()`` will arrive after this event. If not overridden this will default to stopping the stage.

Also, there are two query methods available for output ports:

* ``isAvailable(out)`` returns true if the port can be pushed.
* ``isClosed(out)`` returns true if the port is closed. At this point the port can not be pushed and will not be pulled anymore.

The relationship of the above operations, events and queries are summarized in the state machine below. Green shows
the initial state while orange indicates the end state. If an operation is not listed for a state, then it is invalid
to call it while the port is in that state. If an event is not listed for a state, then that event cannot happen
in that state.

|

.. image:: ../images/outport_transitions.png
:align: center

|

The following operations are available for *input* ports:

* ``pull(in)`` requests a new element from an input port. This is only possible after the port has been pushed by upstream.
* ``grab(in)`` acquires the element that has been received during an ``onPush()``. It cannot be called again until the
  port is pushed again by the upstream.
* ``cancel(in)`` closes the input port.

The events corresponding to an *input* port can be received in an :class:`AbstractInHandler` instance registered to the
input port using ``setHandler(in, handler)``. This handler has three callbacks:

* ``onPush()`` is called when the output port has now a new element. Now it is possible to aquire this element using
  ``grab(in)`` and/or call ``pull(in)`` on the port to request the next element. It is not mandatory to grab the
  element, but if it is pulled while the element has not been grabbed it will drop the buffered element.
* ``onUpstreamFinish()`` is called once the upstream has completed and no longer can be pulled for new elements.
  No more ``onPush()`` will arrive after this event. If not overridden this will default to stopping the stage.
* ``onUpstreamFailure()`` is called if the upstream failed with an exception and no longer can be pulled for new elements.
  No more ``onPush()`` will arrive after this event. If not overridden this will default to failing the stage.

Also, there are three query methods available for input ports:

* ``isAvailable(in)`` returns true if a data element can be grabbed from the port
* ``hasBeenPulled(in)`` returns true if the port has been already pulled. Calling ``pull(in)`` in this state is illegal.
* ``isClosed(in)`` returns true if the port is closed. At this point the port can not be pulled and will not be pushed anymore.

The relationship of the above operations, events and queries are summarized in the state machine below. Green shows
the initial state while orange indicates the end state. If an operation is not listed for a state, then it is invalid
to call it while the port is in that state. If an event is not listed for a state, then that event cannot happen
in that state.

|

.. image:: ../images/inport_transitions.png
:align: center

|

Finally, there are two methods available for convenience to complete the stage and all of its ports:

* ``completeStage()`` is equivalent to closing all output ports and cancelling all input ports.
* ``failStage(exception)`` is equivalent to failing all output ports and cancelling all input ports.


Completion
----------

**This section is a stub and will be extended in the next release**

Stages by default automatically stop once all of their ports (input and output) have been closed externally or internally.
It is possible to opt out from this behavior by overriding ``keepGoingAfterAllPortsClosed`` and returning true in
the :class:`GraphStageLogic` implementation. In this case the stage **must** be explicitly closed by calling ``completeStage()``
or ``failStage(exception)``. This feature carries the risk of leaking streams and actors, therefore it should be used
with care.

Using timers
------------

**This section is a stub and will be extended in the next release**

It is possible to use timers in :class:`GraphStages` by using :class:`TimerGraphStageLogic` as the base class for
the returned logic. Timers can be scheduled by calling one of ``scheduleOnce(key,delay)``, ``schedulePeriodically(key,period)`` or
``schedulePeriodicallyWithInitialDelay(key,delay,period)`` and passing an object as a key for that timer (can be any object, for example
a :class:`String`). The ``onTimer(key)`` method needs to be overridden and it will be called once the timer of ``key``
fires. It is possible to cancel a timer using ``cancelTimer(key)`` and check the status of a timer with
``isTimerActive(key)``. Timers will be automatically cleaned up when the stage completes.

Timers can not be scheduled from the constructor of the logic, but it is possible to schedule them from the
``preStart()`` lifecycle hook.

Using asynchronous side-channels
--------------------------------

**This section is a stub and will be extended in the next release**

In order to receive asynchronous events that are not arriving as stream elements (for example a completion of a future
or a callback from a 3rd party API) one must acquire a :class:`AsyncCallback` by calling ``getAsyncCallback()`` from the
stage logic. The method ``getAsyncCallback`` takes as a parameter a callback that will be called once the asynchronous
event fires. It is important to **not call the callback directly**, instead, the external API must call the
``invoke(event)`` method on the returned :class:`AsyncCallback`. The execution engine will take care of calling the
provided callback in a thread-safe way. The callback can safely access the state of the :class:`GraphStageLogic`
implementation.

Sharing the AsyncCallback from the constructor risks race conditions, therefore it is recommended to use the
``preStart()`` lifecycle hook instead.

Integration with actors
-----------------------

**This section is a stub and will be extended in the next release**
**This is an experimental feature***

It is possible to acquire an ActorRef that can be addressed from the outside of the stage, similarly how
:class:`AsyncCallback` allows injecting asynchronous events into a stage logic. This reference can be obtained
by calling ``getStageActorRef(receive)`` passing in a function that takes a :class:`Pair` of the sender
:class:`ActorRef` and the received message. This reference can be used to watch other actors by calling its ``watch(ref)``
or ``unwatch(ref)`` methods. The reference can be also watched by external actors. The current limitations of this
:class:`ActorRef` are:

 - they are not location transparent, they cannot be accessed via remoting.
 - they cannot be returned as materialized values.
 - they cannot be accessed from the constructor of the :class:`GraphStageLogic`, but they can be accessed from the
   ``preStart()`` method.

Custom materialized values
--------------------------

**This section is a stub and will be extended in the next release**

Custom stages can return materialized values instead of ``Unit`` by inheriting from :class:`GraphStageWithMaterializedValue`
instead of the simpler :class:`GraphStage`. The difference is that in this case the method
``createLogicAndMaterializedValue(inheritedAttributes)`` needs to be overridden, overridden, and in addition to the
stage logic the materialized value must be provided

.. warning::
   There is no built-in synchronization of accessing this value from both of the thread where the logic runs and
   the thread that got hold of the materialized value. It is the responsibility of the programmer to add the
   necessary (non-blocking) synchronization and visibility guarantees to this shared object.

Using attributes to affect the behavior of a stage
--------------------------------------------------

**This section is a stub and will be extended in the next release**

Stages can access the :class:`Attributes` object created by the materializer. This contains all the applied (inherited)
attributes applying to the stage, ordered from least specific (outermost) towards the most specific (innermost)
attribute. It is the responsibility of the stage to decide how to reconcile this inheritance chain to a final effective
decision.

See :ref:`composition-java` for an explanation on how attributes work.


Custom linear processing stages
===============================

To extend the available transformations on a :class:`Flow` or :class:`Source` one can use the ``transform()`` method
which takes a factory function returning a :class:`Stage`. Stages come in different flavors swhich we will introduce in this
page.

.. _stream-using-push-pull-stage-java:

Using PushPullStage
-------------------

The most elementary transformation stage is the :class:`PushPullStage` which can express a large class of algorithms
working on streams. A :class:`PushPullStage` can be illustrated as a box with two "input" and two "output ports" as it is
seen in the illustration below.

|

.. image:: ../images/stage_conceptual.png
   :align: center
   :width: 600

|

The "input ports" are implemented as event handlers ``onPush(elem,ctx)`` and ``onPull(ctx)`` while "output ports"
correspond to methods on the :class:`Context` object that is handed as a parameter to the event handlers. By calling
exactly one "output port" method we wire up these four ports in various ways which we demonstrate shortly.

.. warning::
   There is one very important rule to remember when working with a ``Stage``. **Exactly one** method should be called
   on the **currently passed** :class:`Context` **exactly once** and as the **last statement of the handler** where the return type
   of the called method **matches the expected return type of the handler**. Any violation of this rule will
   almost certainly result in unspecified behavior (in other words, it will break in spectacular ways). Exceptions
   to this rule are the query methods ``isHolding()`` and ``isFinishing()``

To illustrate these concepts we create a small :class:`PushPullStage` that implements the ``map`` transformation.

|

.. image:: ../images/stage_map.png
   :align: center
   :width: 300

|

Map calls ``ctx.push()`` from the ``onPush()`` handler and it also calls ``ctx.pull()`` form the ``onPull``
handler resulting in the conceptual wiring above, and fully expressed in code below:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/FlowStagesDocTest.java#one-to-one

Map is a typical example of a one-to-one transformation of a stream. To demonstrate a many-to-one stage we will implement
filter. The conceptual wiring of ``Filter`` looks like this:

|

.. image:: ../images/stage_filter.png
   :align: center
   :width: 300

|

As we see above, if the given predicate matches the current element we are propagating it downwards, otherwise
we return the "ball" to our upstream so that we get the new element. This is achieved by modifying the map
example by adding a conditional in the ``onPush`` handler and decide between a ``ctx.pull()`` or ``ctx.push()`` call
(and of course not having a mapping ``f`` function).

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/FlowStagesDocTest.java#many-to-one

To complete the picture we define a one-to-many transformation as the next step. We chose a straightforward example stage
that emits every upstream element twice downstream. The conceptual wiring of this stage looks like this:

|

.. image:: ../images/stage_doubler.png
   :align: center
   :width: 300

|

This is a stage that has state: the last element it has seen, and a flag ``oneLeft`` that indicates if we
have duplicated this last element already or not. Looking at the code below, the reader might notice that our ``onPull``
method is more complex than it is demonstrated by the figure above. The reason for this is completion handling, which we
will explain a little bit later. For now it is enough to look at the ``if(!ctx.isFinishing)`` block which
corresponds to the logic we expect by looking at the conceptual picture.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/FlowStagesDocTest.java#one-to-many

Finally, to demonstrate all of the stages above, we put them together into a processing chain, which conceptually
would correspond to the following structure:

|

.. image:: ../images/stage_chain.png
   :align: center
   :width: 650

|

In code this is only a few lines, using the ``transform`` method to inject our custom processing into a stream:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/FlowStagesDocTest.java#stage-chain

If we attempt to draw the sequence of events, it shows that there is one "event token"
in circulation in a potential chain of stages, just like our conceptual "railroad tracks" representation predicts.

|

.. image:: ../images/stage_msc_general.png
   :align: center

|

Completion handling
^^^^^^^^^^^^^^^^^^^

Completion handling usually (but not exclusively) comes into the picture when processing stages need to emit a few
more elements after their upstream source has been completed. We have seen an example of this in our ``Duplicator`` class
where the last element needs to be doubled even after the upstream neighbor stage has been completed. Since the
``onUpstreamFinish()`` handler expects a :class:`TerminationDirective` as the return type we are only allowed to call
``ctx.finish()``, ``ctx.fail()`` or ``ctx.absorbTermination()``. Since the first two of these available methods will
immediately terminate, our only option is ``absorbTermination()``. It is also clear from the return type of
``onUpstreamFinish`` that we cannot call ``ctx.push()`` but we need to emit elements somehow! The trick is that after
calling ``absorbTermination()`` the ``onPull()`` handler will be called eventually, and at the same time
``ctx.isFinishing`` will return true, indicating that ``ctx.pull()`` cannot be called anymore. Now we are free to
emit additional elementss and call ``ctx.finish()`` or ``ctx.pushAndFinish()`` eventually to finish processing.

The reason for this slightly complex termination sequence is that the underlying ``onComplete`` signal of
Reactive Streams may arrive without any pending demand, i.e. without respecting backpressure. This means that
our push/pull structure that was illustrated in the figure of our custom processing chain does not
apply to termination. Our neat model that is analogous to a ball that bounces back-and-forth in a
pipe (it bounces back on ``Filter``, ``Duplicator`` for example) cannot describe the termination signals. By calling
``absorbTermination()`` the execution environment checks if the conceptual token was *above* the current stage at
that time (which means that it will never come back, so the environment immediately calls ``onPull``) or it was
*below* (which means that it will come back eventually, so the environment does not need to call anything yet).

The first of the two scenarios is when a termination signal arrives after a stage passed the event to its downstream. As
we can see in the following diagram, there is no need to do anything by ``absorbTermination()`` since the black arrows
representing the movement of the "event token" is uninterrupted.

|

.. image:: ../images/stage_msc_absorb_1.png
   :align: center

|

In the second scenario the "event token" is somewhere upstream when the termination signal arrives. In this case
``absorbTermination`` needs to ensure that a new "event token" is generated replacing the old one that is forever gone
(since the upstream finished). This is done by calling the ``onPull()`` event handler of the stage.

|

.. image:: ../images/stage_msc_absorb_2.png
   :align: center

|

Observe, that in both scenarios ``onPull()`` kicks off the continuation of the processing logic, the only difference is
whether it is the downstream or the ``absorbTermination()`` call that calls the event handler.

.. warning::
  It is not allowed to call ``absorbTermination()`` from ``onDownstreamFinish()``. If the method is called anyway,
  it will be logged at ``ERROR`` level, but no further action will be taken as at that point there is no active
  downstream to propagate the error to. Cancellation in the upstream direction will continue undisturbed.

Using PushStage
---------------

Many one-to-one and many-to-one transformations do not need to override the ``onPull()`` handler at all since all
they do is just propagate the pull upwards. For such transformations it is better to extend PushStage directly. For
example our ``Map`` and ``Filter`` would look like this:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/FlowStagesDocTest.java#pushstage

The reason to use ``PushStage`` is not just cosmetic: internal optimizations rely on the fact that the onPull method
only calls ``ctx.pull()`` and allow the environment do process elements faster than without this knowledge. By
extending ``PushStage`` the environment can be sure that ``onPull()`` was not overridden since it is ``final`` on
``PushStage``.

Using DetachedStage
-------------------

The model described in previous sections, while conceptually simple, cannot describe all desired stages. The main
limitation is the "single-ball" (single "event token") model which prevents independent progress of an upstream and
downstream of a stage. Sometimes it is desirable to *detach* the progress (and therefore, rate) of the upstream and
downstream of a stage, synchronizing only when needed.

This is achieved in the model by representing a :class:`DetachedStage` as a *boundary* between two "single-ball" regions.
One immediate consequence of this difference is that **it is not allowed to call** ``ctx.pull()`` **from** ``onPull()`` **and
it is not allowed to call** ``ctx.push()`` **from** ``onPush()`` as such combinations would "steal" a token from one region
(resulting in zero tokens left) and would inject an unexpected second token to the other region. This is enforced
by the expected return types of these callback functions.

One of the important use-cases for :class:`DetachedStage` is to build buffer-like entities, that allow independent progress
of upstream and downstream stages when the buffer is not full or empty, and slowing down the appropriate side if the
buffer becomes empty or full. The next diagram illustrates the event sequence for a buffer with capacity of two elements.

|

.. image:: ../images/stage_msc_buffer.png
  :align: center

|

The very first difference we can notice is that our ``Buffer`` stage is automatically pulling its upstream on
initialization. Remember that it is forbidden to call ``ctx.pull`` from ``onPull``, therefore it is the task of the
framework to kick off the first "event token" in the upstream region, which will remain there until the upstream stages
stop. The diagram distinguishes between the actions of the two regions by colors: *purple* arrows indicate the actions
involving the upstream "event token", while *red* arrows show the downstream region actions. This demonstrates the clear
separation of these regions, and the invariant that the number of tokens in the two regions are kept unchanged.

For buffer it is necessary to detach the two regions, but it is also necessary to sometimes hold back the upstream
or downstream. The new API calls that are available for :class:`DetachedStage` s are the various ``ctx.holdXXX()`` methods
, ``ctx.pushAndPull()`` and variants, and ``ctx.isHoldingXXX()``.
Calling ``ctx.holdXXX()`` from ``onPull()`` or ``onPush`` results in suspending the corresponding
region from progress, and temporarily taking ownership of the "event token". This state can be queried by ``ctx.isHolding()``
which will tell if the stage is currently holding a token or not. It is only allowed to suspend one of the regions, not
both, since that would disable all possible future events, resulting in a dead-lock. Releasing the held token is only
possible by calling ``ctx.pushAndPull()``. This is to ensure that both the held token is released, and the triggering region
gets its token back (one inbound token + one held token = two released tokens).

The following code example demonstrates the buffer class corresponding to the message sequence chart we discussed.

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/FlowStagesDocTest.java#detached

.. warning::
  If ``absorbTermination()`` is called on a :class:`DetachedStage` while it holds downstream (``isHoldingDownstream``
  returns true) then ``onPull()`` will be called on the stage. This ensures that the stage does not end up in a
  deadlocked case. Since at the point when the termination is absorbed there will be no way to get any callbacks because
  the downstream is held, so the framework invokes onPull() to avoid this situation. This is similar to the termination
  logic already shown for :class:`PushPullStage`.

Thread safety of custom processing stages
=========================================

All of the above custom stages (linear or graph) provide a few simple guarantees that implementors can rely on.
 - The callbacks exposed by all of these classes are never called concurrently.
 - The state encapsulated by these classes can be safely modified from the provided callbacks, without any further
   synchronization.

In essence, the above guarantees are similar to what :class:`Actor` s provide, if one thinks of the state of a custom
stage as state of an actor, and the callbacks as the ``receive`` block of the actor.

.. warning::
  It is **not safe** to access the state of any custom stage outside of the callbacks that it provides, just like it
  is unsafe to access the state of an actor from the outside. This means that Future callbacks should **not close over**
  internal state of custom stages because such access can be concurrent with the provided callbacks, leading to undefined
  behavior.
