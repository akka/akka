# intersperse

Intersperse stream with provided element similar to `List.

## Signature

## Description

Intersperse stream with provided element similar to `List.mkString`. It can inject start and end marker elements to stream.


@@@div { .callout }

**emits** when upstream emits an element or before with the *start* element if provided

**backpressures** when downstream backpressures

**completes** when upstream completes

@@@

## Example

