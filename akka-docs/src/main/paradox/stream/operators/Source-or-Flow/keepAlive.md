# keepAlive

Injects additional (configured) elements if upstream does not emit for a configured amount of time.

@ref[Time aware operators](../index.md#time-aware-operators)

## Signature

@apidoc[Source.keepAlive](Source) { scala="#keepAlive[U&gt;:Out](maxIdle:scala.concurrent.duration.FiniteDuration,injectedElem:()=&gt;U):FlowOps.this.Repr[U]" java="#keepAlive(java.time.Duration,akka.japi.function.Creator)" }
@apidoc[Flow.keepAlive](Flow) { scala="#keepAlive[U&gt;:Out](maxIdle:scala.concurrent.duration.FiniteDuration,injectedElem:()=&gt;U):FlowOps.this.Repr[U]" java="#keepAlive(java.time.Duration,akka.japi.function.Creator)" }


## Description

Injects additional (configured) elements if upstream does not emit for a configured amount of time.

## Reactive Streams semantics

@@@div { .callout }

**emits** when upstream emits an element or if the upstream was idle for the configured period

**backpressures** when downstream backpressures

**completes** when upstream completes

**cancels** when downstream cancels

@@@

