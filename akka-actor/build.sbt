import akka.{ AkkaBuild, Formatting, OSGi, Dependencies, Version }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.actor

Dependencies.actor

MimaKeys.previousArtifacts := akkaPreviousArtifacts("akka-actor").value

enablePlugins(spray.boilerplate.BoilerplatePlugin)

Version.versionSettings
