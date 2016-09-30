.. _stages-overview_scala:

Overview of built-in stages and their semantics
===============================================


Source stages
-------------
These built-in sources are available from ``akka.stream.scaladsl.Source``:



fromIterator
^^^^^^^^^^^^
Stream the values from an ``Iterator``, requesting the next value when there is demand. The iterator will be created anew
for each materialization, which is the reason the method takes a function rather than an iterator directly.

If the iterator perform blocking operations, make sure to run it on a separate dispatcher.

**emits** the next value returned from the iterator

**completes** when the iterator reaches its end

apply
^^^^^
Stream the values of an ``immutable.Seq``.

**emits** the next value of the seq

**completes** when the last element of the seq has been emitted


single
^^^^^^
Stream a single object

**emits** the value once

**completes** when the single value has been emitted

repeat
^^^^^^
Stream a single object repeatedly

**emits** the same value repeatedly when there is demand

**completes** never

cycle
^^^^^
Stream iterator in cycled manner. Internally new iterator is being created to cycle the one provided via argument meaning
when original iterator runs out of elements process will start all over again from the beginning of the iterator
provided by the evaluation of provided parameter. If method argument provides empty iterator stream will be terminated with
exception.

**emits** the next value returned from cycled iterator

**completes** never

tick
^^^^
A periodical repetition of an arbitrary object. Delay of first tick is specified
separately from interval of the following ticks.

**emits** periodically, if there is downstream backpressure ticks are skipped

**completes** never

fromFuture
^^^^^^^^^^
Send the single value of the ``Future`` when it completes and there is demand.
If the future fails the stream is failed with that exception.

**emits** the future completes

**completes** after the future has completed

fromCompletionStage
^^^^^^^^^^^^^^^^^^^
Send the single value of the Java ``CompletionStage`` when it completes and there is demand.
If the future fails the stream is failed with that exception.

**emits** the future completes

**completes** after the future has completed


unfold
^^^^^^
Stream the result of a function as long as it returns a ``Some``, the value inside the option
consists of a tuple where the first value is a state passed back into the next call to the function allowing
to pass a state. The first invocation of the provided fold function will receive the ``zero`` state.

Can be used to implement many stateful sources without having to touch the more low level ``GraphStage`` API.

**emits** when there is demand and the unfold function over the previous state returns non empty value

**completes** when the unfold function returns an empty value

unfoldAsync
^^^^^^^^^^^
Just like ``unfold`` but the fold function returns a ``Future`` which will cause the source to
complete or emit when it completes.

Can be used to implement many stateful sources without having to touch the more low level ``GraphStage`` API.

**emits** when there is demand and unfold state returned future completes with some value

**completes** when the future returned by the unfold function completes with an empty value

empty
^^^^^
Complete right away without ever emitting any elements. Useful when you have to provide a source to
an API but there are no elements to emit.

**emits** never

**completes** directly

maybe
^^^^^
Materialize a ``Promise[Option[T]]`` that if completed with a ``Some[T]`` will emit that `T` and then complete
the stream, or if completed with ``None`` complete the stream right away.

**emits** when the returned promise is completed with some value

**completes** after emitting some value, or directly if the promise is completed with no value

failed
^^^^^^
Fail directly with a user specified exception.

**emits** never

**completes** fails the stream directly with the given exception

actorPublisher
^^^^^^^^^^^^^^
Wrap an actor extending ``ActorPublisher`` as a source.

**emits** depends on the actor implementation

**completes** when the actor stops

actorRef
^^^^^^^^
Materialize an ``ActorRef``, sending messages to it will emit them on the stream. The actor contain
a buffer but since communication is one way, there is no back pressure. Handling overflow is done by either dropping
elements or failing the stream, the strategy is chosen by the user.

**emits** when there is demand and there are messages in the buffer or a message is sent to the actorref

**completes** when the actorref is sent ``akka.actor.Status.Success`` or ``PoisonPill``

combine
^^^^^^^
Combine several sources, using a given strategy such as merge or concat, into one source.

**emits** when there is demand, but depending on the strategy

**completes** when all sources has completed

unfoldResource
^^^^^^^^^^^^^^
Wrap any resource that can be opened, queried for next element (in a blocking way) and closed using three distinct functions into a source.

**emits** when there is demand and read function returns value

**completes** when read function returns ``None``

unfoldResourceAsync
^^^^^^^^^^^^^^^^^^^
Wrap any resource that can be opened, queried for next element (in a blocking way) and closed using three distinct functions into a source.
Functions return ``Future`` to achieve asynchronous processing

**emits** when there is demand and ``Future`` from read function returns value

**completes** when ``Future`` from read function returns ``None``

queue
^^^^^
Materialize a ``SourceQueue`` onto which elements can be pushed for emitting from the source. The queue contains
a buffer, if elements are pushed onto the queue faster than the source is consumed the overflow will be handled with
a strategy specified by the user. Functionality for tracking when an element has been emitted is available through
``SourceQueue.offer``.

