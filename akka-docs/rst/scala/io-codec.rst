.. _io-scala-codec:

Encoding and decoding binary data
=================================

.. note::

  Previously Akka offered a specialized Iteratee implementation in the
  ``akka.actor.IO`` object which is now deprecated in favor of the pipeline
  mechanism described here. The documentation for Iteratees can be found `here
  <http://doc.akka.io/docs/akka/2.1.4/scala/io.html#Encoding_and_decoding_of_binary_data>`_.

.. warning::

  The IO implementation is marked as **“experimental”** as of its introduction
  in Akka 2.2.0. We will continue to improve this API based on our users’
  feedback, which implies that while we try to keep incompatible changes to a
  minimum the binary compatibility guarantee for maintenance releases does not
  apply to the contents of the `akka.io` package.

Akka adopted and adapted the implementation of data processing pipelines found
in the ``spray-io`` module. The idea is that encoding and decoding often
go hand in hand and keeping the code pertaining to one protocol layer together
is deemed more important than writing down the complete read side—say—in the
iteratee style in one go; pipelines encourage packaging the stages in a form
which lends itself better to reuse in a protocol stack. Another reason for
choosing this abstraction is that it is at times necessary to change the
behavior of encoding and decoding within a stage based on a message stream’s
state, and pipeline stages allow communication between the read and write
halves quite naturally.

The actual byte-fiddling can be done within pipeline stages, for example using
the rich API of :class:`ByteIterator` and :class:`ByteStringBuilder` as shown
below. All these activities are synchronous transformations which benefit
greatly from CPU affinity to make good use of those data caches. Therefore the
design of the pipeline infrastructure is completely synchronous, every stage’s
handler code can only directly return the events and/or commands resulting from
an input, there are no callbacks. Exceptions thrown within a pipeline stage
will abort processing of the whole pipeline under the assumption that
recoverable error conditions will be signaled in-band to the next stage instead
of raising an exception.

An overall “logical” pipeline can span multiple execution contexts, for example
starting with the low-level protocol layers directly within an actor handling
the reads and writes to a TCP connection and then being passed to a number of
higher-level actors which do the costly application level processing. This is
supported by feeding the generated events into a sink which sends them to
another actor, and that other actor will then upon reception feed them into its
own pipeline.

Introducing the Sample Protocol
-------------------------------

In the following the process of implementing a protocol stack using pipelines
is demonstrated on the following simple example:

.. code-block:: text

  frameLen: Int
  persons: Int
  persons times {
    first: String
    last: String
  }
  points: Int
  points times Double

mapping to the following data type:

.. includecode:: code/docs/io/Pipelines.scala#data

We will split the handling of this protocol into two parts: the frame-length
encoding handles the buffering necessary on the read side and the actual
encoding of the frame contents is done in a separate stage.

Building a Pipeline Stage
-------------------------

As a common example, which is also included in the ``akka-actor`` package, let
us look at a framing protocol which works by prepending a length field to each
message.

.. includecode:: ../../../akka-actor/src/main/scala/akka/io/Pipelines.scala
   :include: length-field-frame
   :exclude: range-checks-omitted

In the end a pipeline stage is nothing more than a set of three functions: one
transforming commands arriving from above, one transforming events arriving
from below and the third transforming incoming management commands (not shown
here, see below for more information). The result of the transformation can in
either case be a sequence of commands flowing downwards or events flowing
upwards (or a combination thereof).

In the case above the data type for commands and events are equal as both
functions operate only on ``ByteString``, and the transformation does not
change that type because it only adds or removes four octets at the front.

The pair of command and event transformation functions is represented by an
object of type :class:`PipePair`, or in this case a :class:`SymmetricPipePair`.
This object could benefit from knowledge about the context it is running in,
for example an :class:`Actor`, and this context is introduced by making a
:class:`PipelineStage` be a factory for producing a :class:`PipePair`. The
factory method is called :meth:`apply` (in good Scala tradition) and receives
the context object as its argument. The implementation of this factory method
could now make use of the context in whatever way it sees fit, you will see an
example further down.

Manipulating ByteStrings
------------------------

The second stage of our sample protocol stack illustrates in more depth what
showed only a little in the pipeline stage built above: constructing and
deconstructing byte strings. Let us first take a look at the encoder:

.. includecode:: code/docs/io/Pipelines.scala
   :include: format
   :exclude: decoding-omitted,omitted

Note how the byte order to be used by this stage is fixed in exactly one place,
making it impossible get wrong between commands and events; the way how the
byte order is passed into the stage demonstrates one possible use for the
stage’s ``context`` parameter. 

The basic tool for constucting a :class:`ByteString` is a
:class:`ByteStringBuilder` which can be obtained by calling
:meth:`ByteString.newBuilder` since byte strings implement the
:class:`IndexesSeq[Byte]` interface of the standard Scala collections. This
builder knows a few extra tricks, though, for appending byte representations of
the primitive data types like ``Int`` and ``Double`` or arrays thereof.
Encoding a ``String`` requires a bit more work because not only the sequence of
bytes needs to be encoded but also the length, otherwise the decoding stage
would not know where the ``String`` terminates. When all values making up the
:class:`Message` have been appended to the builder, we simply pass the
resulting :class:`ByteString` on to the next stage as a command using the
optimized :meth:`singleCommand` facility.

