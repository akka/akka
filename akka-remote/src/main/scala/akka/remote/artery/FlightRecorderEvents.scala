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

  // Aeron Sink events
  val AeronSink_Started = 13
  val AeronSink_TaskRunnerRemoved = 14
  val AeronSink_PublicationClosed = 15
  val AeronSink_Stopped = 16
  val AeronSink_EnvelopeGrabbed = 17
  val AeronSink_EnvelopeOffered = 18
  val AeronSink_GaveUpEnvelope = 19
  val AeronSink_DelegateToTaskRunner = 20
  val AeronSink_ReturnFromTaskRunner = 21

  // Aeron Source events
  val AeronSource_Started = 22
  val AeronSource_Stopped = 23
  val AeronSource_Received = 24
  val AeronSource_DelegateToTaskRunner = 25
  val AeronSource_ReturnFromTaskRunner = 26

  // Compression events
  val Compression_CompressedActorRef = 25
  val Compression_AllocatedActorRefCompressionId = 26
  val Compression_CompressedManifest = 27
  val Compression_AllocatedManifestCompressionId = 28

}
