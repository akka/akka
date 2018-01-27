// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: ShardingMessages.proto

package akka.cluster.sharding.typed.internal.protobuf;

public final class ShardingMessages {
  private ShardingMessages() {}
  public static void registerAllExtensions(
      akka.protobuf.ExtensionRegistry registry) {
  }
  public interface ShardingEnvelopeOrBuilder
      extends akka.protobuf.MessageOrBuilder {

    // required string entityId = 1;
    /**
     * <code>required string entityId = 1;</code>
     */
    boolean hasEntityId();
    /**
     * <code>required string entityId = 1;</code>
     */
    java.lang.String getEntityId();
    /**
     * <code>required string entityId = 1;</code>
     */
    akka.protobuf.ByteString
        getEntityIdBytes();

    // optional .Payload message = 2;
    /**
     * <code>optional .Payload message = 2;</code>
     */
    boolean hasMessage();
    /**
     * <code>optional .Payload message = 2;</code>
     */
    akka.remote.ContainerFormats.Payload getMessage();
    /**
     * <code>optional .Payload message = 2;</code>
     */
    akka.remote.ContainerFormats.PayloadOrBuilder getMessageOrBuilder();
  }
  /**
   * Protobuf type {@code akka.cluster.sharding.typed.ShardingEnvelope}
   */
  public static final class ShardingEnvelope extends
      akka.protobuf.GeneratedMessage
      implements ShardingEnvelopeOrBuilder {
    // Use ShardingEnvelope.newBuilder() to construct.
    private ShardingEnvelope(akka.protobuf.GeneratedMessage.Builder<?> builder) {
      super(builder);
      this.unknownFields = builder.getUnknownFields();
    }
    private ShardingEnvelope(boolean noInit) { this.unknownFields = akka.protobuf.UnknownFieldSet.getDefaultInstance(); }

    private static final ShardingEnvelope defaultInstance;
    public static ShardingEnvelope getDefaultInstance() {
      return defaultInstance;
    }

    public ShardingEnvelope getDefaultInstanceForType() {
      return defaultInstance;
    }

    private final akka.protobuf.UnknownFieldSet unknownFields;
    @java.lang.Override
    public final akka.protobuf.UnknownFieldSet
        getUnknownFields() {
      return this.unknownFields;
    }
    private ShardingEnvelope(
        akka.protobuf.CodedInputStream input,
        akka.protobuf.ExtensionRegistryLite extensionRegistry)
        throws akka.protobuf.InvalidProtocolBufferException {
      initFields();
      int mutable_bitField0_ = 0;
      akka.protobuf.UnknownFieldSet.Builder unknownFields =
          akka.protobuf.UnknownFieldSet.newBuilder();
      try {
        boolean done = false;
        while (!done) {
          int tag = input.readTag();
          switch (tag) {
            case 0:
              done = true;
              break;
            default: {
              if (!parseUnknownField(input, unknownFields,
                                     extensionRegistry, tag)) {
                done = true;
              }
              break;
            }
            case 10: {
              bitField0_ |= 0x00000001;
              entityId_ = input.readBytes();
              break;
            }
            case 18: {
              akka.remote.ContainerFormats.Payload.Builder subBuilder = null;
              if (((bitField0_ & 0x00000002) == 0x00000002)) {
                subBuilder = message_.toBuilder();
              }
              message_ = input.readMessage(akka.remote.ContainerFormats.Payload.PARSER, extensionRegistry);
              if (subBuilder != null) {
                subBuilder.mergeFrom(message_);
                message_ = subBuilder.buildPartial();
              }
              bitField0_ |= 0x00000002;
              break;
            }
          }
        }
      } catch (akka.protobuf.InvalidProtocolBufferException e) {
        throw e.setUnfinishedMessage(this);
      } catch (java.io.IOException e) {
        throw new akka.protobuf.InvalidProtocolBufferException(
            e.getMessage()).setUnfinishedMessage(this);
      } finally {
        this.unknownFields = unknownFields.build();
        makeExtensionsImmutable();
      }
    }
    public static final akka.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.internal_static_akka_cluster_sharding_typed_ShardingEnvelope_descriptor;
    }

