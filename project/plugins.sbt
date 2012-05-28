
resolvers += Classpaths.typesafeResolver

addSbtPlugin("com.typesafe.sbtmultijvm" % "sbt-multi-jvm" % "0.1.9")

addSbtPlugin("com.typesafe.sbtscalariform" % "sbtscalariform" % "0.4.0")

resolvers += Resolver.url("sbt-plugin-releases", new URL("http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases/"))(Resolver.ivyStylePatterns)

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.3")

// ls-sbt is not published for 0.11.3

//resolvers ++= Seq(
//  "less is" at "http://repo.lessis.me",
//  "coda" at "http://repo.codahale.com")

//addSbtPlugin("me.lessis" % "ls-sbt" % "0.1.1")
