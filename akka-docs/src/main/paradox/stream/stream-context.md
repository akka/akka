# Context Propagation

It can be convenient to attach metadata to each element in the stream.

For example, when reading from an external data source it can be
useful to keep track of the read offset, so it can be marked as processed
when the element reaches the @apidoc[Sink].

For this use case we provide the @apidoc[SourceWithContext] and
@apidoc[FlowWithContext] variations on @apidoc[Source] and
@apidoc[Flow].

Essentially, a @apidoc[FlowWithContext] is just a @apidoc[Flow] that
contains @scala[tuples]@java[pairs] of element and context, but the
advantage is in the operators: most operators on @apidoc[FlowWithContext]
will work on the element rather than on the @scala[tuple]@java[pair],
allowing you to focus on your application logic rather without worrying
about the context.

## Restrictions

Not all operations that are available on @apidoc[Flow] are also available
on @apidoc[FlowWithContext]. This is intentional: in the use case of
keeping track of a read offset, if the @apidoc[FlowWithContext] was
allowed to arbitrarily filter and reorder the stream, the @apidoc[Sink]
would have no way to determine whether an element was skipped
or merely reordered and still in flight.

For this reason, @apidoc[FlowWithContext] allows filtering operations
(such as `filter`, `filterNot`, `collect`, etc.) and grouping operations
(such as `grouped`, `sliding`, etc.) but not reordering operations
(such as `mapAsyncUnordered` and `statefulMapConcat`). Finally,
also 'one-to-n' operations such as `mapConcat` are allowed.

Filtering operations will drop the context along with dropped elements,
while grouping operations will keep all contexts from the elements in
the group. Streaming one-to-many operations such as `mapConcat`
associate the original context with each of the produced elements.

As an escape hatch, there is a `via` operator that allows you to
insert an arbitrary @apidoc[Flow] that can process the
@scala[tuples]@java[pairs] of elements and context in any way
desired. When using this operator, it is the responsibility of the
implementor to make sure this @apidoc[Flow] does not perform
any operations (such as reordering) that might break assumptions
made by the @apidoc[Sink] consuming the context elements.

## Creation

The simplest way to create a @apidoc[SourceWithContext] is to
first create a regular @apidoc[Source] with elements from which
the context can be extracted, and then use
@ref[Source.asSourceWithContext](operators/Source/asSourceWithContext.md).

## Composition

When you have a @apidoc[SourceWithContext] `source` that produces
elements of type `Foo` with a context of type `Ctx`, and a
@apidoc[Flow] `flow` from `Foo` to `Bar`,  you cannot simply
`source.via(flow)` to arrive at a @apidoc[SourceWithContext] that
produces elements of type `Bar` with contexts of type `Ctx`. The
reason for this is that `flow` might reorder the elements flowing
through it, making `via` challenging to implement.

Due to this there is a `unsafeDataVia` that can be used instead however no
protection is offered to prevent reordering or dropping/duplicating elements
from stream so use this operation with great care.

There is also a @ref[Flow.asFlowWithContext](operators/Flow/asFlowWithContext.md)
which can be used when the types used in the inner
@apidoc[Flow] have room to hold the context. If this is not the
case, a better solution is usually to build the flow from the ground
up as a @apidoc[FlowWithContext], instead of first building a
@apidoc[Flow] and trying to convert it to @apidoc[FlowWithContext]
after-the-fact.
