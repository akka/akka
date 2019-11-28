# mergePrioritized

Merge multiple sources.

@ref[Fan-in operators](../index.md#fan-in-operators)

## Signature

## Description

Merge multiple sources. Prefer sources depending on priorities if all sources has elements ready. If a subset of all
sources has elements ready the relative priorities for those sources are used to prioritise.

## Reactive Streams semantics

@@@div { .callout }

**emits** when one of the inputs has an element available, preferring inputs based on their priorities if multiple have elements available

**backpressures** when downstream backpressures

**completes** when all upstreams complete (This behavior is changeable to completing when any upstream completes by setting `eagerComplete=true`.)

@@@