.. warning::

  The :meth:`singleCommand` and :meth:`singleEvent` methods provide a way to
  generate responses which transfer exactly one result from one pipeline stage
  to the next without suffering the overhead of object allocations. This means
  that the returned collection object will not work for anything else (you will
  get :class:`ClassCastExceptions`!) and this facility can only be used *EXACTLY
  ONCE* during the processing of one input (command or event).

Now let us look at the decoder side:

.. includecode:: code/docs/io/Pipelines.scala
   :include: decoding

The decoding side does the same things that the encoder does in the same order,
it just uses a :class:`ByteIterator` to retrieve primitive data types or arrays
of those from the underlying :class:`ByteString`. And in the end it hands the
assembled :class:`Message` as an event to the next stage using the optimized
:meth:`singleEvent` facility (see warning above).

Building a Pipeline
-------------------

Given the two pipeline stages introduced in the sections above we can now put
them to some use. First we define some message to be encoded:

.. includecode:: code/docs/io/Pipelines.scala
   :include: message

Then we need to create a pipeline context which satisfies our declared needs:

.. includecode:: code/docs/io/Pipelines.scala
   :include: byteorder

Building the pipeline and encoding this message then is quite simple:

.. includecode:: code/docs/io/Pipelines.scala
   :include: build-pipeline

The tuple returned from :meth:`buildFunctionTriple` contains one function for
injecting commands, one for events and a third for injecting management
commands (see below). In this case we demonstrate how a single message ``msg``
is encoded by passing it into the ``cmd`` function. The return value is a pair
of sequences, one for the resulting events and the other for the resulting
commands. For the sample pipeline this will contain exactly one command—one
:class:`ByteString`. Decoding works in the same way, only with the ``evt``
function (which can again also result in commands being generated, although
that is not demonstrated in this sample).

Besides the more functional style there is also an explicitly side-effecting one:

.. includecode:: code/docs/io/Pipelines.scala
   :include: build-sink

The functions passed into the :meth:`buildWithSinkFunctions` factory method
describe what shall happen to the commands and events as they fall out of the
pipeline. In this case we just send those to some actors, since that is usually
quite a good strategy for distributing the work represented by the messages.

The types of commands or events fed into the provided sink functions are
wrapped within :class:`Try` so that failures can also be encoded and acted
upon. This means that injecting into a pipeline using a
:class:`PipelineInjector` will catch exceptions resulting from processing the
input, in which case the exception (there can only be one per injection) is
passed into the respective sink.

Using the Pipeline’s Context
----------------------------

Up to this point there was always a parameter ``ctx`` which was used when
constructing a pipeline, but it was not explained in full. The context is a
piece of information which is made available to all stages of a pipeline. The
context may also carry behavior, provide infrastructure or helper methods etc.
It should be noted that the context is bound to the pipeline and as such must
not be accessed concurrently from different threads unless care is taken to
properly synchronize such access. Since the context will in many cases be
provided by an actor it is not recommended to share this context with code
executing outside of the actor’s message handling.

.. warning::

  A PipelineContext instance *MUST NOT* be used by two different pipelines
  since it contains mutable fields which are used during message processing.

Using Management Commands
-------------------------

Since pipeline stages do not have any reference to the pipeline or even to
their neighbors they cannot directly effect the injection of commands or events
outside of their normal processing. But sometimes things need to happen driven
by a timer, for example. In this case the timer would need to cause sending
tick messages to the whole pipeline, and those stages which wanted to receive
them would act upon those. In order to keep the type signatures for events and
commands useful, such external triggers are sent out-of-band, via a different
channel—the management port. One example which makes use of this facility is
the :class:`TickGenerator` which comes included with ``akka-actor``:

.. includecode:: ../../../akka-actor/src/main/scala/akka/io/Pipelines.scala
   :include: tick-generator

This pipeline stage is to be used within an actor, and it will make use of this
context in order to schedule the delivery of :class:`TickGenerator.Trigger`
messages; the actor is then supposed to feed these messages into the management
port of the pipeline.  An example could look like this:

.. includecode:: code/docs/io/Pipelines.scala#actor

This actor extends our well-known pipeline with the tick generator and attaches
the outputs to functions which send commands and events to actors for further
processing. The pipeline stages will then all receive one ``Tick`` per second
which can be used like so:

.. includecode:: code/docs/io/Pipelines.scala
   :include: mgmt-ticks
   :exclude: omitted

.. note::

  Management commands are delivered to all stages of a pipeline “effectively
  parallel”, like on a broadcast medium. No code will actually run concurrently
  since a pipeline is strictly single-threaded, but the order in which these
  commands are processed is not specified.

The intended purpose of management commands is for each stage to define its
special command types and then listen only to those (where the aforementioned
``Tick`` message is a useful counter-example), exactly like sending packets on
a wifi network where every station receives all traffic but reacts only to
those messages which are destined for it.

If you need all stages to react upon something in their defined order, then
this must be modeled either as a command or event, i.e. it will be part of the
“business” type of the pipeline.

