
resolvers += Classpaths.typesafeResolver

// these comment markers are for including code into the docs
//#sbt-multi-jvm
addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.3.6")
//#sbt-multi-jvm

addSbtPlugin("com.typesafe.sbt" % "sbt-scalariform" % "1.0.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-site" % "0.6.2")

addSbtPlugin("com.typesafe.sbtosgi" % "sbtosgi" % "0.3.0")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.3")

addSbtPlugin("me.lessis" % "ls-sbt" % "0.1.2")

addSbtPlugin("com.typesafe.sbt" % "sbt-pgp" % "0.8")

addSbtPlugin("com.typesafe.sbt" % "sbt-s3" % "0.5")
