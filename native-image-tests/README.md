# Projects used to verify native-image support works, run by CI or locally.

 * `scala-local` local actor system app in Scala
 * FIXME `scala-cluster` clustered actor system app in Scala
 * FIXME `java-local` local actor system app in Java
 * FIXME `java-cluster` clustered actor system app in Java

## Running locally

`sbt publishLocal` and or `sbt publishM2` depending on Java or Scala.

For scala test projects, build test project with `sbt -Dakka.version=[local-snapshot-version] nativeImage` and 
then start the generated native image.

FIXME verifiation
