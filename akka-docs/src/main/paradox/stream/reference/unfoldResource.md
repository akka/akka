# unfoldResource

Wrap any resource that can be opened, queried for next element (in a blocking way) and closed using three distinct functions into a source.

## Signature

## Description

Wrap any resource that can be opened, queried for next element (in a blocking way) and closed using three distinct functions into a source.


@@@div { .callout }

**emits** when there is demand and read @scala[function] @java[method] returns value

**completes** when read function returns `None`

@@@

## Example

