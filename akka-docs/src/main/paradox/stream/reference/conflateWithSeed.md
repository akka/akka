# conflateWithSeed

Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there
is backpressure.

## Signature

## Description

Allow for a slower downstream by passing incoming elements and a summary into an aggregate function as long as there
is backpressure. When backpressure starts or there is no backpressure element is passed into a `seed` function to
transform it to the summary type.


@@@div { .callout }

**emits** when downstream stops backpressuring and there is a conflated element available

**backpressures** when the aggregate or seed functions cannot keep up with incoming elements

**completes** when upstream completes

@@@

## Example

