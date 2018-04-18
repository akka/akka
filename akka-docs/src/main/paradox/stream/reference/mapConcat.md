# mapConcat

Transform each element into zero or more elements that are individually passed downstream.

## Signature

## Description

Transform each element into zero or more elements that are individually passed downstream.


@@@div { .callout }

**emits** when the mapping function returns an element or there are still remaining elements from the previously calculated collection

**backpressures** when downstream backpressures or there are still available elements from the previously calculated collection

**completes** when upstream completes and all remaining elements has been emitted

@@@

## Example

