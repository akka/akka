
resolvers += Classpaths.typesafeResolver

addSbtPlugin("com.typesafe.sbtmultijvm" % "sbt-multi-jvm" % "0.2.0-M3")

addSbtPlugin("com.typesafe.sbtscalariform" % "sbtscalariform" % "0.4.0")

addSbtPlugin("com.typesafe.sbtosgi" % "sbtosgi" % "0.2.0")

resolvers ++= Seq(
  // needed for sbt-assembly, which comes with sbt-multi-jvm
  Resolver.url("sbtonline", url("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases"))(Resolver.ivyStylePatterns),
  "less is" at "http://repo.lessis.me",
  "coda" at "http://repo.codahale.com")

// addSbtPlugin("me.lessis" % "ls-sbt" % "0.1.1")
