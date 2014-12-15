addSbtPlugin("com.typesafe.rp" % "sbt-typesafe-rp" % "15v01p01")

val typesafeUrl = "https://private-repo.typesafe.com/typesafe/for-subscribers-only/DFDB5DD187A28462DDAF7AB39A95A6AE65983B23"

resolvers += "typesafe-rp-mvn" at typesafeUrl

resolvers += Resolver.url("typesafe-rp-ivy", url(typesafeUrl))(Resolver.ivyStylePatterns)

resolvers += "typesafe-releases" at "https://repo.typesafe.com/typesafe/releases/"
