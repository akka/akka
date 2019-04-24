# Migration Guide 2.5.x to 2.6.x

## akka-camel removed

After being deprecated in 2.5.0, the akka-camel module has been removed in 2.6.
As an alternative we recommend [Alpakka](https://doc.akka.io/docs/alpakka/current/).

This is of course not a drop-in replacement. If there is community interest we
are open to setting up akka-camel as a separate community-maintained
repository.

## Scala 2.11 no longer supported

If you are still using Scala 2.11 then you must upgrade to 2.12 or 2.13
