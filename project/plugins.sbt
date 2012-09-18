
resolvers += Classpaths.typesafeResolver

addSbtPlugin("com.typesafe.sbt" % "sbt-multi-jvm" % "0.3.0")

addSbtPlugin("com.typesafe.sbtscalariform" % "sbtscalariform" % "0.4.0")

addSbtPlugin("com.typesafe.sbtosgi" % "sbtosgi" % "0.3.0")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.3")

resolvers ++= Seq(
  // needed for sbt-assembly, which comes with sbt-multi-jvm
  Resolver.url("sbtonline", url("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases"))(Resolver.ivyStylePatterns),
  "less is" at "http://repo.lessis.me",
  "coda" at "http://repo.codahale.com")

// addSbtPlugin("me.lessis" % "ls-sbt" % "0.1.1")
