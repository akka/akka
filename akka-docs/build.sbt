import akka.{ AkkaBuild, Dependencies, Formatting }
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
enablePlugins(ParadoxPlugin)
enablePlugins(AkkaParadoxPlugin)

paradoxProperties ++= Map(
  "extref.wikipedia.base_url" -> "https://en.wikipedia.org/wiki/%s",
  "extref.github.base_url" -> ("http://github.com/akka/akka/tree/" + (if (isSnapshot.value) "master" else "v" + version.value) + "/%s"),
  "extref.samples.base_url" -> "http://github.com/akka/akka-samples/tree/master/%s",
  "extref.ecs.base_url" -> "https://example.lightbend.com/v1/download/%s",
  "scala.version" -> scalaVersion.value,
  "scala.binary_version" -> scalaBinaryVersion.value,
  "akka.version" -> version.value,
  "sigar_loader.version" -> "1.6.6-rev002",
  "snip.code.base_dir" -> (sourceDirectory in Test).value.getAbsolutePath,
  "snip.akka.base_dir" -> ((baseDirectory in Test).value / "..").getAbsolutePath
)
paradoxGroups := Map("Languages" -> Seq("Scala", "Java"))

resolvers += Resolver.bintrayRepo("2m", "maven")
resolvers += Resolver.bintrayRepo("2m", "sbt-plugin-releases")
