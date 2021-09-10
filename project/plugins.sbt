libraryDependencies += Defaults.sbtPluginExtra(
  "com.eed3si9n" % "sbt-assembly" % "1.1.0",
  (pluginCrossBuild / sbtBinaryVersion).value,
  (pluginCrossBuild / scalaBinaryVersion).value)

addSbtPlugin("com.lightbend.sbt" % "sbt-java-formatter" % "0.6.1")
addSbtPlugin("com.lightbend.sbt" % "sbt-bill-of-materials" % "1.0.2")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.30")
// sbt-osgi 0.9.5 is available but breaks including jdk9-only classes
// sbt-osgi 0.9.6 is available but breaks populating akka-protobuf-v3
addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.9.4")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.8.1")
addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.3")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.3")
addSbtPlugin("io.spray" % "sbt-boilerplate" % "0.6.1")
addSbtPlugin("com.lightbend.akka" % "sbt-paradox-akka" % "0.39")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.6.0")
addSbtPlugin("com.hpe.sbt" % "sbt-pull-request-validator" % "1.0.0")
addSbtPlugin("net.bzzt" % "sbt-reproducible-builds" % "0.30")

addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.5.7")
addSbtPlugin("com.lightbend.sbt" % "sbt-publish-rsync" % "0.2")
