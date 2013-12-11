.. _receive-pipeline:

Receive Pipeline Pattern
========================
Receive Pipeline Pattern lets you define general interceptors for your messages
and plug any arbitrary amount of them to your Actors.
It's useful for defining cross aspects to be applied to all or many of your Actors.

Some Possible Use Cases
-----------------------
* Measure the time spent for processing the messages
* Audit messages with an  associated author
* Log all important messages
* Secure restricted messages
* Text internationalization

Interceptors
------------
So how does an interceptor look? Well, Interceptors are defined by decorator functions
of type :class:`Receive => Receive`, where it gets the inner :class:`Receive` by parameter and
returns a new :class:`Receive` with the decoration applied.
Most of the times your decorators will be defined as a regular :class:`Receive` with cases
for the messages of your interest and at some point delegate on the inner :class:`Receive`
you get by parameter. We will talk about ignored and unhandled messages later.

A simple example
----------------
We have the simplest :class:`Receive` possible, it just prints any type of message, and we decorate
it to create an interceptor that increments :class:`Int` messages and delegates the result to the
inner (printer) :class:`Receive`.

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/ReceivePipelineSpec.scala#interceptor

Building the Pipeline
---------------------
So we're done defining decorators which will create the interceptors. Now we will see
how to plug them into the Actors to construct the pipeline.

To give your Actor the ability to pipeline the receive, you'll need to mixin with the
:class:`ReceivePipeline` trait. It has two methods for controlling the pipeline, :class:`pipelineOuter`
and :class:`pipelineInner`, both receiving a decorator function. The first one adds the interceptor at the
beginning of the pipeline and the second one adds it at the end, just before the current
Actor's behavior.

In this example we mixin our Actor with :class:`ReceivePipeline` trait and
we add :class:`Increment` and :class:`Double` interceptors with :class:`pipelineInner`.
They will be applied in this very order.

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/ReceivePipelineSpec.scala#in-actor

If we add :class:`Double` with :class:`pipelineOuter` it will be applied before :class:`Increment` so the output is 11

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/ReceivePipelineSpec.scala#in-actor-outer

Interceptors Mixin
------------------
Defining all the pipeline inside the Actor implementation is good for showing up the pattern, but it isn't
very practical. The real flexibility of this pattern comes when you define every interceptor in its own
trait and then you mixin any of them into your Actors.

Let's see it in an example. We have the following model:

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/ReceivePipelineSpec.scala#mixin-model

And this two interceptors defined each one in its own trait. The first one intercepts any messages having
an internationalized text and replaces it with the resolved text before resuming with the chain. The second one
intercepts any message with an author defined and prints it before resuming the chain with the message unchanged.
But since :class:`I18n` adds the interceptor with :class:`pipelineInner` and :class:`Audit` adds it with
:class:`pipelineOuter`, the audit will happen before the internationalization.

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/ReceivePipelineSpec.scala#mixin-interceptors

So if we mixin both interceptors in our Actor, we will see the following output for these example messages.

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/ReceivePipelineSpec.scala#mixin-actor

Unhandled Messages
------------------
With all that behaviors chaining occurring on, what happens to unhandled messages? Let me explain it with
a simple rule.

.. note::
  Every message not handled by an interceptor will be passed to the next one in the chain. If none
  of the interceptors handles a message, the current Actor's behavior will receive it, and if the
  behavior doesn't handle it either, it will be treated as usual with the unhandled method.

But some times it is desired for interceptors to break the chain. You can do it by explicitly ignoring
the messages with empty cases or just not calling the inner interceptor received by parameter.

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/ReceivePipelineSpec.scala#unhandled

