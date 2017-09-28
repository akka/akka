import akka.{ AkkaBuild, Formatting, OSGi, Dependencies, VersionGenerator }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.actor
Dependencies.actor
VersionGenerator.versionSettings
unmanagedSourceDirectories in Compile += {
  val ver = scalaVersion.value.take(4)
  (scalaSource in Compile).value.getParentFile / s"scala-$ver"
}

enablePlugins(akka.Unidoc, spray.boilerplate.BoilerplatePlugin)
