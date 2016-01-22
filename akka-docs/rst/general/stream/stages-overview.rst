.. _stages-overview:

Overview of built-in stages and their semantics
===============================================


Source stages
-------------
These built-in sources are available from `akka.stream.scaladsl.Source` and `akka.stream.javadsl.Source`



fromIterator
^^^^^^^^^^^^
Streams the values from an iterator, requesting the next value when there is demand. If the iterator
performs blocking operations, make sure to run it on a separate dispatcher.

*emits* when the next value returned from the iterator
*completes* when the iterator reaches it's end

single
^^^^^^
Streams a single object

*emits* the value once
*completes* when the single value has been emitted

repeat
^^^^^^
Streams a single object repeatedly

*emits* the same value repeatedly when there is demand
*completes* never

tick
^^^^
A periodical repeated stream of an arbitrary object. Delay of first tick is specified
separately from interval of the following ticks.

*emits* periodically, if there is downstream backpressure ticks are skipped
*completes* never

fromFuture
^^^^^^^^^^
The value of the future is sent when the future completes and there is demand.
If the future fails the stream is failed with that exception.

*emits* the future completes
*completes* after the future has completed

unfold
^^^^^^
Streams the result of a function as long as it returns a ``Some`` or non-empty ``Optional``, the value inside the optional
consists of a tuple (or ``Pair``) where the first value is a state passed back into the next call to the function allowing
to pass a state. The first invocation of the provided fold function will receive the ``zero`` state.

Can be used to implement many stateful sources without having to touch the more low level ``GraphStage`` API.

*emits* when there is demand and the unfold function over the previous state returns non empty value
*completes* when the unfold function returns an empty value

unfoldAsync
^^^^^^^^^^^
Just like ``unfold`` but the fold function returns a ``Future`` or ``CompletionStage`` which will cause the source to
complete or emit when it completes.

Can be used to implement many stateful sources without having to touch the more low level ``GraphStage`` API.

*emits* when there is demand and unfold state returned future completes with some value
*completes* when the future returned by the unfold function completes with an empty value

empty
^^^^^
A source that completes right away without ever emitting any elements. Useful when you have to provide a source to
an API but there are no elements to emit.

*emits* never
*completes* directly

maybe
^^^^^
A source that will either emit one value if the ``Option`` or ``Optional`` contains a value, or complete directly
if the optional value is empty.

*emits* when the returned promise is completed with some value
*completes* after emitting some value, or directly if the promise is completed with no value

failed
^^^^^^
A source that fails with a user specified exception

*emits* never
*completes* fails the stream directly with the given exception

actorPublisher
^^^^^^^^^^^^^^
Wraps an actor extending ``ActorPublisher`` as a source

*emits* depends on the actor implementation
*completes* when the actor stops (TODO double check)

actorRef
^^^^^^^^
Materializes into an ``ActorRef``, sending messages to the actor will emit them on the stream. The actor contains
a buffer but since communication is one way, there is no back pressure. Handling overflow is done by either dropping
elements or failing the stream, the strategy is chosen by the user.

*emits* when there is demand and there are messages in the buffer or a message is sent to the actorref
*completes* When the actorref is sent ``akka.actor.Status.Success`` or ``PoisonPill``

combine
^^^^^^^
Combines several sources, using a given strategy such as merge or concat, into one source.

*emits* when there is demand, but depending on the strategy
*completes*

queue
^^^^^
Materializes into a ``SourceQueue`` onto which elements can be pushed for emitting from the source. The queue contains
a buffer, if elements are pushed onto the queue faster than the source is consumed the overflow will be handled with
a strategy specified by the user. Functionality for tracking when an element has been emitted is available through
``SourceQueue.offer``.

*emits* when there is demand and the queue contains elements
*completes* when downstream completes

asSubscriber
^^^^^^^^^^^^
Integration with Reactive Streams, materializes into a ``org.reactivestreams.Subscriber``.


fromPublisher
^^^^^^^^^^^^^
Integration with Reactive Streams, subscribes to a ``org.reactivestreams.Publisher``.





