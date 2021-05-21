libraryDependencies += Defaults.sbtPluginExtra(
  "com.eed3si9n" % "sbt-assembly" % "0.15.0",
  (pluginCrossBuild / sbtBinaryVersion).value,
  (pluginCrossBuild / scalaBinaryVersion).value)

// these comment markers are for including code into the docs
//#sbt-multi-jvm
addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.4.0")
//#sbt-multi-jvm

addSbtPlugin("com.lightbend.sbt" % "sbt-java-formatter" % "0.6.0")
addSbtPlugin("com.lightbend.sbt" % "sbt-bill-of-materials" % "1.0.2")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.27")
// sbt-osgi 0.9.5 is available but breaks including jdk9-only classes
// sbt-osgi 0.9.6 is available but breaks populating akka-protobuf-v3
addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.9.4")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.8.1")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.1.1")
addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.3")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.0")
addSbtPlugin("io.spray" % "sbt-boilerplate" % "0.6.1")
addSbtPlugin("com.lightbend.akka" % "sbt-paradox-akka" % "0.38")
addSbtPlugin("com.lightbend" % "sbt-whitesource" % "0.1.18")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.6.0")
addSbtPlugin("com.hpe.sbt" % "sbt-pull-request-validator" % "1.0.0")
addSbtPlugin("net.bzzt" % "sbt-reproducible-builds" % "0.25")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")
addSbtPlugin("com.lightbend.sbt" % "sbt-publish-rsync" % "0.2")
