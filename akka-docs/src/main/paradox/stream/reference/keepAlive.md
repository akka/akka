# keepAlive

Injects additional (configured) elements if upstream does not emit for a configured amount of time.

## Signature

## Description

Injects additional (configured) elements if upstream does not emit for a configured amount of time.


@@@div { .callout }

**emits** when upstream emits an element or if the upstream was idle for the configured period

**backpressures** when downstream backpressures

**completes** when upstream completes

**cancels** when downstream cancels

@@@

## Example

