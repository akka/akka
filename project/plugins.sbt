resolvers += Classpaths.sbtPluginReleases
resolvers += Classpaths.typesafeReleases
resolvers += Resolver.sonatypeRepo("releases") // to more quickly obtain paradox rigth after release

// need this to resolve http://jcenter.bintray.com/org/jenkins-ci/jenkins/1.26/
// which is used by plugin "org.kohsuke" % "github-api" % "1.68"
resolvers += Resolver.jcenterRepo

addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.3.8")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.15")
addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.1")
addSbtPlugin("com.dwijnand" % "sbt-dynver" % "1.1.1")
addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.9.1")
addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.3.3")
addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "1.0.0")
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.2.27")
addSbtPlugin("pl.project13.sbt" % "sbt-jol" % "0.1.1")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.8.2") // for advanced PR validation features
addSbtPlugin("io.spray" % "sbt-boilerplate" % "0.6.0")
addSbtPlugin("org.foundweekends" % "sbt-bintray" % "0.5.1")
addSbtPlugin("com.lightbend.sbt" % "sbt-javaagent" % "0.1.2")
addSbtPlugin("com.lightbend.akka" % "sbt-paradox-akka" % "0.4")
addSbtPlugin("com.lightbend" % "sbt-whitesource" % "0.1.2")

libraryDependencies += "org.kohsuke" % "github-api" % "1.68"
