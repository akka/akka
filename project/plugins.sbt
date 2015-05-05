resolvers += Classpaths.typesafeResolver

// these comment markers are for including code into the docs
//#sbt-multi-jvm
addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.3.8")
//#sbt-multi-jvm

addSbtPlugin("com.typesafe.sbt" % "sbt-scalariform" % "1.2.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "0.7.1")

addSbtPlugin("com.typesafe.sbt" % "sbt-osgi" % "0.6.0")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1-b71a3e88f0a21d6ce5efe7c0580dba9784295358")

resolvers += Resolver.url("2m-sbt-plugins", url("https://dl.bintray.com/2m/sbt-plugins/"))(Resolver.ivyStylePatterns)

addSbtPlugin("com.typesafe.sbt" % "sbt-pgp" % "0.8.3")

addSbtPlugin("com.typesafe.sbt" % "sbt-s3" % "0.5")

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.1.14")

// needed for the akka-sample-hello-kernel
// it is also defined in akka-samples/akka-sample-hello-kernel/project/plugins.sbt
//addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "0.8.0-M2")

libraryDependencies += "com.timgroup" % "java-statsd-client" % "2.0.0"
