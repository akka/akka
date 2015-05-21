import akka.{ AkkaBuild, Formatting, OSGi, Dependencies }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.actor

Dependencies.actor

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-actor").value

spray.boilerplate.BoilerplatePlugin.Boilerplate.settings
