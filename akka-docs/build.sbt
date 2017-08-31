import akka.{ AkkaBuild, Dependencies, Formatting, GitHub }
import akka.ValidatePullRequest._
import com.typesafe.sbt.SbtScalariform.ScalariformKeys

AkkaBuild.defaultSettings
AkkaBuild.dontPublishSettings
Formatting.docFormatSettings
Dependencies.docs

unmanagedSourceDirectories in ScalariformKeys.format in Test := (unmanagedSourceDirectories in Test).value
additionalTasks in ValidatePR += paradox in Compile

enablePlugins(ScaladocNoVerificationOfDiagrams)
disablePlugins(MimaPlugin)
enablePlugins(AkkaParadoxPlugin)

name in (Compile, paradox) := "Akka"

val paradoxBrowse = taskKey[Unit]("Open the docs in the default browser")
paradoxBrowse := {
  import java.awt.Desktop
  val rootDocFile = (target in (Compile, paradox)).value / "index.html"
  val log = streams.value.log
  if (!rootDocFile.exists()) log.info("No generated docs found, generate with the 'paradox' task")
  else if (Desktop.isDesktopSupported) Desktop.getDesktop.open(rootDocFile)
  else log.info(s"Couldn't open default browser, but docs are at $rootDocFile")
}

paradoxProperties ++= Map(
  "akka.canonical.base_url" -> "http://doc.akka.io/docs/akka/current",
  "github.base_url" -> GitHub.url(version.value), // for links like this: @github[#1](#1) or @github[83986f9](83986f9)
  "extref.akka.http.base_url" -> "http://doc.akka.io/docs/akka-http/current/%s",
  "extref.wikipedia.base_url" -> "https://en.wikipedia.org/wiki/%s",
  "extref.github.base_url" -> (GitHub.url(version.value) + "/%s"), // for links to our sources
  "extref.samples.base_url" -> "https://github.com/akka/akka-samples/tree/2.5/%s",
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
  "snip.akka.base_dir" -> ((baseDirectory in Test).value / "..").getAbsolutePath,
  "fiddle.code.base_dir" -> (sourceDirectory in Test).value.getAbsolutePath
)
paradoxGroups := Map("Languages" -> Seq("Scala", "Java"))

resolvers += Resolver.jcenterRepo
