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

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.15")

libraryDependencies += "org.kohsuke" % "github-api" % "1.68"

addSbtPlugin("com.lightbend" % "sbt-whitesource" % "0.1.6")

addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")
