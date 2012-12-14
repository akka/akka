import sbt._
import Defaults._


resolvers += Classpaths.typesafeResolver

// these comment markers are for including code into the docs
//#sbt-multi-jvm
addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.3.4")
//#sbt-multi-jvm

addSbtPlugin("com.typesafe.sbt" % "sbt-scalariform" % "1.0.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "0.7.0-M1")

addSbtPlugin("com.typesafe.sbtosgi" % "sbtosgi" % "0.3.0")

resolvers ++= Seq(
  // needed for sbt-assembly, which comes with sbt-multi-jvm
  Resolver.url("sbtonline", url("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases"))(Resolver.ivyStylePatterns),
  "less is" at "http://repo.lessis.me",
  "coda" at "http://repo.codahale.com",
  "sbt-idea-repo" at "http://mpeltonen.github.com/maven/"
)


/*libraryDependencies += sbtPluginExtra(
  "com.github.mpeltonen" % "sbt-idea" % "1.2.0-SNAPSHOT",
  "0.11.3",
  "2.9.1")*/
addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.1.0")

addSbtPlugin("me.lessis" % "ls-sbt" % "0.1.2")
