package akka.remote.artery

object FlightRecorderEvents {

  val NoMetaData = Array.empty[Byte]

  // Top level remoting events
  val Transport_MediaDriverStarted = 0
  val Transport_AeronStarted = 1
  val Transport_AeronErrorLogStarted = 2
  val Transport_TaskRunnerStarted = 3
  val Transport_UniqueAddressSet = 4
  val Transport_MaterializerStarted = 5
  val Transport_StartupFinished = 6
  val Transport_OnAvailableImage = 7
  val Transport_KillSwitchPulled = 8
  val Transport_Stopped = 9
  val Transport_AeronErrorLogTaskStopped = 10
  val Transport_MediaFileDeleted = 11
  val Transport_FlightRecorderClose = 12
  val Transport_SendQueueOverflow = 13

  // Aeron Sink events
  val AeronSink_Started = 50
  val AeronSink_TaskRunnerRemoved = 51
  val AeronSink_PublicationClosed = 52
  val AeronSink_Stopped = 53
  val AeronSink_EnvelopeGrabbed = 54
  val AeronSink_EnvelopeOffered = 55
  val AeronSink_GaveUpEnvelope = 56
  val AeronSink_DelegateToTaskRunner = 57
  val AeronSink_ReturnFromTaskRunner = 58

  // Aeron Source events
  val AeronSource_Started = 70
  val AeronSource_Stopped = 71
  val AeronSource_Received = 72
  val AeronSource_DelegateToTaskRunner = 72
  val AeronSource_ReturnFromTaskRunner = 73

  // Compression events
  val Compression_CompressedActorRef = 90
  val Compression_AllocatedActorRefCompressionId = 91
  val Compression_CompressedManifest = 91
  val Compression_AllocatedManifestCompressionId = 92

}
