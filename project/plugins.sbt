resolvers += Classpaths.typesafeResolver

// need this to resolve http://jcenter.bintray.com/org/jenkins-ci/jenkins/1.26/
// which is used by plugin "org.kohsuke" % "github-api" % "1.68"
resolvers += "Bintray Jcenter" at "https://jcenter.bintray.com/"

// these comment markers are for including code into the docs
//#sbt-multi-jvm
addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.3.8")
//#sbt-multi-jvm

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.6.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "0.7.1")

addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.9.1")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.14")

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.0.0")

addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.3.3")

addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "1.0.0")

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.2.17")

addSbtPlugin("pl.project13.sbt" % "sbt-jol" % "0.1.1")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.0.0-RC1")

// for advanced PR validation features
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.8.2")

libraryDependencies += "org.kohsuke" % "github-api" % "1.68"

addSbtPlugin("io.spray" % "sbt-boilerplate" % "0.6.0")

addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.1.10")

addSbtPlugin("com.lightbend.akka" % "sbt-paradox-akka" % "0.3")

addSbtPlugin("com.lightbend" % "sbt-whitesource" % "0.1.2")

addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")