    protected akka.protobuf.GeneratedMessage.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.internal_static_akka_cluster_sharding_typed_ShardingEnvelope_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope.class, akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope.Builder.class);
    }

    public static akka.protobuf.Parser<ShardingEnvelope> PARSER =
        new akka.protobuf.AbstractParser<ShardingEnvelope>() {
      public ShardingEnvelope parsePartialFrom(
          akka.protobuf.CodedInputStream input,
          akka.protobuf.ExtensionRegistryLite extensionRegistry)
          throws akka.protobuf.InvalidProtocolBufferException {
        return new ShardingEnvelope(input, extensionRegistry);
      }
    };

    @java.lang.Override
    public akka.protobuf.Parser<ShardingEnvelope> getParserForType() {
      return PARSER;
    }

    private int bitField0_;
    // required string entityId = 1;
    public static final int ENTITYID_FIELD_NUMBER = 1;
    private java.lang.Object entityId_;
    /**
     * <code>required string entityId = 1;</code>
     */
    public boolean hasEntityId() {
      return ((bitField0_ & 0x00000001) == 0x00000001);
    }
    /**
     * <code>required string entityId = 1;</code>
     */
    public java.lang.String getEntityId() {
      java.lang.Object ref = entityId_;
      if (ref instanceof java.lang.String) {
        return (java.lang.String) ref;
      } else {
        akka.protobuf.ByteString bs = 
            (akka.protobuf.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        if (bs.isValidUtf8()) {
          entityId_ = s;
        }
        return s;
      }
    }
    /**
     * <code>required string entityId = 1;</code>
     */
    public akka.protobuf.ByteString
        getEntityIdBytes() {
      java.lang.Object ref = entityId_;
      if (ref instanceof java.lang.String) {
        akka.protobuf.ByteString b = 
            akka.protobuf.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        entityId_ = b;
        return b;
      } else {
        return (akka.protobuf.ByteString) ref;
      }
    }

    // optional .Payload message = 2;
    public static final int MESSAGE_FIELD_NUMBER = 2;
    private akka.remote.ContainerFormats.Payload message_;
    /**
     * <code>optional .Payload message = 2;</code>
     */
    public boolean hasMessage() {
      return ((bitField0_ & 0x00000002) == 0x00000002);
    }
    /**
     * <code>optional .Payload message = 2;</code>
     */
    public akka.remote.ContainerFormats.Payload getMessage() {
      return message_;
    }
    /**
     * <code>optional .Payload message = 2;</code>
     */
    public akka.remote.ContainerFormats.PayloadOrBuilder getMessageOrBuilder() {
      return message_;
    }

    private void initFields() {
      entityId_ = "";
      message_ = akka.remote.ContainerFormats.Payload.getDefaultInstance();
    }
    private byte memoizedIsInitialized = -1;
    public final boolean isInitialized() {
      byte isInitialized = memoizedIsInitialized;
      if (isInitialized != -1) return isInitialized == 1;

      if (!hasEntityId()) {
        memoizedIsInitialized = 0;
        return false;
      }
      if (hasMessage()) {
        if (!getMessage().isInitialized()) {
          memoizedIsInitialized = 0;
          return false;
        }
      }
      memoizedIsInitialized = 1;
      return true;
    }

    public void writeTo(akka.protobuf.CodedOutputStream output)
                        throws java.io.IOException {
      getSerializedSize();
      if (((bitField0_ & 0x00000001) == 0x00000001)) {
        output.writeBytes(1, getEntityIdBytes());
      }
      if (((bitField0_ & 0x00000002) == 0x00000002)) {
        output.writeMessage(2, message_);
      }
      getUnknownFields().writeTo(output);
    }

    private int memoizedSerializedSize = -1;
    public int getSerializedSize() {
      int size = memoizedSerializedSize;
      if (size != -1) return size;

      size = 0;
      if (((bitField0_ & 0x00000001) == 0x00000001)) {
        size += akka.protobuf.CodedOutputStream
          .computeBytesSize(1, getEntityIdBytes());
      }
      if (((bitField0_ & 0x00000002) == 0x00000002)) {
        size += akka.protobuf.CodedOutputStream
          .computeMessageSize(2, message_);
      }
      size += getUnknownFields().getSerializedSize();
      memoizedSerializedSize = size;
      return size;
    }

    private static final long serialVersionUID = 0L;
    @java.lang.Override
    protected java.lang.Object writeReplace()
        throws java.io.ObjectStreamException {
      return super.writeReplace();
    }

    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(
        akka.protobuf.ByteString data)
        throws akka.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(
        akka.protobuf.ByteString data,
        akka.protobuf.ExtensionRegistryLite extensionRegistry)
        throws akka.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(byte[] data)
        throws akka.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(
        byte[] data,
        akka.protobuf.ExtensionRegistryLite extensionRegistry)
        throws akka.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(java.io.InputStream input)
        throws java.io.IOException {
      return PARSER.parseFrom(input);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(
        java.io.InputStream input,
        akka.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return PARSER.parseFrom(input, extensionRegistry);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseDelimitedFrom(java.io.InputStream input)
        throws java.io.IOException {
      return PARSER.parseDelimitedFrom(input);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseDelimitedFrom(
        java.io.InputStream input,
        akka.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return PARSER.parseDelimitedFrom(input, extensionRegistry);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(
        akka.protobuf.CodedInputStream input)
        throws java.io.IOException {
      return PARSER.parseFrom(input);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(
        akka.protobuf.CodedInputStream input,
        akka.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return PARSER.parseFrom(input, extensionRegistry);
    }

    public static Builder newBuilder() { return Builder.create(); }
    public Builder newBuilderForType() { return newBuilder(); }
    public static Builder newBuilder(akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope prototype) {
      return newBuilder().mergeFrom(prototype);
    }
    public Builder toBuilder() { return newBuilder(this); }

    @java.lang.Override
    protected Builder newBuilderForType(
        akka.protobuf.GeneratedMessage.BuilderParent parent) {
      Builder builder = new Builder(parent);
      return builder;
    }
    /**
     * Protobuf type {@code akka.cluster.sharding.typed.ShardingEnvelope}
     */
    public static final class Builder extends
        akka.protobuf.GeneratedMessage.Builder<Builder>
       implements akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelopeOrBuilder {
      public static final akka.protobuf.Descriptors.Descriptor
          getDescriptor() {
        return akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.internal_static_akka_cluster_sharding_typed_ShardingEnvelope_descriptor;
      }

      protected akka.protobuf.GeneratedMessage.FieldAccessorTable
          internalGetFieldAccessorTable() {
        return akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.internal_static_akka_cluster_sharding_typed_ShardingEnvelope_fieldAccessorTable
            .ensureFieldAccessorsInitialized(
                akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope.class, akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope.Builder.class);
      }

      // Construct using akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope.newBuilder()
      private Builder() {
        maybeForceBuilderInitialization();
      }

      private Builder(
          akka.protobuf.GeneratedMessage.BuilderParent parent) {
        super(parent);
        maybeForceBuilderInitialization();
      }
      private void maybeForceBuilderInitialization() {
        if (akka.protobuf.GeneratedMessage.alwaysUseFieldBuilders) {
          getMessageFieldBuilder();
        }
      }
      private static Builder create() {
        return new Builder();
      }

      public Builder clear() {
        super.clear();
        entityId_ = "";
        bitField0_ = (bitField0_ & ~0x00000001);
        if (messageBuilder_ == null) {
          message_ = akka.remote.ContainerFormats.Payload.getDefaultInstance();
        } else {
          messageBuilder_.clear();
        }
        bitField0_ = (bitField0_ & ~0x00000002);
        return this;
      }

      public Builder clone() {
        return create().mergeFrom(buildPartial());
      }

      public akka.protobuf.Descriptors.Descriptor
          getDescriptorForType() {
        return akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.internal_static_akka_cluster_sharding_typed_ShardingEnvelope_descriptor;
      }

      public akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope getDefaultInstanceForType() {
        return akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope.getDefaultInstance();
      }

      public akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope build() {
        akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope result = buildPartial();
        if (!result.isInitialized()) {
          throw newUninitializedMessageException(result);
        }
        return result;
      }

      public akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope buildPartial() {
        akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope result = new akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope(this);
        int from_bitField0_ = bitField0_;
        int to_bitField0_ = 0;
        if (((from_bitField0_ & 0x00000001) == 0x00000001)) {
          to_bitField0_ |= 0x00000001;
        }
        result.entityId_ = entityId_;
        if (((from_bitField0_ & 0x00000002) == 0x00000002)) {
          to_bitField0_ |= 0x00000002;
        }
        if (messageBuilder_ == null) {
          result.message_ = message_;
        } else {
          result.message_ = messageBuilder_.build();
        }
        result.bitField0_ = to_bitField0_;
        onBuilt();
        return result;
      }

      public Builder mergeFrom(akka.protobuf.Message other) {
        if (other instanceof akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope) {
          return mergeFrom((akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope)other);
        } else {
          super.mergeFrom(other);
          return this;
        }
      }

      public Builder mergeFrom(akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope other) {
        if (other == akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope.getDefaultInstance()) return this;
        if (other.hasEntityId()) {
          bitField0_ |= 0x00000001;
          entityId_ = other.entityId_;
          onChanged();
        }
        if (other.hasMessage()) {
          mergeMessage(other.getMessage());
        }
        this.mergeUnknownFields(other.getUnknownFields());
        return this;
      }

      public final boolean isInitialized() {
        if (!hasEntityId()) {
          
          return false;
        }
        if (hasMessage()) {
          if (!getMessage().isInitialized()) {
            
            return false;
          }
        }
        return true;
      }

      public Builder mergeFrom(
          akka.protobuf.CodedInputStream input,
          akka.protobuf.ExtensionRegistryLite extensionRegistry)
          throws java.io.IOException {
        akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parsedMessage = null;
        try {
          parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
        } catch (akka.protobuf.InvalidProtocolBufferException e) {
          parsedMessage = (akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope) e.getUnfinishedMessage();
          throw e;
        } finally {
          if (parsedMessage != null) {
            mergeFrom(parsedMessage);
          }
        }
        return this;
      }
      private int bitField0_;

      // required string entityId = 1;
      private java.lang.Object entityId_ = "";
      /**
       * <code>required string entityId = 1;</code>
       */
      public boolean hasEntityId() {
        return ((bitField0_ & 0x00000001) == 0x00000001);
      }
      /**
       * <code>required string entityId = 1;</code>
       */
      public java.lang.String getEntityId() {
        java.lang.Object ref = entityId_;
        if (!(ref instanceof java.lang.String)) {
          java.lang.String s = ((akka.protobuf.ByteString) ref)
              .toStringUtf8();
          entityId_ = s;
          return s;
        } else {
          return (java.lang.String) ref;
        }
      }
      /**
       * <code>required string entityId = 1;</code>
       */
      public akka.protobuf.ByteString
          getEntityIdBytes() {
        java.lang.Object ref = entityId_;
        if (ref instanceof String) {
          akka.protobuf.ByteString b = 
              akka.protobuf.ByteString.copyFromUtf8(
                  (java.lang.String) ref);
          entityId_ = b;
          return b;
        } else {
          return (akka.protobuf.ByteString) ref;
        }
      }
      /**
       * <code>required string entityId = 1;</code>
       */
      public Builder setEntityId(
          java.lang.String value) {
        if (value == null) {
    throw new NullPointerException();
  }
  bitField0_ |= 0x00000001;
        entityId_ = value;
        onChanged();
        return this;
      }
      /**
       * <code>required string entityId = 1;</code>
       */
      public Builder clearEntityId() {
        bitField0_ = (bitField0_ & ~0x00000001);
        entityId_ = getDefaultInstance().getEntityId();
        onChanged();
        return this;
      }
      /**
       * <code>required string entityId = 1;</code>
       */
      public Builder setEntityIdBytes(
          akka.protobuf.ByteString value) {
        if (value == null) {
    throw new NullPointerException();
  }
  bitField0_ |= 0x00000001;
        entityId_ = value;
        onChanged();
        return this;
      }

      // optional .Payload message = 2;
      private akka.remote.ContainerFormats.Payload message_ = akka.remote.ContainerFormats.Payload.getDefaultInstance();
      private akka.protobuf.SingleFieldBuilder<
          akka.remote.ContainerFormats.Payload, akka.remote.ContainerFormats.Payload.Builder, akka.remote.ContainerFormats.PayloadOrBuilder> messageBuilder_;
      /**
       * <code>optional .Payload message = 2;</code>
       */
      public boolean hasMessage() {
        return ((bitField0_ & 0x00000002) == 0x00000002);
      }
      /**
       * <code>optional .Payload message = 2;</code>
       */
      public akka.remote.ContainerFormats.Payload getMessage() {
        if (messageBuilder_ == null) {
          return message_;
        } else {
          return messageBuilder_.getMessage();
        }
      }
      /**
       * <code>optional .Payload message = 2;</code>
       */
      public Builder setMessage(akka.remote.ContainerFormats.Payload value) {
        if (messageBuilder_ == null) {
          if (value == null) {
            throw new NullPointerException();
          }
          message_ = value;
          onChanged();
        } else {
          messageBuilder_.setMessage(value);
        }
        bitField0_ |= 0x00000002;
        return this;
      }
      /**
       * <code>optional .Payload message = 2;</code>
       */
      public Builder setMessage(
          akka.remote.ContainerFormats.Payload.Builder builderForValue) {
        if (messageBuilder_ == null) {
          message_ = builderForValue.build();
          onChanged();
        } else {
          messageBuilder_.setMessage(builderForValue.build());
        }
        bitField0_ |= 0x00000002;
        return this;
      }
      /**
       * <code>optional .Payload message = 2;</code>
       */
      public Builder mergeMessage(akka.remote.ContainerFormats.Payload value) {
        if (messageBuilder_ == null) {
          if (((bitField0_ & 0x00000002) == 0x00000002) &&
              message_ != akka.remote.ContainerFormats.Payload.getDefaultInstance()) {
            message_ =
              akka.remote.ContainerFormats.Payload.newBuilder(message_).mergeFrom(value).buildPartial();
          } else {
            message_ = value;
          }
          onChanged();
        } else {
          messageBuilder_.mergeFrom(value);
        }
        bitField0_ |= 0x00000002;
        return this;
      }
      /**
       * <code>optional .Payload message = 2;</code>
       */
      public Builder clearMessage() {
        if (messageBuilder_ == null) {
          message_ = akka.remote.ContainerFormats.Payload.getDefaultInstance();
          onChanged();
        } else {
          messageBuilder_.clear();
        }
        bitField0_ = (bitField0_ & ~0x00000002);
        return this;
      }
      /**
       * <code>optional .Payload message = 2;</code>
       */
      public akka.remote.ContainerFormats.Payload.Builder getMessageBuilder() {
        bitField0_ |= 0x00000002;
        onChanged();
        return getMessageFieldBuilder().getBuilder();
      }
      /**
       * <code>optional .Payload message = 2;</code>
       */
      public akka.remote.ContainerFormats.PayloadOrBuilder getMessageOrBuilder() {
        if (messageBuilder_ != null) {
          return messageBuilder_.getMessageOrBuilder();
        } else {
          return message_;
        }
      }
      /**
       * <code>optional .Payload message = 2;</code>
       */
      private akka.protobuf.SingleFieldBuilder<
          akka.remote.ContainerFormats.Payload, akka.remote.ContainerFormats.Payload.Builder, akka.remote.ContainerFormats.PayloadOrBuilder> 
          getMessageFieldBuilder() {
        if (messageBuilder_ == null) {
          messageBuilder_ = new akka.protobuf.SingleFieldBuilder<
              akka.remote.ContainerFormats.Payload, akka.remote.ContainerFormats.Payload.Builder, akka.remote.ContainerFormats.PayloadOrBuilder>(
                  message_,
                  getParentForChildren(),
                  isClean());
          message_ = null;
        }
        return messageBuilder_;
      }

      // @@protoc_insertion_point(builder_scope:akka.cluster.sharding.typed.ShardingEnvelope)
    }

    static {
      defaultInstance = new ShardingEnvelope(true);
      defaultInstance.initFields();
    }

    // @@protoc_insertion_point(class_scope:akka.cluster.sharding.typed.ShardingEnvelope)
  }

  private static akka.protobuf.Descriptors.Descriptor
    internal_static_akka_cluster_sharding_typed_ShardingEnvelope_descriptor;
  private static
    akka.protobuf.GeneratedMessage.FieldAccessorTable
      internal_static_akka_cluster_sharding_typed_ShardingEnvelope_fieldAccessorTable;

  public static akka.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static akka.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\026ShardingMessages.proto\022\033akka.cluster.s" +
      "harding.typed\032\026ContainerFormats.proto\"?\n" +
      "\020ShardingEnvelope\022\020\n\010entityId\030\001 \002(\t\022\031\n\007m" +
      "essage\030\002 \001(\0132\010.PayloadB1\n-akka.cluster.s" +
      "harding.typed.internal.protobufH\001"
    };
    akka.protobuf.Descriptors.FileDescriptor.InternalDescriptorAssigner assigner =
      new akka.protobuf.Descriptors.FileDescriptor.InternalDescriptorAssigner() {
        public akka.protobuf.ExtensionRegistry assignDescriptors(
            akka.protobuf.Descriptors.FileDescriptor root) {
          descriptor = root;
          internal_static_akka_cluster_sharding_typed_ShardingEnvelope_descriptor =
            getDescriptor().getMessageTypes().get(0);
          internal_static_akka_cluster_sharding_typed_ShardingEnvelope_fieldAccessorTable = new
            akka.protobuf.GeneratedMessage.FieldAccessorTable(
              internal_static_akka_cluster_sharding_typed_ShardingEnvelope_descriptor,
              new java.lang.String[] { "EntityId", "Message", });
          return null;
        }
      };
    akka.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new akka.protobuf.Descriptors.FileDescriptor[] {
          akka.remote.ContainerFormats.getDescriptor(),
        }, assigner);
  }

  // @@protoc_insertion_point(outer_class_scope)
}
