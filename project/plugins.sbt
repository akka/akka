
resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("com.typesafe.sbtmultijvm" % "sbt-multi-jvm" % "0.1.6")

addSbtPlugin("com.typesafe.sbtscalariform" % "sbt-scalariform" % "0.1.3")

libraryDependencies += "org.scalariform" % "scalariform_2.9.0" % "0.1.0"

scalacOptions += "-deprecation"