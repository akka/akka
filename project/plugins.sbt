// need this to resolve http://jcenter.bintray.com/org/jenkins-ci/jenkins/1.26/
// which is used by plugin "org.kohsuke" % "github-api" % "1.68"
resolvers += "Bintray Jcenter" at "https://jcenter.bintray.com/"
libraryDependencies += "org.kohsuke" % "github-api" % "1.68"

// these comment markers are for including code into the docs
//#sbt-multi-jvm
addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.4.0")
//#sbt-multi-jvm

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.9.3")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.2.0")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.0-M1")
addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.1")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "2.0.1")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.3")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.2.2-RC2")
addSbtPlugin("io.spray" % "sbt-boilerplate" % "0.6.1")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.3.1")
addSbtPlugin("com.lightbend.akka" % "sbt-paradox-akka" % "0.6")
addSbtPlugin("com.lightbend" % "sbt-whitesource" % "0.1.10")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.0") // for advanced PR validation features
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.0.0") // for maintenance of copyright file header

// used for @unidoc directive
libraryDependencies += "io.github.lukehutch" % "fast-classpath-scanner" % "2.12.3"
