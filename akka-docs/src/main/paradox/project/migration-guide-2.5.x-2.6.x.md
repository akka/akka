# Migration Guide 2.5.x to 2.6.x

## Scala 2.11 no longer supported

If you are still using Scala 2.11 then you must upgrade to 2.12 or 2.13

### Actor DSL removal

Actor DSL is a rarely used feature and has been deprecated since `2.5.0`.
Use plain `system.actorOf` instead of the DSL to create Actors if you have been using it.

