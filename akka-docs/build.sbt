import akka.{ AkkaBuild, Dependencies, Formatting, GitHub }
import akka.ValidatePullRequest._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

AkkaBuild.defaultSettings
AkkaBuild.dontPublishSettings
Formatting.docFormatSettings
Dependencies.docs

unmanagedSourceDirectories in ScalariformKeys.format in Test <<= unmanagedSourceDirectories in Test
additionalTasks in ValidatePR += paradox in Compile

enablePlugins(ScaladocNoVerificationOfDiagrams)
disablePlugins(MimaPlugin)
enablePlugins(AkkaParadoxPlugin)

name in (Compile, paradox) := "Akka"

paradoxProperties ++= Map(
  "akka.canonical.base_url" -> "http://doc.akka.io/docs/akka/current",
  "github.base_url" -> GitHub.url(version.value),
  "extref.wikipedia.base_url" -> "https://en.wikipedia.org/wiki/%s",
  "extref.github.base_url" -> ("http://github.com/akka/akka/tree/" + (if (isSnapshot.value) "master" else "v" + version.value) + "/%s"),
  "extref.samples.base_url" -> "http://github.com/akka/akka-samples/tree/2.5/%s",
  "extref.ecs.base_url" -> "https://example.lightbend.com/v1/download/%s",
  "scala.version" -> scalaVersion.value,
  "scala.binary_version" -> scalaBinaryVersion.value,
  "akka.version" -> version.value,
  "sigar_loader.version" -> "1.6.6-rev002",
  "algolia.docsearch.api_key" -> "543bad5ad786495d9ccd445ed34ed082",
  "algolia.docsearch.index_name" -> "akka_io",
  "google.analytics.account" -> "UA-21117439-1",
  "google.analytics.domain.name" -> "akka.io",
  "snip.code.base_dir" -> (sourceDirectory in Test).value.getAbsolutePath,
  "snip.akka.base_dir" -> ((baseDirectory in Test).value / "..").getAbsolutePath
)
paradoxGroups := Map("Languages" -> Seq("Scala", "Java"))

resolvers += Resolver.jcenterRepo
