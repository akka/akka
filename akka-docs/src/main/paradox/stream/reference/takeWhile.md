# takeWhile

Pass elements downstream as long as a predicate function return true for the element include the element
when the predicate first return false and then complete.

## Signature

## Description

Pass elements downstream as long as a predicate function return true for the element include the element
when the predicate first return false and then complete.


@@@div { .callout }

**emits** while the predicate is true and until the first false result

**backpressures** when downstream backpressures

**completes** when predicate returned false or upstream completes

@@@

## Example

