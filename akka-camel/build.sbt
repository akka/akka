import akka.{ AkkaBuild, Dependencies, Formatting, OSGi }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.camel

Dependencies.camel

MimaKeys.previousArtifacts := akkaPreviousArtifacts("akka-camel").value
