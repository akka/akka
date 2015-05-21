.. _stages-overview:

###############################################
Overview of built-in stages and their semantics
###############################################

All stages by default backpressure if the computation they encapsulate is not fast enough to keep up with the rate of
incoming elements from the preceding stage. There are differences though how the different stages handle when some of
their downstream stages backpressure them. This table provides a summary of all built-in stages and their semantics.

All stages stop and propagate the failure downstream as soon as any of their upstreams emit a failure unless supervision
is used. This happens to ensure reliable teardown of streams and cleanup when failures happen. Failures are meant to
be to model unrecoverable conditions, therefore they are always eagerly propagated.
For in-band error handling of normal errors (dropping elements if a map fails for example) you should use the
upervision support, or explicitly wrap your element types in a proper container that can express error or success
states (for example ``Try`` in Scala).

Custom components are not covered by this table since their semantics are defined by the user.

Simple processing stages
^^^^^^^^^^^^^^^^^^^^^^^^

These stages are all expressible as a ``PushPullStage``. These stages can transform the rate of incoming elements
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
scan                   the function scanning the element returns a new element                                                                     downstream backpressures                                                                                                        upstream completes
drop                   the specified number of elements has been dropped already                                                                   the specified number of elements has been dropped and downstream backpressures                                                  upstream completes
take                   the specified number of elements to take has not yet been reached                                                           downstream backpressures                                                                                                        the defined number of elements has been taken or upstream completes
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================

Asynchronous processing stages
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

These stages encapsulate an asynchronous computation, properly handling backpressure while taking care of the asynchronous
operation at the same time (usually handling the completion of a Future).

**It is currently not possible to build custom asynchronous processing stages**

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

=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
Stage                  Emits when                                                                                                                  Backpressures when                                                                                                              Completes when
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
conflate               downstream stops backpressuring and there is a conflated element available                                                  never [2]_                                                                                                                      upstream completes
expand                 downstream stops backpressuring                                                                                             downstream backpressures                                                                                                        upstream completes
buffer (Backpressure)  downstream stops backpressuring and there is a pending element in the buffer                                                buffer is full                                                                                                                  upstream completes and buffered elements has been drained
buffer (DropX)         downstream stops backpressuring and there is a pending element in the buffer                                                never [2]_                                                                                                                      upstream completes and buffered elements has been drained
buffer (Fail)          downstream stops backpressuring and there is a pending element in the buffer                                                fails the stream instead of backpressuring when buffer is full                                                                  upstream completes and buffered elements has been drained
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================

Nesting and flattening stages
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

These stages either take a stream and turn it into a stream of streams (nesting) or they take a stream that contains
nested streams and turn them into a stream of elements instead (flattening).

**It is currently not possible to build custom nesting or flattening stages**

=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
Stage                  Emits when                                                                                                                  Backpressures when                                                                                                              Completes when
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
prefixAndTail          the configured number of prefix elements are available. Emits this prefix, and the rest as a substream                      downstream backpressures or substream backpressures                                                                             prefix elements has been consumed and substream has been consumed
groupBy                an element for which the grouping function returns a group that has not yet been created. Emits the new group               there is an element pending for a group whose substream backpressures                                                           upstream completes [3]_
splitWhen              an element for which the provided predicate is true, opening and emitting a new substream for subsequent elements           there is an element pending for the next substream, but the previous is not fully consumed yet, or the substream backpressures  upstream completes [3]_
flatten (Concat)       the current consumed substream has an element available                                                                     downstream backpressures                                                                                                        upstream completes and all consumed substreams complete
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================

Fan-in stages
^^^^^^^^^^^^^

Most of these stages can be expressible as a ``FlexiMerge``. These stages take multiple streams as their input and provide
a single output combining the elements from all of the inputs in different ways.

**The custom fan-in stages that can be built currently are limited**

=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
Stage                  Emits when                                                                                                                  Backpressures when                                                                                                              Completes when
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
merge                  one of the inputs has an element available                                                                                  downstream backpressures                                                                                                        all upstreams complete
mergePreferred         one of the inputs has an element available, preferring a defined input if multiple have elements available                  downstream backpressures                                                                                                        all upstreams complete
zip                    all of the inputs has an element available                                                                                  downstream backpressures                                                                                                        any upstream completes
zipWith                all of the inputs has an element available                                                                                  downstream backpressures                                                                                                        any upstream completes
concat                 the current stream has an element available; if the current input completes, it tries the next one                          downstream backpressures                                                                                                        all upstreams complete
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================

Fan-out stages
^^^^^^^^^^^^^^

Most of these stages can be expressible as a ``FlexiRoute``. These have one input and multiple outputs. They might
route the elements between different outputs, or emit elements on multiple outputs at the same time.

**The custom fan-out stages that can be built currently are limited**

=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
Stage                  Emits when                                                                                                                  Backpressures when                                                                                                              Completes when
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================
unzip                  all of the outputs stops backpressuring and there is an input element available                                             any of the outputs backpressures                                                                                                upstream completes
broadcast              all of the outputs stops backpressuring and there is an input element available                                             any of the outputs backpressures                                                                                                upstream completes
balance                any of the outputs stops backpressuring; emits the element to the first available output                                    all of the outputs backpressure                                                                                                 upstream completes
=====================  =========================================================================================================================   ==============================================================================================================================  =====================================================================================

.. [1] If a Future fails, the stream also fails (unless a different supervision strategy is applied)
.. [2] Except if the encapsulated computation is not fast enough
.. [3] Until the end of stream it is not possible to know whether new substreams will be needed or not