Sink stages
-----------
These built-in sinks are available from ``akka.stream.scaladsl.Sink`` and ``akka.stream.javadsl.Sink``:


head
^^^^
Materializes into a ``Future`` or ``CompletionStage`` which completes with the first value arriving,
after this the stream is canceled. If no element is emitted, the future is be failed.

*cancels* after receiving one element
*backpressures* never

headOption
^^^^^^^^^^
Materializes into a ``Future[Option[T]]`` or ``CompletionStage<Optional<T>>`` which completes with the first value
arriving wrapped in the optional, or an empty optional if the stream completes without any elements emitted.

*cancels* after receiving one element
*backpressures* never

last
^^^^
Materializes into a ``Future`` or ``CompletionStage`` which will complete with the last value emitted when the stream
completes. If the stream completes with no elements the future is failed.

*cancels* never
*backpressures* never

lastOption
^^^^^^^^^^
Materializes into a ``Future[Option[T]]`` or ``CompletionStage<Optional<T>>`` which completes with the last value
emitted wrapped in an optional when the stream completes. if the stream completes with no elements the future is
completed with an empty optional.

*cancels* never
*backpressures* never

ignore
^^^^^^
Keeps consuming elements but discards them

*cancels* never
*backpressures* never

cancelled
^^^^^^^^^
Immediately cancels the stream

*cancels* immediately

seq_
^^^^
TODO three letter header not allowed

Collects values emitted from the stream into a collection, the collection is available through a ``Future`` or
``CompletionStage`` which completes when the stream completes. Note that the collection is bounded to ``Int.MaxValue``,
if more element are emitted the sink will cancel the stream

*cancels* If too many values are collected

foreach
^^^^^^^
Invokes a given procedure for each element received. Note that it is not safe to mutate shared state from the procedure.

The sink materializes into a  ``Future[Option[Done]]`` or ``CompletionStage<Optional<Done>>`` which completes when the
stream completes, or fails if the stream fails.

Note that it is not safe to mutate state from the procedure.

*cancels* never
*backpressures* when the previous procedure invocation has not yet completed


foreachParallel
^^^^^^^^^^^^^^^
Like ``foreach`` but allows up to ``parallellism`` procedure calls to happen in parallel.

*cancels* never
*backpressures* when the previous parallel procedure invocations has not yet completed


onComplete
^^^^^^^^^^
A sink that calls a callback when the stream has completed or failed.

*cancels* never
*backpressures* never


fold
^^^^
Fold over emitted element with a function, where each invocation will get the new element and the result from the
previous fold invocation. The first invocation will be provided the ``zero`` value.

Materializes into a future that will complete with the last state when the stream has completed.

This stage allows combining values into a result without a global mutable state by instead passing the state along
between invocations.

*cancels* never
*backpressures* when the previous fold function invocation has not yet completed

reduce
^^^^^^
Applies a reduction function on the incoming elements and passes the result to the next invocation. The first invocation
receives the two first elements of the flow.

Materializes into a future that will be completed by the last result of the reduction function.

*cancels* never
*backpressures* when the previous reduction function invocation has not yet completed


combine
^^^^^^^
Combines several sinks into one using a user specified strategy

*cancels* depends on the strategy
*backpressures* depends on the strategy


actorRef
^^^^^^^^
Sends the elements from the stream to an ``ActorRef``. No backpressure so care must be taken to not overflow the inbox.

*cancels* when the actor terminates
*backpressures* never


actorRefWithAck
^^^^^^^^^^^^^^^
Sends the elements from the stream to an ``ActorRef`` which must then acknowledge reception after completing a message,
to provide back pressure onto the sink.

*cancels* when the actor terminates
*backpressures* when the actor acknowledgement has not arrived


actorSubscriber
^^^^^^^^^^^^^^^
Creates an actor from a ``Props`` upon materialization, where the actor implements ``ActorSubscriber``.

Materializes into an ``ActorRef`` to the created actor.

*cancels* when the actor terminates
*backpressures* depends on the actor implementation


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
Creates a sink that wraps an ``OutputStream``. Takes a function that produces an ``OutputStream``, when the sink is
materialized the function will be called and bytes sent to the sink will be written to the returned ``OutputStream``.

