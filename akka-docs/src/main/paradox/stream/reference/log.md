# log

Log elements flowing through the stream as well as completion and erroring.

## Signature

## Description

Log elements flowing through the stream as well as completion and erroring. By default element and
completion signals are logged on debug level, and errors are logged on Error level.
This can be changed by calling @scala[`Attributes.logLevels(...)`] @java[`Attributes.createLogLevels(...)`] on the given Flow.


@@@div { .callout }

**emits** when upstream emits

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

## Example