**emits** when there is demand and the queue contains elements

**completes** when downstream completes

asSubscriber
^^^^^^^^^^^^
Integration with Reactive Streams, materializes into a ``org.reactivestreams.Subscriber``.


fromPublisher
^^^^^^^^^^^^^
Integration with Reactive Streams, subscribes to a ``org.reactivestreams.Publisher``.

zipN
^^^^
Combine the elements of multiple streams into a stream of sequences.

**emits** when all of the inputs has an element available

**completes** when any upstream completes

zipWithN
^^^^^^^^
Combine the elements of multiple streams into a stream of sequences using a combiner function.

**emits** when all of the inputs has an element available

**completes** when any upstream completes




Sink stages
-----------
These built-in sinks are available from ``akka.stream.scaladsl.Sink``:


head
^^^^
Materializes into a ``Future`` which completes with the first value arriving,
after this the stream is canceled. If no element is emitted, the future is be failed.

**cancels** after receiving one element

**backpressures** never

headOption
^^^^^^^^^^
Materializes into a ``Future[Option[T]]`` which completes with the first value arriving wrapped in a ``Some``,
or a ``None`` if the stream completes without any elements emitted.

**cancels** after receiving one element

**backpressures** never

last
^^^^
Materializes into a ``Future`` which will complete with the last value emitted when the stream
completes. If the stream completes with no elements the future is failed.

**cancels** never

**backpressures** never

lastOption
^^^^^^^^^^
Materialize a ``Future[Option[T]]`` which completes with the last value
emitted wrapped in an ``Some`` when the stream completes. if the stream completes with no elements the future is
completed with ``None``.

**cancels** never

**backpressures** never

ignore
^^^^^^
Consume all elements but discards them. Useful when a stream has to be consumed but there is no use to actually
do anything with the elements.

**cancels** never

**backpressures** never

cancelled
^^^^^^^^^
Immediately cancel the stream

**cancels** immediately

seq
^^^
Collect values emitted from the stream into a collection, the collection is available through a ``Future`` or
which completes when the stream completes. Note that the collection is bounded to ``Int.MaxValue``,
if more element are emitted the sink will cancel the stream

**cancels** If too many values are collected

foreach
^^^^^^^
Invoke a given procedure for each element received. Note that it is not safe to mutate shared state from the procedure.

The sink materializes into a  ``Future[Option[Done]]`` which completes when the
stream completes, or fails if the stream fails.

Note that it is not safe to mutate state from the procedure.

**cancels** never

**backpressures** when the previous procedure invocation has not yet completed


foreachParallel
^^^^^^^^^^^^^^^
Like ``foreach`` but allows up to ``parallellism`` procedure calls to happen in parallel.

**cancels** never

**backpressures** when the previous parallel procedure invocations has not yet completed


onComplete
^^^^^^^^^^
Invoke a callback when the stream has completed or failed.

**cancels** never

**backpressures** never

lazyInit
^^^^^^^^
Invoke sinkFactory function to create a real sink upon receiving the first element. Internal ``Sink`` will not be created if there are no elements,
because of completion or error. `fallback` will be invoked if there was no elements and completed is received from upstream.

**cancels** never

**backpressures** when initialized and when created sink backpressures

queue
^^^^^
Materialize a ``SinkQueue`` that can be pulled to trigger demand through the sink. The queue contains
a buffer in case stream emitting elements faster than queue pulling them.

**cancels** when  ``SinkQueue.cancel`` is called

**backpressures** when buffer has some space

fold
^^^^
Fold over emitted element with a function, where each invocation will get the new element and the result from the
previous fold invocation. The first invocation will be provided the ``zero`` value.

Materializes into a future that will complete with the last state when the stream has completed.

This stage allows combining values into a result without a global mutable state by instead passing the state along
between invocations.

**cancels** never

**backpressures** when the previous fold function invocation has not yet completed

reduce
^^^^^^
Apply a reduction function on the incoming elements and pass the result to the next invocation. The first invocation
receives the two first elements of the flow.

Materializes into a future that will be completed by the last result of the reduction function.

**cancels** never

**backpressures** when the previous reduction function invocation has not yet completed


combine
^^^^^^^
Combine several sinks into one using a user specified strategy

**cancels** depends on the strategy

**backpressures** depends on the strategy


actorRef
^^^^^^^^
Send the elements from the stream to an ``ActorRef``. No backpressure so care must be taken to not overflow the inbox.

**cancels** when the actor terminates

**backpressures** never


actorRefWithAck
^^^^^^^^^^^^^^^
Send the elements from the stream to an ``ActorRef`` which must then acknowledge reception after completing a message,
to provide back pressure onto the sink.

**cancels** when the actor terminates

**backpressures** when the actor acknowledgement has not arrived


