# logWithMarker

Log elements flowing through the stream as well as completion and erroring.

@ref[Simple operators](../index.md#simple-operators)

## Signature

@apidoc[Source.logWithMarker](Source) { scala="#logWithMarker(name:String,marker:Out=&gt;akka.event.LogMarker,extract:Out=&gt;Any)(implicitlog:akka.event.MarkerLoggingAdapter):FlowOps.this.Repr[Out]" java="#logWithMarker(java.lang.String,akka.japi.function.Function)" }
@apidoc[Flow.logWithMarker](Flow) { scala="#logWithMarker(name:String,marker:Out=&gt;akka.event.LogMarker,extract:Out=&gt;Any)(implicitlog:akka.event.MarkerLoggingAdapter):FlowOps.this.Repr[Out]" java="#logWithMarker(java.lang.String,akka.japi.function.Function)" }


## Description

Log elements flowing through the stream as well as completion and erroring. By default element and
completion signals are logged on debug level, and errors are logged on Error level.
This can be changed by calling @scala[`Attributes.logLevels(...)`] @java[`Attributes.createLogLevels(...)`] on the given Flow.

See also @ref:[log](log.md).

## Example

Scala
:   @@snip [SourceOrFlow.scala](/akka-docs/src/test/scala/docs/stream/operators/sourceorflow/LogWithMarker.scala) { #logWithMarker }

Java
:   @@snip [SourceOrFlow.java](/akka-docs/src/test/java/jdocs/stream/operators/SourceOrFlow.java) { #logWithMarker }

## Reactive Streams semantics 

@@@div { .callout }

**emits** when upstream emits

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@
