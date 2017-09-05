resolvers += Classpaths.typesafeResolver

// need this to resolve http://jcenter.bintray.com/org/jenkins-ci/jenkins/1.26/
// which is used by plugin "org.kohsuke" % "github-api" % "1.68"
resolvers += "Bintray Jcenter" at "https://jcenter.bintray.com/"

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.15")

libraryDependencies += "org.kohsuke" % "github-api" % "1.68"

addSbtPlugin("com.lightbend" % "sbt-whitesource" % "0.1.6")

addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "0.9.3")