actorSubscriber
^^^^^^^^^^^^^^^
Create an actor from a ``Props`` upon materialization, where the actor implements ``ActorSubscriber``, which will
receive the elements from the stream.

Materializes into an ``ActorRef`` to the created actor.

**cancels** when the actor terminates

**backpressures** depends on the actor implementation


asPublisher
^^^^^^^^^^^
Integration with Reactive Streams, materializes into a ``org.reactivestreams.Publisher``.


fromSubscriber
^^^^^^^^^^^^^^
Integration with Reactive Streams, wraps a ``org.reactivestreams.Subscriber`` as a sink




Additional Sink and Source converters
-------------------------------------
Sources and sinks for integrating with ``java.io.InputStream`` and ``java.io.OutputStream`` can be found on
``StreamConverters``. As they are blocking APIs the implementations of these stages are run on a separate
dispatcher configured through the ``akka.stream.blocking-io-dispatcher``.

fromOutputStream
^^^^^^^^^^^^^^^^
Create a sink that wraps an ``OutputStream``. Takes a function that produces an ``OutputStream``, when the sink is
materialized the function will be called and bytes sent to the sink will be written to the returned ``OutputStream``.

Materializes into a ``Future`` which will complete with a ``IOResult`` when the stream
completes.

Note that a flow can be materialized multiple times, so the function producing the ``OutputStream`` must be able
to handle multiple invocations.

The ``OutputStream`` will be closed when the stream that flows into the ``Sink`` is completed, and the ``Sink``
will cancel its inflow when the ``OutputStream`` is no longer writable.

asInputStream
^^^^^^^^^^^^^
Create a sink which materializes into an ``InputStream`` that can be read to trigger demand through the sink.
Bytes emitted through the stream will be available for reading through the ``InputStream``

The ``InputStream`` will be ended when the stream flowing into this ``Sink`` completes, and the closing the
``InputStream`` will cancel the inflow of this ``Sink``.

fromInputStream
^^^^^^^^^^^^^^^
Create a source that wraps an ``InputStream``. Takes a function that produces an ``InputStream``, when the source is
materialized the function will be called and bytes from the ``InputStream`` will be emitted into the stream.

Materializes into a ``Future`` which will complete with a ``IOResult`` when the stream
completes.

Note that a flow can be materialized multiple times, so the function producing the ``InputStream`` must be able
to handle multiple invocations.

The ``InputStream`` will be closed when the ``Source`` is canceled from its downstream, and reaching the end of the
``InputStream`` will complete the ``Source``.

asOutputStream
^^^^^^^^^^^^^^
Create a source that materializes into an ``OutputStream``. When bytes are written to the ``OutputStream`` they
are emitted from the source.

The ``OutputStream`` will no longer be writable when the ``Source`` has been canceled from its downstream, and
closing the ``OutputStream`` will complete the ``Source``.

asJavaStream
^^^^^^^^^^^^
Create a sink which materializes into Java 8 ``Stream`` that can be run to trigger demand through the sink.
Elements emitted through the stream will be available for reading through the Java 8 ``Stream``.

The Java 8 ``Stream`` will be ended when the stream flowing into this ``Sink`` completes, and closing the Java
``Stream`` will cancel the inflow of this ``Sink``. Java ``Stream`` throws exception in case reactive stream failed.

Be aware that Java ``Stream`` blocks current thread while waiting on next element from downstream.

fromJavaStream
^^^^^^^^^^^^^^
Create a source that wraps a Java 8 ``Stream``. ``Source`` uses a stream iterator to get all its elements and send them
downstream on demand.

javaCollector
^^^^^^^^^^^^^
Create a sink which materializes into a ``Future`` which will be completed with a result of the Java 8 ``Collector``
transformation and reduction operations. This allows usage of Java 8 streams transformations for reactive streams.
The ``Collector`` will trigger demand downstream. Elements emitted through the stream will be accumulated into a mutable
result container, optionally transformed into a final representation after all input elements have been processed.
The ``Collector`` can also do reduction at the end. Reduction processing is performed sequentially

Note that a flow can be materialized multiple times, so the function producing the ``Collector`` must be able
to handle multiple invocations.

javaCollectorParallelUnordered
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
Create a sink which materializes into a ``Future`` which will be completed with a result of the Java 8 ``Collector``
transformation and reduction operations. This allows usage of Java 8 streams transformations for reactive streams.
The ``Collector`` is triggering demand downstream. Elements emitted through the stream will be accumulated into a mutable
result container, optionally transformed into a final representation after all input elements have been processed.
The ``Collector`` can also do reduction at the end. Reduction processing is performed in parallel based on graph ``Balance``.

Note that a flow can be materialized multiple times, so the function producing the ``Collector`` must be able
to handle multiple invocations.

File IO Sinks and Sources
-------------------------
Sources and sinks for reading and writing files can be found on ``FileIO``.

