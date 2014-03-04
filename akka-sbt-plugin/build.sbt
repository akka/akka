import scalariform.formatter.preferences._

organization := "com.typesafe.akka"

name := "akka-sbt-plugin"

version := "2.2.4"

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-encoding", "UTF-8"
)

scalariformSettings

ScalariformKeys.preferences := ScalariformKeys.preferences.value
    .setPreference(RewriteArrowSymbols, true)
    .setPreference(AlignParameters, true)
    .setPreference(AlignSingleLineCaseStatements, true)

sbtPlugin := true

publishTo := {
  val baseUrl = "http://scalasbt.artifactoryonline.com/scalasbt"
  val kind = if (isSnapshot.value) "snapshots" else "releases"
  val name = s"sbt-plugin-$kind"
  Some(Resolver.url(s"publish-$name", url(s"$baseUrl/$name"))(Resolver.ivyStylePatterns))
}

publishMavenStyle := false
