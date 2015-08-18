.. _receive-pipeline:

Receive Pipeline Pattern
========================
The Receive Pipeline Pattern lets you define general interceptors for your messages
and plug an arbitrary amount of them into your Actors.
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
Multiple interceptors can be added to actors that mixin the :class:`ReceivePipeline` trait. 
These interceptors internally define layers of decorators around the actor's behavior. The first interceptor
defines an outer decorator which delegates to a decorator corresponding to the second interceptor and so on, 
until the last interceptor which defines a decorator for the actor's :class:`Receive`. 

The first or outermost interceptor receives messages sent to the actor. 

For each message received by an interceptor, the interceptor will typically perform some 
processing based on the message and decide whether 
or not to pass the received message (or some other message) to the next interceptor.

An :class:`Interceptor` is a type alias for
:class:`PartialFunction[Any, Delegation]`. The :class:`Any` input is the message
it receives from the previous interceptor (or, in the case of the first interceptor,
the message that was sent to the actor).    
The :class:`Delegation` return type is used to control what (if any) 
message is passed on to the next interceptor.

A simple example
----------------
To pass a transformed message to the actor 
(or next inner interceptor) an interceptor can return :class:`Inner(newMsg)` where :class:`newMsg` is the transformed message.

The following interceptor shows this. It intercepts :class:`Int` messages, 
adds one to them and passes on the incremented value to the next interceptor. 

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/ReceivePipelineSpec.scala#interceptor-sample1

Building the Pipeline
---------------------
To give your Actor the ability to pipeline received messages in this way, you'll need to mixin with the
:class:`ReceivePipeline` trait. It has two methods for controlling the pipeline, :class:`pipelineOuter`
and :class:`pipelineInner`, both receiving an :class:`Interceptor`. 
The first one adds the interceptor at the
beginning of the pipeline and the second one adds it at the end, just before the current
Actor's behavior.

In this example we mixin our Actor with the :class:`ReceivePipeline` trait and
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

and these two interceptors defined, each one in its own trait: 

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/ReceivePipelineSpec.scala#mixin-interceptors

The first one intercepts any messages having
an internationalized text and replaces it with the resolved text before resuming with the chain. The second one
intercepts any message with an author defined and prints it before resuming the chain with the message unchanged.
But since :class:`I18n` adds the interceptor with :class:`pipelineInner` and :class:`Audit` adds it with
:class:`pipelineOuter`, the audit will happen before the internationalization.

So if we mixin both interceptors in our Actor, we will see the following output for these example messages:

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/ReceivePipelineSpec.scala#mixin-actor

Unhandled Messages
------------------
With all that behaviors chaining occurring, what happens to unhandled messages? Let me explain it with
a simple rule.

.. note::
  Every message not handled by an interceptor will be passed to the next one in the chain. If none
  of the interceptors handles a message, the current Actor's behavior will receive it, and if the
  behavior doesn't handle it either, it will be treated as usual with the unhandled method.

But sometimes it is desired for interceptors to break the chain. You can do it by explicitly indicating 
that the message has been completely handled by the interceptor by returning 
:class:`HandledCompletely`.

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/ReceivePipelineSpec.scala#unhandled

Processing after delegation
---------------------------
But what if you want to perform some action after the actor has processed the message (for example to 
measure the message processing time)?

In order to support such use cases, the :class:`Inner` return type has a method :class:`andAfter` which accepts 
a code block that can perform some action after the message has been processed by subsequent inner interceptors.

The following is a simple interceptor that times message processing:

.. includecode:: @contribSrc@/src/test/scala/akka/contrib/pattern/ReceivePipelineSpec.scala#interceptor-after

.. note::
  The :class:`andAfter` code blocks are run on return from handling the message with the next inner handler and 
  on the same thread. It is therefore safe for the :class:`andAfter` logic to close over the interceptor's state.

Using Receive Pipelines with Persistence
----------------------------------------

When using ``ReceivePipeline`` together with :ref:`PersistentActor<persistence-scala>` make sure to
mix-in the traits in the following order for them to properly co-operate::

    class ExampleActor extends PersistentActor with ReceivePipeline {
      /* ... */
    }

The order is important here because of how both traits use internal "around" methods to implement their features,
and if mixed-in the other way around it would not work as expected. If you want to learn more about how exactly this
works, you can read up on Scala's `type linearization mechanism`_;

.. _type linearization mechanism: http://ktoso.github.io/scala-types-of-types/#type-linearization-vs-the-diamond-problem