Materializes into a ``Future`` or ``CompletionStage`` which will complete with a ``IOResult`` when the stream
completes.

Note that a flow can be materialized multiple times, so the function producing the ``OutputStream`` must be able
to handle multiple invocations.

asInputStream
^^^^^^^^^^^^^
Creates a sink which materializes into an ``InputStream`` that can be read to trigger demand through the sink.
Bytes emitted through the stream will be available for reading through the ``InputStream``

fromInputStream
^^^^^^^^^^^^^^^
Creates a source that wraps an ``InputStream``. Takes a function that produces an ``InputStream``, when the source is
materialized the function will be called and bytes from the ``InputStream`` will be emitted into the stream.

Materializes into a ``Future`` or ``CompletionStage`` which will complete with a ``IOResult`` when the stream
completes.

Note that a flow can be materialized multiple times, so the function producing the ``InputStream`` must be able
to handle multiple invocations.

asOutputStream
^^^^^^^^^^^^^^
Creates a source that materializes into an ``OutputStream``. When bytes are written to the ``OutputStream`` they
are emitted from the source



File IO Sinks and Sources
-------------------------
Sources and sinks for reading and writing files can be found on ``FileIO``.

fromFile
^^^^^^^^
Emits the contents of a file, as ``ByteString`` s, materializes into a ``Future`` or ``CompletionStage`` which will be completed with
a ``IOResult`` upon reaching the end of the file or if there is a failure.

toFile
^^^^^^
Creates a sink which will write incoming ``ByteString`` s to a given file.



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
^^^^^^^^^^^^^^^^^^^^^^^^