fromFile
^^^^^^^^
Emit the contents of a file, as ``ByteString`` s, materializes into a ``Future`` which will be completed with
a ``IOResult`` upon reaching the end of the file or if there is a failure.

toFile
^^^^^^
Create a sink which will write incoming ``ByteString`` s to a given file.



Flow stages
-----------

All flows by default backpressure if the computation they encapsulate is not fast enough to keep up with the rate of
incoming elements from the preceding stage. There are differences though how the different stages handle when some of
their downstream stages backpressure them.

Most stages stop and propagate the failure downstream as soon as any of their upstreams emit a failure.
This happens to ensure reliable teardown of streams and cleanup when failures happen. Failures are meant to
be to model unrecoverable conditions, therefore they are always eagerly propagated.
For in-band error handling of normal errors (dropping elements if a map fails for example) you should use the
supervision support, or explicitly wrap your element types in a proper container that can express error or success
states (for example ``Try`` in Scala).


Simple processing stages
------------------------

These stages can transform the rate of incoming elements since there are stages that emit multiple elements for a
single input (e.g. `mapConcat') or consume multiple elements before emitting one output (e.g. ``filter``).
However, these rate transformations are data-driven, i.e. it is the incoming elements that define how the
rate is affected. This is in contrast with :ref:`detached-stages-overview_scala` which can change their processing behavior
depending on being backpressured by downstream or not.

map
^^^
Transform each element in the stream by calling a mapping function with it and passing the returned value downstream.

**emits** when the mapping function returns an element

**backpressures** when downstream backpressures

**completes** when upstream completes

mapConcat
^^^^^^^^^
Transform each element into zero or more elements that are individually passed downstream.

**emits** when the mapping function returns an element or there are still remaining elements from the previously calculated collection

**backpressures** when downstream backpressures or there are still available elements from the previously calculated collection

**completes** when upstream completes and all remaining elements has been emitted

statefulMapConcat
^^^^^^^^^^^^^^^^^
Transform each element into zero or more elements that are individually passed downstream. The difference to ``mapConcat`` is that
the transformation function is created from a factory for every materialization of the flow.

**emits** when the mapping function returns an element or there are still remaining elements from the previously calculated collection

**backpressures** when downstream backpressures or there are still available elements from the previously calculated collection

**completes** when upstream completes and all remaining elements has been emitted

filter
^^^^^^
Filter the incoming elements using a predicate. If the predicate returns true the element is passed downstream, if
it returns false the element is discarded.

**emits** when the given predicate returns true for the element

**backpressures** when the given predicate returns true for the element and downstream backpressures

**completes** when upstream completes

filterNot
^^^^^^^^^
Filter the incoming elements using a predicate. If the predicate returns false the element is passed downstream, if
it returns true the element is discarded.

**emits** when the given predicate returns false for the element

**backpressures** when the given predicate returns false for the element and downstream backpressures

**completes** when upstream completes

collect
^^^^^^^
Apply a partial function to each incoming element, if the partial function is defined for a value the returned
value is passed downstream. Can often replace ``filter`` followed by ``map`` to achieve the same in one single stage.

**emits** when the provided partial function is defined for the element

**backpressures** the partial function is defined for the element and downstream backpressures

**completes** when upstream completes

grouped
^^^^^^^
Accumulate incoming events until the specified number of elements have been accumulated and then pass the collection of
elements downstream.

**emits** when the specified number of elements has been accumulated or upstream completed

**backpressures** when a group has been assembled and downstream backpressures

**completes** when upstream completes

sliding
^^^^^^^
Provide a sliding window over the incoming stream and pass the windows as groups of elements downstream.

Note: the last window might be smaller than the requested size due to end of stream.

**emits** the specified number of elements has been accumulated or upstream completed

**backpressures** when a group has been assembled and downstream backpressures

**completes** when upstream completes


scan
^^^^
Emit its current value which starts at ``zero`` and then applies the current and next value to the given function
emitting the next current value.

Note that this means that scan emits one element downstream before and upstream elements will not be requested until
the second element is required from downstream.

**emits** when the function scanning the element returns a new element

**backpressures** when downstream backpressures

**completes** when upstream completes

fold
^^^^
Start with current value ``zero`` and then apply the current and next value to the given function, when upstream
complete the current value is emitted downstream.

**emits** when upstream completes

**backpressures** when downstream backpressures

**completes** when upstream completes

reduce
^^^^^^
Start with first element and then apply the current and next value to the given function, when upstream
complete the current value is emitted downstream. Similar to ``fold``.

**emits** when upstream completes

**backpressures** when downstream backpressures

**completes** when upstream completes

drop
^^^^
Drop ``n`` elements and then pass any subsequent element downstream.

**emits** when the specified number of elements has been dropped already

**backpressures** when the specified number of elements has been dropped and downstream backpressures

**completes** when upstream completes

take
^^^^
Pass ``n`` incoming elements downstream and then complete

**emits** while the specified number of elements to take has not yet been reached

**backpressures** when downstream backpressures

**completes** when the defined number of elements has been taken or upstream completes


takeWhile
^^^^^^^^^
Pass elements downstream as long as a predicate function return true for the element include the element
when the predicate first return false and then complete.

**emits** while the predicate is true and until the first false result

**backpressures** when downstream backpressures

**completes** when predicate returned false or upstream completes

dropWhile
^^^^^^^^^
Drop elements as long as a predicate function return true for the element

**emits** when the predicate returned false and for all following stream elements

**backpressures** predicate returned false and downstream backpressures

**completes** when upstream completes

recover
^^^^^^^
Allow sending of one last element downstream when a failure has happened upstream.

**emits** when the element is available from the upstream or upstream is failed and pf returns an element

**backpressures** when downstream backpressures, not when failure happened

**completes** when upstream completes or upstream failed with exception pf can handle

recoverWith
^^^^^^^^^^^
Allow switching to alternative Source when a failure has happened upstream.

**emits** the element is available from the upstream or upstream is failed and pf returns alternative Source

**backpressures** downstream backpressures, after failure happened it backprssures to alternative Source

**completes** upstream completes or upstream failed with exception pf can handle

detach
^^^^^^
Detach upstream demand from downstream demand without detaching the stream rates.

**emits** when the upstream stage has emitted and there is demand

**backpressures** when downstream backpressures

**completes** when upstream completes


throttle
^^^^^^^^
Limit the throughput to a specific number of elements per time unit, or a specific total cost per time unit, where
a function has to be provided to calculate the individual cost of each element.

**emits** when upstream emits an element and configured time per each element elapsed

**backpressures** when downstream backpressures

**completes** when upstream completes

intersperse
^^^^^^^^^^^
Intersperse stream with provided element similar to ``List.mkString``. It can inject start and end marker elements to stream.

**emits** when upstream emits an element or before with the `start` element if provided

**backpressures** when downstream backpressures

**completes** when upstream completes

limit
^^^^^
Limit number of element from upstream to given ``max`` number.

**emits** when upstream emits and the number of emitted elements has not reached max

**backpressures** when downstream backpressures

**completes** when upstream completes and the number of emitted elements has not reached max

limitWeighted
^^^^^^^^^^^^^
Ensure stream boundedness by evaluating the cost of incoming elements using a cost function.
Evaluated cost of each element defines how many elements will be allowed to travel downstream.

**emits** when upstream emits and the number of emitted elements has not reached max

**backpressures** when downstream backpressures

**completes** when upstream completes and the number of emitted elements has not reached max

log
^^^
Log elements flowing through the stream as well as completion and erroring. By default element and
completion signals are logged on debug level, and errors are logged on Error level.
This can be changed by calling ``Attributes.logLevels(...)`` on the given Flow.

**emits** when upstream emits

**backpressures** when downstream backpressures

**completes** when upstream completes

recoverWithRetries
^^^^^^^^^^^^^^^^^^
Switch to alternative Source on flow failure. It stays in effect after a failure has been recovered up to ``attempts``
number of times. Each time a failure is fed into the partial function and a new Source may be materialized.

**emits** when element is available from the upstream or upstream is failed and element is available from alternative Source

**backpressures** when downstream backpressures

**completes** when upstream completes or upstream failed with exception provided partial function can handle


Asynchronous processing stages
------------------------------

These stages encapsulate an asynchronous computation, properly handling backpressure while taking care of the asynchronous
operation at the same time (usually handling the completion of a Future).


mapAsync
^^^^^^^^
Pass incoming elements to a function that return a ``Future`` result. When the future arrives the result is passed
downstream. Up to ``n`` elements can be processed concurrently, but regardless of their completion time the incoming
order will be kept when results complete. For use cases where order does not mather ``mapAsyncUnordered`` can be used.

If a Future fails, the stream also fails (unless a different supervision strategy is applied)

**emits** when the Future returned by the provided function finishes for the next element in sequence

**backpressures** when the number of futures reaches the configured parallelism and the downstream backpressures

**completes** when upstream completes and all futures has been completed and all elements has been emitted

mapAsyncUnordered
^^^^^^^^^^^^^^^^^
Like ``mapAsync`` but ``Future`` results are passed downstream as they arrive regardless of the order of the elements
that triggered them.

If a Future fails, the stream also fails (unless a different supervision strategy is applied)

**emits** any of the Futures returned by the provided function complete

**backpressures** when the number of futures reaches the configured parallelism and the downstream backpressures

**completes** upstream completes and all futures has been completed  and all elements has been emitted


Timer driven stages
-------------------

These stages process elements using timers, delaying, dropping or grouping elements for certain time durations.

takeWithin
^^^^^^^^^^
Pass elements downstream within a timeout and then complete.

**emits** when an upstream element arrives

**backpressures** downstream backpressures

**completes** upstream completes or timer fires


dropWithin
^^^^^^^^^^
Drop elements until a timeout has fired

**emits** after the timer fired and a new upstream element arrives

**backpressures** when downstream backpressures

**completes** upstream completes

groupedWithin
^^^^^^^^^^^^^
Chunk up the stream into groups of elements received within a time window, or limited by the given number of elements,
whichever happens first.

**emits** when the configured time elapses since the last group has been emitted

**backpressures** when the group has been assembled (the duration elapsed) and downstream backpressures

**completes** when upstream completes


initialDelay
^^^^^^^^^^^^
Delay the initial element by a user specified duration from stream materialization.

**emits** upstream emits an element if the initial delay already elapsed

**backpressures** downstream backpressures or initial delay not yet elapsed

**completes** when upstream completes


delay
^^^^^
Delay every element passed through with a specific duration.

**emits** there is a pending element in the buffer and configured time for this element elapsed

**backpressures** differs, depends on ``OverflowStrategy`` set

**completes** when upstream completes and buffered elements has been drained



.. _detached-stages-overview_scala:

Backpressure aware stages
-------------------------

These stages are aware of the backpressure provided by their downstreams and able to adapt their behavior to that signal.

conflate
^^^^^^^^
Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as
there is backpressure. The summary value must be of the same type as the incoming elements, for example the sum or
average of incoming numbers, if aggregation should lead to a different type ``conflateWithSeed`` can be used:

**emits** when downstream stops backpressuring and there is a conflated element available

**backpressures** when the aggregate function cannot keep up with incoming elements

**completes** when upstream completes

conflateWithSeed
^^^^^^^^^^^^^^^^
Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there
is backpressure. When backpressure starts or there is no backpressure element is passed into a ``seed`` function to
transform it to the summary type.

**emits** when downstream stops backpressuring and there is a conflated element available

**backpressures** when the aggregate or seed functions cannot keep up with incoming elements

**completes** when upstream completes

batch
^^^^^
Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there
is backpressure and a maximum number of batched elements is not yet reached. When the maximum number is reached and
downstream still backpressures batch will also backpressure.

When backpressure starts or there is no backpressure element is passed into a ``seed`` function to transform it
to the summary type.

Will eagerly pull elements, this behavior may result in a single pending (i.e. buffered) element which cannot be
aggregated to the batched value.

**emits** when downstream stops backpressuring and there is a batched element available

**backpressures** when batched elements reached the max limit of allowed batched elements & downstream backpressures

**completes** when upstream completes and a "possibly pending" element was drained


batchWeighted
^^^^^^^^^^^^^
Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there
is backpressure and a maximum weight batched elements is not yet reached. The weight of each element is determined by
applying ``costFn``. When the maximum total weight is reached and downstream still backpressures batch will also
backpressure.

Will eagerly pull elements, this behavior may result in a single pending (i.e. buffered) element which cannot be
aggregated to the batched value.

**emits** downstream stops backpressuring and there is a batched element available

**backpressures** batched elements reached the max weight limit of allowed batched elements & downstream backpressures

**completes** upstream completes and a "possibly pending" element was drained

expand
^^^^^^
Allow for a faster downstream by expanding the last incoming element to an ``Iterator``. For example
``Iterator.continually(element)`` to keep repating the last incoming element.

**emits** when downstream stops backpressuring

**backpressures** when downstream backpressures

**completes** when upstream completes

buffer (Backpressure)
^^^^^^^^^^^^^^^^^^^^^
Allow for a temporarily faster upstream events by buffering ``size`` elements. When the buffer is full backpressure
is applied.

**emits** when downstream stops backpressuring and there is a pending element in the buffer

**backpressures** when buffer is full

**completes** when upstream completes and buffered elements has been drained

buffer (Drop)
^^^^^^^^^^^^^
Allow for a temporarily faster upstream events by buffering ``size`` elements. When the buffer is full elements are
dropped according to the specified ``OverflowStrategy``:

* ``dropHead`` drops the oldest element in the buffer to make space for the new element
* ``dropTail`` drops the youngest element in the buffer to make space for the new element
* ``dropBuffer`` drops the entire buffer and buffers the new element
* ``dropNew`` drops the new element

**emits** when downstream stops backpressuring and there is a pending element in the buffer

**backpressures** never (when dropping cannot keep up with incoming elements)

**completes** upstream completes and buffered elements has been drained

buffer (Fail)
^^^^^^^^^^^^^
Allow for a temporarily faster upstream events by buffering ``size`` elements. When the buffer is full the stage fails
the flow with a ``BufferOverflowException``.

**emits** when downstream stops backpressuring and there is a pending element in the buffer

**backpressures** never, fails the stream instead of backpressuring when buffer is full

**completes** when upstream completes and buffered elements has been drained


Nesting and flattening stages
-----------------------------

These stages either take a stream and turn it into a stream of streams (nesting) or they take a stream that contains
nested streams and turn them into a stream of elements instead (flattening).

prefixAndTail
^^^^^^^^^^^^^
Take up to `n` elements from the stream (less than `n` only if the upstream completes before emitting `n` elements)
and returns a pair containing a strict sequence of the taken element and a stream representing the remaining elements.

**emits** when the configured number of prefix elements are available. Emits this prefix, and the rest as a substream

**backpressures** when downstream backpressures or substream backpressures

**completes** when prefix elements has been consumed and substream has been consumed


groupBy
^^^^^^^
Demultiplex the incoming stream into separate output streams.

**emits** an element for which the grouping function returns a group that has not yet been created. Emits the new group
there is an element pending for a group whose substream backpressures

**completes** when upstream completes (Until the end of stream it is not possible to know whether new substreams will be needed or not)

splitWhen
^^^^^^^^^
Split off elements into a new substream whenever a predicate function return ``true``.

**emits** an element for which the provided predicate is true, opening and emitting a new substream for subsequent elements

**backpressures** when there is an element pending for the next substream, but the previous is not fully consumed yet, or the substream backpressures

**completes** when upstream completes (Until the end of stream it is not possible to know whether new substreams will be needed or not)

splitAfter
^^^^^^^^^^
End the current substream whenever a predicate returns ``true``, starting a new substream for the next element.

**emits** when an element passes through. When the provided predicate is true it emits the element * and opens a new substream for subsequent element

**backpressures** when there is an element pending for the next substream, but the previous is not fully consumed yet, or the substream backpressures

**completes** when upstream completes (Until the end of stream it is not possible to know whether new substreams will be needed or not)

flatMapConcat
^^^^^^^^^^^^^
Transform each input element into a ``Source`` whose elements are then flattened into the output stream through
concatenation. This means each source is fully consumed before consumption of the next source starts.

**emits** when the current consumed substream has an element available

**backpressures** when downstream backpressures

**completes** when upstream completes and all consumed substreams complete


flatMapMerge
^^^^^^^^^^^^
Transform each input element into a ``Source`` whose elements are then flattened into the output stream through
merging. The maximum number of merged sources has to be specified.

**emits** when one of the currently consumed substreams has an element available

**backpressures** when downstream backpressures

**completes** when upstream completes and all consumed substreams complete


Time aware stages
-----------------

Those stages operate taking time into consideration.

initialTimeout
^^^^^^^^^^^^^^
If the first element has not passed through this stage before the provided timeout, the stream is failed
with a ``TimeoutException``.

**emits** when upstream emits an element

**backpressures** when downstream backpressures

**completes** when upstream completes or fails if timeout elapses before first element arrives

**cancels** when downstream cancels

completionTimeout
^^^^^^^^^^^^^^^^^
If the completion of the stream does not happen until the provided timeout, the stream is failed
with a ``TimeoutException``.

**emits** when upstream emits an element

**backpressures** when downstream backpressures

**completes** when upstream completes or fails if timeout elapses before upstream completes

**cancels** when downstream cancels

idleTimeout
^^^^^^^^^^^
If the time between two processed elements exceeds the provided timeout, the stream is failed
with a ``TimeoutException``. The timeout is checked periodically, so the resolution of the
check is one period (equals to timeout value).

**emits** when upstream emits an element

**backpressures** when downstream backpressures

**completes** when upstream completes or fails if timeout elapses between two emitted elements

**cancels** when downstream cancels

backpressureTimeout
^^^^^^^^^^^^^^^^^^^
If the time between the emission of an element and the following downstream demand exceeds the provided timeout,
the stream is failed with a ``TimeoutException``. The timeout is checked periodically, so the resolution of the
check is one period (equals to timeout value).

**emits** when upstream emits an element

**backpressures** when downstream backpressures

**completes** when upstream completes or fails if timeout elapses between element emission and downstream demand.

**cancels** when downstream cancels

keepAlive
^^^^^^^^^
Injects additional (configured) elements if upstream does not emit for a configured amount of time.

**emits** when upstream emits an element or if the upstream was idle for the configured period

**backpressures** when downstream backpressures

**completes** when upstream completes

**cancels** when downstream cancels

initialDelay
^^^^^^^^^^^^
Delays the initial element by the specified duration.

**emits** when upstream emits an element if the initial delay is already elapsed

**backpressures** when downstream backpressures or initial delay is not yet elapsed

**completes** when upstream completes

**cancels** when downstream cancels


Fan-in stages
-------------

These stages take multiple streams as their input and provide a single output combining the elements from all of
the inputs in different ways.

merge
^^^^^
Merge multiple sources. Picks elements randomly if all sources has elements ready.

**emits** when one of the inputs has an element available

**backpressures** when downstream backpressures

**completes** when all upstreams complete (This behavior is changeable to completing when any upstream completes by setting ``eagerComplete=true``.)

mergeSorted
^^^^^^^^^^^
Merge multiple sources. Waits for one element to be ready from each input stream and emits the
smallest element.

**emits** when all of the inputs have an element available

**backpressures** when downstream backpressures

**completes** when all upstreams complete

mergePreferred
^^^^^^^^^^^^^^
Merge multiple sources. Prefer one source if all sources has elements ready.

**emits** when one of the inputs has an element available, preferring a defined input if multiple have elements available

**backpressures** when downstream backpressures

**completes** when all upstreams complete (This behavior is changeable to completing when any upstream completes by setting ``eagerComplete=true``.)

zip
^^^
Combines elements from each of multiple sources into tuples and passes the tuples downstream.

**emits** when all of the inputs have an element available

**backpressures** when downstream backpressures

**completes** when any upstream completes

zipWith
^^^^^^^
Combines elements from multiple sources through a ``combine`` function and passes the
returned value downstream.

**emits** when all of the inputs have an element available

**backpressures** when downstream backpressures

**completes** when any upstream completes

zipWithIndex
^^^^^^^^^^^^
Zips elements of current flow with its indices.

**emits** upstream emits an element and is paired with their index

**backpressures** when downstream backpressures

**completes** when upstream completes

concat
^^^^^^
After completion of the original upstream the elements of the given source will be emitted.

**emits** when the current stream has an element available; if the current input completes, it tries the next one

**backpressures** when downstream backpressures

**completes** when all upstreams complete

++
^^
Just a shorthand for concat

**emits** when the current stream has an element available; if the current input completes, it tries the next one

**backpressures** when downstream backpressures

**completes** when all upstreams complete

prepend
^^^^^^^
Prepends the given source to the flow, consuming it until completion before the original source is consumed.

If materialized values needs to be collected ``prependMat`` is available.

**emits** when the given stream has an element available; if the given input completes, it tries the current one

**backpressures** when downstream backpressures

**completes** when all upstreams complete

orElse
^^^^^^
If the primary source completes without emitting any elements, the elements from the secondary source
are emitted. If the primary source emits any elements the secondary source is cancelled.

Note that both sources are materialized directly and the secondary source is backpressured until it becomes
the source of elements or is cancelled.

Signal errors downstream, regardless which of the two sources emitted the error.

**emits** when an element is available from first stream or first stream closed without emitting any elements and an element
is available from the second stream

**backpressures** when downstream backpressures

**completes** the primary stream completes after emitting at least one element, when the primary stream completes
without emitting and the secondary stream already has completed or when the secondary stream completes

interleave
^^^^^^^^^^
Emits a specifiable number of elements from the original source, then from the provided source and repeats. If one
source completes the rest of the other stream will be emitted.

**emits** when element is available from the currently consumed upstream

**backpressures** when upstream backpressures

**completes** when both upstreams have completed

Fan-out stages
--------------

These have one input and multiple outputs. They might route the elements between different outputs, or emit elements on
multiple outputs at the same time.

unzip
^^^^^
Takes a stream of two element tuples and unzips the two elements ino two different downstreams.

**emits** when all of the outputs stops backpressuring and there is an input element available

**backpressures** when any of the outputs backpressures

**completes** when upstream completes

unzipWith
^^^^^^^^^
Splits each element of input into multiple downstreams using a function

**emits** when all of the outputs stops backpressuring and there is an input element available

**backpressures** when any of the outputs backpressures

**completes** when upstream completes

broadcast
^^^^^^^^^
Emit each incoming element each of ``n`` outputs.

**emits** when all of the outputs stops backpressuring and there is an input element available

**backpressures** when any of the outputs backpressures

**completes** when upstream completes

balance
^^^^^^^
Fan-out the stream to several streams. Each upstream element is emitted to the first available downstream consumer.

**emits** when any of the outputs stops backpressuring; emits the element to the first available output

**backpressures** when all of the outputs backpressure

**completes** when upstream completes

partition
^^^^^^^^^
Fan-out the stream to several streams. Each upstream element is emitted to one downstream consumer according to the
partitioner function applied to the element.

**emits** when the chosen output stops backpressuring and there is an input element available

**backpressures** when the chosen output backpressures

**completes** when upstream completes and no output is pending


Watching status stages
----------------------

watchTermination
^^^^^^^^^^^^^^^^
Materializes to a ``Future`` that will be completed with Done or failed depending whether the upstream of the stage has been completed or failed.
The stage otherwise passes through elements unchanged.

**emits** when input has an element available

**backpressures** when output backpressures

**completes** when upstream completes

monitor
^^^^^^^
Materializes to a ``FlowMonitor`` that monitors messages flowing through or completion of the stage. The stage otherwise
passes through elements unchanged. Note that the ``FlowMonitor`` inserts a memory barrier every time it processes an
event, and may therefore affect performance.

**emits** when upstream emits an element

**backpressures** when downstream **backpressures**

**completes** when upstream completes

