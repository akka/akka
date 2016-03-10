import akka.{ AkkaBuild, Formatting, OSGi, Dependencies, Version }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.actor
Dependencies.actor
Version.versionSettings

enablePlugins(spray.boilerplate.BoilerplatePlugin)
