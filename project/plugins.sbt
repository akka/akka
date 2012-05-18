
resolvers += Classpaths.typesafeResolver

addSbtPlugin("com.typesafe.sbtmultijvm" % "sbt-multi-jvm" % "0.2.0-SNAPSHOT")

addSbtPlugin("com.typesafe.schoir" % "schoir" % "0.1.2")

addSbtPlugin("com.typesafe.sbtscalariform" % "sbtscalariform" % "0.3.1")

resolvers ++= Seq(
  "less is" at "http://repo.lessis.me",
  "coda" at "http://repo.codahale.com")

addSbtPlugin("me.lessis" % "ls-sbt" % "0.1.1")
