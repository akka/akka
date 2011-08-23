
resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies += "com.typesafe.sbt-multi-jvm" %% "sbt-multi-jvm" % "0.1.4"

libraryDependencies += "com.typesafe.sbt-scalariform" %% "sbt-scalariform" % "0.1.2"

resolvers += {
      val typesafeRepoUrl = new java.net.URL("http://repo.typesafe.com/typesafe/releases")
            val pattern = Patterns(false, "[organisation]/[module]/[sbtversion]/[revision]/[type]s/[module](-[classifier])-[revision].[ext]")
                    Resolver.url("Typesafe Repository", typesafeRepoUrl)(pattern)
}

libraryDependencies <<= (libraryDependencies, sbtVersion) { (deps, version) => 
  deps :+ ("com.typesafe.sbteclipse" %% "sbteclipse" % "1.3-RC3" extra("sbtversion" -> version))
}

