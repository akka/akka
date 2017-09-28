// need this to resolve http://jcenter.bintray.com/org/jenkins-ci/jenkins/1.26/
// which is used by plugin "org.kohsuke" % "github-api" % "1.68"
resolvers += "Bintray Jcenter" at "https://jcenter.bintray.com/"
libraryDependencies += "org.kohsuke" % "github-api" % "1.68"

// these comment markers are for including code into the docs
//#sbt-multi-jvm
addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.4.0")
//#sbt-multi-jvm

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "1.3.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.9.2")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.18")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.0-M1")
addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.1")
// addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "2.0.0") // FIXME broken on jdk9
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.2.27")
//addSbtPlugin("pl.project13.sbt" % "sbt-jol" % "0.1.3") // TODO but not really needed for now
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.2.2-RC2")
addSbtPlugin("io.spray" % "sbt-boilerplate" % "0.6.1")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.3.1")
addSbtPlugin("com.lightbend.akka" % "sbt-paradox-akka" % "0.4")
addSbtPlugin("com.lightbend" % "sbt-whitesource" % "0.1.6")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")

// JDK9 features support via Multi-Release JARs
addSbtPlugin("com.lightbend.sbt" % "sbt-multi-release-jar" % "0.1.0") 

// version with sbt 1.0 support is in snapshots for now
//resolvers += Resolver.sonatypeRepo("snapshots")
//addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.8.3-SNAPSHOT") // TODO