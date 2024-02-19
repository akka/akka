# Projects used to verify native-image support works, run by CI or locally.

 * `scala-local` local actor system app in Scala, exercising as many single-system features as possible
 * `scala-cluster` clustered actor system app in Scala, exercising cluster and cluster tools

## Running locally

`sbt publishLocal` akka itself.

Build test project with `sbt -Dakka.version=[local-snapshot-version] nativeImage` and then start the generated native 
image.

