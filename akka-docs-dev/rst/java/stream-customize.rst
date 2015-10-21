.. _stream-customize-java:

########################
Custom stream processing
########################

While the processing vocabulary of Akka Streams is quite rich (see the :ref:`stream-cookbook-java` for examples) it
is sometimes necessary to define new transformation stages either because some functionality is missing from the
stock operations, or for performance reasons. In this part we show how to build custom processing stages and graph
junctions of various kinds.

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


Using StatefulStage
-------------------

On top of ``PushPullStage`` which is the most elementary and low-level abstraction and ``PushStage`` that is a
convenience class that also informs the environment about possible optimizations ``StatefulStage`` is a new tool that
builds on ``PushPullStage`` directly, adding various convenience methods on top of it. It is possible to explicitly
maintain state-machine like states using its ``become()`` method to encapsulates states explicitly. There is also
a handy ``emit()`` method that simplifies emitting multiple values given as an iterator. To demonstrate this feature
we reimplemented ``Duplicator`` in terms of a ``StatefulStage``:

.. includecode:: ../../../akka-samples/akka-docs-java-lambda/src/test/java/docs/stream/FlowStagesDocTest.java#doubler-stateful

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

Custom graph processing junctions
=================================

To extend available fan-in and fan-out structures (graph stages) Akka Streams include :class:`GraphStage`. This is an
advanced usage DSL that should only be needed in rare and special cases, documentation will be forthcoming in one of the
next releases.

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