These stages are all expressible as a ``GraphStage``. These stages can transform the rate of incoming elements
since there are stages that emit multiple elements for a single input (e.g. `mapConcat') or consume
multiple elements before emitting one output (e.g. ``filter``). However, these rate transformations are data-driven, i.e. it is
the incoming elements that define how the rate is affected. This is in contrast with :ref:`detached-stages-overview`
which can change their processing behavior depending on being backpressured by downstream or not.

=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
Stage                  Emits when                                                                                                                  Backpressures when                                                                                                              Completes when
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
map                    the mapping function returns an element                                                                                     downstream backpressures                                                                                                        upstream completes
mapConcat              the mapping function returns an element or there are still remaining elements from the previously calculated collection     downstream backpressures or there are still available elements from the previously calculated collection                        upstream completes and all remaining elements has been emitted
filter                 the given predicate returns true for the element                                                                            the given predicate returns true for the element and downstream backpressures                                                   upstream completes
collect                the provided partial function is defined for the element                                                                    the partial function is defined for the element and downstream backpressures                                                    upstream completes
grouped                the specified number of elements has been accumulated or upstream completed                                                 a group has been assembled and downstream backpressures                                                                         upstream completes
sliding                the specified number of elements has been accumulated or upstream completed                                                 a group has been assembled and downstream backpressures                                                                         upstream completes
scan                   the function scanning the element returns a new element                                                                     downstream backpressures                                                                                                        upstream completes
fold                   upstream completes                                                                                                          downstream backpressures                                                                                                        upstream completes
drop                   the specified number of elements has been dropped already                                                                   the specified number of elements has been dropped and downstream backpressures                                                  upstream completes
take                   the specified number of elements to take has not yet been reached                                                           downstream backpressures                                                                                                        the defined number of elements has been taken or upstream completes
takeWhile              the predicate is true and until the first false result                                                                      downstream backpressures                                                                                                        predicate returned false or upstream completes
dropWhile              the predicate returned false and for all following stream elements                                                          predicate returned false and downstream backpressures                                                                           upstream completes
recover                the element is available from the upstream or upstream is failed and pf returns an element                                  downstream backpressures, not when failure happened                                                                             upstream completes or upstream failed with exception pf can handle
detach                 the upstream stage has emitted and there is demand                                                                          downstream backpressures                                                                                                        upstream completes
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================

Asynchronous processing stages
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

These stages encapsulate an asynchronous computation, properly handling backpressure while taking care of the asynchronous
operation at the same time (usually handling the completion of a Future).

=====================  =========================================================================================================================   ==============================================================================================================================  =============================================================================================
Stage                  Emits when                                                                                                                  Backpressures when                                                                                                              Completes when
=====================  =========================================================================================================================   ==============================================================================================================================  =============================================================================================
mapAsync               the Future returned by the provided function finishes for the next element in sequence                                      the number of futures reaches the configured parallelism and the downstream backpressures                                       upstream completes and all futures has been completed  and all elements has been emitted [1]_
mapAsyncUnordered      any of the Futures returned by the provided function complete                                                               the number of futures reaches the configured parallelism and the downstream backpressures                                       upstream completes and all futures has been completed  and all elements has been emitted [1]_
=====================  =========================================================================================================================   ==============================================================================================================================  =============================================================================================

Timer driven stages
^^^^^^^^^^^^^^^^^^^

These stages process elements using timers, delaying, dropping or grouping elements for certain time durations.

=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
Stage                  Emits when                                                                                                                  Backpressures when                                                                                                              Completes when
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
takeWithin             an upstream element arrives                                                                                                 downstream backpressures                                                                                                        upstream completes or timer fires
dropWithin             after the timer fired and a new upstream element arrives                                                                    downstream backpressures                                                                                                        upstream completes
groupedWithin          the configured time elapses since the last group has been emitted                                                           the group has been assembled (the duration elapsed) and downstream backpressures                                                upstream completes
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================

**It is currently not possible to build custom timer driven stages**

.. _detached-stages-overview:

Backpressure aware stages
^^^^^^^^^^^^^^^^^^^^^^^^^

These stages are all expressible as a ``DetachedStage``. These stages are aware of the backpressure provided by their
downstreams and able to adapt their behavior to that signal.

=====================  =========================================================================================================================   ====================================================================================================================================  =====================================================================================
Stage                  Emits when                                                                                                                  Backpressures when                                                                                                                    Completes when
=====================  =========================================================================================================================   ====================================================================================================================================  =====================================================================================
conflate               downstream stops backpressuring and there is a conflated element available                                                  never [2]_                                                                                                                            upstream completes
conflateWithSeed       downstream stops backpressuring and there is a conflated element available                                                  never [2]_                                                                                                                            upstream completes
batch                  downstream stops backpressuring and there is a batched element available                                                    batched elements reached the max limit of allowed batched elements & downstream backpressures                                         upstream completes and a "possibly pending" element was drained [3]_
batchWeighted          downstream stops backpressuring and there is a batched element available                                                    batched elements reached the max weight limit of allowed batched elements (plus a pending element [3]_ ) & downstream backpressures   upstream completes and a "possibly pending" element was drained [3]_
expand                 downstream stops backpressuring                                                                                             downstream backpressures                                                                                                              upstream completes
buffer (Backpressure)  downstream stops backpressuring and there is a pending element in the buffer                                                buffer is full                                                                                                                        upstream completes and buffered elements has been drained
buffer (DropX)         downstream stops backpressuring and there is a pending element in the buffer                                                never [2]_                                                                                                                            upstream completes and buffered elements has been drained
buffer (Fail)          downstream stops backpressuring and there is a pending element in the buffer                                                fails the stream instead of backpressuring when buffer is full                                                                        upstream completes and buffered elements has been drained
=====================  =========================================================================================================================   ====================================================================================================================================  =====================================================================================

Nesting and flattening stages
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

These stages either take a stream and turn it into a stream of streams (nesting) or they take a stream that contains
nested streams and turn them into a stream of elements instead (flattening).

**It is currently not possible to build custom nesting or flattening stages**

=====================  =========================================================================================================================================   ==============================================================================================================================  =====================================================================================
Stage                  Emits when                                                                                                                                  Backpressures when                                                                                                              Completes when
=====================  =========================================================================================================================================   ==============================================================================================================================  =====================================================================================
prefixAndTail          the configured number of prefix elements are available. Emits this prefix, and the rest as a substream                                      downstream backpressures or substream backpressures                                                                             prefix elements has been consumed and substream has been consumed
groupBy                an element for which the grouping function returns a group that has not yet been created. Emits the new group                               there is an element pending for a group whose substream backpressures                                                           upstream completes [4]_
splitWhen              an element for which the provided predicate is true, opening and emitting a new substream for subsequent elements                           there is an element pending for the next substream, but the previous is not fully consumed yet, or the substream backpressures  upstream completes [4]_
splitAfter             an element passes through. When the provided predicate is true it emitts the element * and opens a new substream for subsequent element     there is an element pending for the next substream, but the previous is not fully consumed yet, or the substream backpressures  upstream completes [4]_
flatMapConcat          the current consumed substream has an element available                                                                                     downstream backpressures                                                                                                        upstream completes and all consumed substreams complete
flatMapMerge           one of the currently consumed substreams has an element available                                                                           downstream backpressures                                                                                                        upstream completes and all consumed substreams complete
=====================  =========================================================================================================================================   ==============================================================================================================================  =====================================================================================

Fan-in stages
^^^^^^^^^^^^^

Most of these stages can be expressible as a ``GraphStage``. These stages take multiple streams as their input and provide
a single output combining the elements from all of the inputs in different ways.

**The custom fan-in stages that can be built currently are limited**

=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
Stage                  Emits when                                                                                                                  Backpressures when                                                                                                              Completes when
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
merge                  one of the inputs has an element available                                                                                  downstream backpressures                                                                                                        all upstreams complete (*)
mergeSorted            all of the inputs have an element available                                                                                 downstream backpressures                                                                                                        all upstreams complete
mergePreferred         one of the inputs has an element available, preferring a defined input if multiple have elements available                  downstream backpressures                                                                                                        all upstreams complete (*)
zip                    all of the inputs have an element available                                                                                 downstream backpressures                                                                                                        any upstream completes
zipWith                all of the inputs have an element available                                                                                 downstream backpressures                                                                                                        any upstream completes
concat                 the current stream has an element available; if the current input completes, it tries the next one                          downstream backpressures                                                                                                        all upstreams complete
prepend                the given stream has an element available; if the given input completes, it tries the current one                           downstream backpressures                                                                                                        all upstreams complete
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================

(*) This behavior is changeable to completing when any upstream completes by setting ``eagerComplete=true``.

Fan-out stages
^^^^^^^^^^^^^^

Most of these stages can be expressible as a ``GraphStage``. These have one input and multiple outputs. They might
route the elements between different outputs, or emit elements on multiple outputs at the same time.

**The custom fan-out stages that can be built currently are limited**

=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
Stage                  Emits when                                                                                                                  Backpressures when                                                                                                              Completes when
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
unzip                  all of the outputs stops backpressuring and there is an input element available                                             any of the outputs backpressures                                                                                                upstream completes
unzipWith              all of the outputs stops backpressuring and there is an input element available                                             any of the outputs backpressures                                                                                                upstream completes
broadcast              all of the outputs stops backpressuring and there is an input element available                                             any of the outputs backpressures                                                                                                upstream completes
balance                any of the outputs stops backpressuring; emits the element to the first available output                                    all of the outputs backpressure                                                                                                 upstream completes
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================

Watching status stages
^^^^^^^^^^^^^^^^^^^^^^

Materializes to a Future that will be completed with Done or failed depending whether the upstream of the stage has been completed or failed.
The stage otherwise passes through elements unchanged.

=====================  ========================================================================   ==========================================================  =====================================================================================
Stage                  Emits when                                                                 Backpressures when                                          Completes when
=====================  ========================================================================   ==========================================================  =====================================================================================
watchTermination       input has an element available                                             output backpressures                                        upstream completes
=====================  ========================================================================   ==========================================================  =====================================================================================


.. [1] If a Future fails, the stream also fails (unless a different supervision strategy is applied)
.. [2] Except if the encapsulated computation is not fast enough
.. [3] Batch & BatchWeighted stages eagerly pulling elements, and this behavior may result in a single pending (i.e. buffered) element which cannot be aggregated to the batched value
.. [4] Until the end of stream it is not possible to know whether new substreams will be needed or not
