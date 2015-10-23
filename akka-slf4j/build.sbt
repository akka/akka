import akka.{ AkkaBuild, Dependencies, Formatting, OSGi }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.slf4j

Dependencies.slf4j

MimaKeys.previousArtifacts := akkaPreviousArtifacts("akka-slf4j").value
