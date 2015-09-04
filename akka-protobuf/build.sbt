import akka.{ AkkaBuild, Formatting, OSGi, Unidoc, Dependencies }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

enablePlugins(JmhPlugin, ScaladocNoVerificationOfDiagrams)

OSGi.protobuf

