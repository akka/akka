/*
 * Copyright (C) 2020-2022 Lightbend Inc. <https://www.lightbend.com>
 */

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: ShardingMessages.proto

package akka.cluster.sharding.typed.internal.protobuf;

public final class ShardingMessages {
  private ShardingMessages() {}
  public static void registerAllExtensions(
      akka.protobufv3.internal.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      akka.protobufv3.internal.ExtensionRegistry registry) {
    registerAllExtensions(
        (akka.protobufv3.internal.ExtensionRegistryLite) registry);
  }
  public interface ShardingEnvelopeOrBuilder extends
      // @@protoc_insertion_point(interface_extends:akka.cluster.sharding.typed.ShardingEnvelope)
      akka.protobufv3.internal.MessageOrBuilder {

    /**
     * <code>required string entityId = 1;</code>
     * @return Whether the entityId field is set.
     */
    boolean hasEntityId();
    /**
     * <code>required string entityId = 1;</code>
     * @return The entityId.
     */
    java.lang.String getEntityId();
    /**
     * <code>required string entityId = 1;</code>
     * @return The bytes for entityId.
     */
    akka.protobufv3.internal.ByteString
        getEntityIdBytes();

    /**
     * <code>required .Payload message = 2;</code>
     * @return Whether the message field is set.
     */
    boolean hasMessage();
    /**
     * <code>required .Payload message = 2;</code>
     * @return The message.
     */
    akka.remote.ContainerFormats.Payload getMessage();
    /**
     * <code>required .Payload message = 2;</code>
     */
    akka.remote.ContainerFormats.PayloadOrBuilder getMessageOrBuilder();
  }
  /**
   * Protobuf type {@code akka.cluster.sharding.typed.ShardingEnvelope}
   */
  public  static final class ShardingEnvelope extends
      akka.protobufv3.internal.GeneratedMessageV3 implements
      // @@protoc_insertion_point(message_implements:akka.cluster.sharding.typed.ShardingEnvelope)
      ShardingEnvelopeOrBuilder {
  private static final long serialVersionUID = 0L;
    // Use ShardingEnvelope.newBuilder() to construct.
    private ShardingEnvelope(akka.protobufv3.internal.GeneratedMessageV3.Builder<?> builder) {
      super(builder);
    }
    private ShardingEnvelope() {
      entityId_ = "";
    }

    @java.lang.Override
    @SuppressWarnings({"unused"})
    protected java.lang.Object newInstance(
        akka.protobufv3.internal.GeneratedMessageV3.UnusedPrivateParameter unused) {
      return new ShardingEnvelope();
    }

    @java.lang.Override
    public final akka.protobufv3.internal.UnknownFieldSet
    getUnknownFields() {
      return this.unknownFields;
    }
    private ShardingEnvelope(
        akka.protobufv3.internal.CodedInputStream input,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      this();
      if (extensionRegistry == null) {
        throw new java.lang.NullPointerException();
      }
      int mutable_bitField0_ = 0;
      akka.protobufv3.internal.UnknownFieldSet.Builder unknownFields =
          akka.protobufv3.internal.UnknownFieldSet.newBuilder();
      try {
        boolean done = false;
        while (!done) {
          int tag = input.readTag();
          switch (tag) {
            case 0:
              done = true;
              break;
            case 10: {
              akka.protobufv3.internal.ByteString bs = input.readBytes();
              bitField0_ |= 0x00000001;
              entityId_ = bs;
              break;
            }
            case 18: {
              akka.remote.ContainerFormats.Payload.Builder subBuilder = null;
              if (((bitField0_ & 0x00000002) != 0)) {
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
            default: {
              if (!parseUnknownField(
                  input, unknownFields, extensionRegistry, tag)) {
                done = true;
              }
              break;
            }
          }
        }
      } catch (akka.protobufv3.internal.InvalidProtocolBufferException e) {
        throw e.setUnfinishedMessage(this);
      } catch (java.io.IOException e) {
        throw new akka.protobufv3.internal.InvalidProtocolBufferException(
            e).setUnfinishedMessage(this);
      } finally {
        this.unknownFields = unknownFields.build();
        makeExtensionsImmutable();
      }
    }
    public static final akka.protobufv3.internal.Descriptors.Descriptor
        getDescriptor() {
      return akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.internal_static_akka_cluster_sharding_typed_ShardingEnvelope_descriptor;
    }

    @java.lang.Override
    protected akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.internal_static_akka_cluster_sharding_typed_ShardingEnvelope_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope.class, akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope.Builder.class);
    }

    private int bitField0_;
    public static final int ENTITYID_FIELD_NUMBER = 1;
    private volatile java.lang.Object entityId_;
    /**
     * <code>required string entityId = 1;</code>
     * @return Whether the entityId field is set.
     */
    public boolean hasEntityId() {
      return ((bitField0_ & 0x00000001) != 0);
    }
    /**
     * <code>required string entityId = 1;</code>
     * @return The entityId.
     */
    public java.lang.String getEntityId() {
      java.lang.Object ref = entityId_;
      if (ref instanceof java.lang.String) {
        return (java.lang.String) ref;
      } else {
        akka.protobufv3.internal.ByteString bs = 
            (akka.protobufv3.internal.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        if (bs.isValidUtf8()) {
          entityId_ = s;
        }
        return s;
      }
    }
    /**
     * <code>required string entityId = 1;</code>
     * @return The bytes for entityId.
     */
    public akka.protobufv3.internal.ByteString
        getEntityIdBytes() {
      java.lang.Object ref = entityId_;
      if (ref instanceof java.lang.String) {
        akka.protobufv3.internal.ByteString b = 
            akka.protobufv3.internal.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        entityId_ = b;
        return b;
      } else {
        return (akka.protobufv3.internal.ByteString) ref;
      }
    }

    public static final int MESSAGE_FIELD_NUMBER = 2;
    private akka.remote.ContainerFormats.Payload message_;
    /**
     * <code>required .Payload message = 2;</code>
     * @return Whether the message field is set.
     */
    public boolean hasMessage() {
      return ((bitField0_ & 0x00000002) != 0);
    }
    /**
     * <code>required .Payload message = 2;</code>
     * @return The message.
     */
    public akka.remote.ContainerFormats.Payload getMessage() {
      return message_ == null ? akka.remote.ContainerFormats.Payload.getDefaultInstance() : message_;
    }
    /**
     * <code>required .Payload message = 2;</code>
     */
    public akka.remote.ContainerFormats.PayloadOrBuilder getMessageOrBuilder() {
      return message_ == null ? akka.remote.ContainerFormats.Payload.getDefaultInstance() : message_;
    }

    private byte memoizedIsInitialized = -1;
    @java.lang.Override
    public final boolean isInitialized() {
      byte isInitialized = memoizedIsInitialized;
      if (isInitialized == 1) return true;
      if (isInitialized == 0) return false;

      if (!hasEntityId()) {
        memoizedIsInitialized = 0;
        return false;
      }
      if (!hasMessage()) {
        memoizedIsInitialized = 0;
        return false;
      }
      if (!getMessage().isInitialized()) {
        memoizedIsInitialized = 0;
        return false;
      }
      memoizedIsInitialized = 1;
      return true;
    }

    @java.lang.Override
    public void writeTo(akka.protobufv3.internal.CodedOutputStream output)
                        throws java.io.IOException {
      if (((bitField0_ & 0x00000001) != 0)) {
        akka.protobufv3.internal.GeneratedMessageV3.writeString(output, 1, entityId_);
      }
      if (((bitField0_ & 0x00000002) != 0)) {
        output.writeMessage(2, getMessage());
      }
      unknownFields.writeTo(output);
    }

    @java.lang.Override
    public int getSerializedSize() {
      int size = memoizedSize;
      if (size != -1) return size;

      size = 0;
      if (((bitField0_ & 0x00000001) != 0)) {
        size += akka.protobufv3.internal.GeneratedMessageV3.computeStringSize(1, entityId_);
      }
      if (((bitField0_ & 0x00000002) != 0)) {
        size += akka.protobufv3.internal.CodedOutputStream
          .computeMessageSize(2, getMessage());
      }
      size += unknownFields.getSerializedSize();
      memoizedSize = size;
      return size;
    }

    @java.lang.Override
    public boolean equals(final java.lang.Object obj) {
      if (obj == this) {
       return true;
      }
      if (!(obj instanceof akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope)) {
        return super.equals(obj);
      }
      akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope other = (akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope) obj;

      if (hasEntityId() != other.hasEntityId()) return false;
      if (hasEntityId()) {
        if (!getEntityId()
            .equals(other.getEntityId())) return false;
      }
      if (hasMessage() != other.hasMessage()) return false;
      if (hasMessage()) {
        if (!getMessage()
            .equals(other.getMessage())) return false;
      }
      if (!unknownFields.equals(other.unknownFields)) return false;
      return true;
    }

    @java.lang.Override
    public int hashCode() {
      if (memoizedHashCode != 0) {
        return memoizedHashCode;
      }
      int hash = 41;
      hash = (19 * hash) + getDescriptor().hashCode();
      if (hasEntityId()) {
        hash = (37 * hash) + ENTITYID_FIELD_NUMBER;
        hash = (53 * hash) + getEntityId().hashCode();
      }
      if (hasMessage()) {
        hash = (37 * hash) + MESSAGE_FIELD_NUMBER;
        hash = (53 * hash) + getMessage().hashCode();
      }
      hash = (29 * hash) + unknownFields.hashCode();
      memoizedHashCode = hash;
      return hash;
    }

    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(
        java.nio.ByteBuffer data)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(
        java.nio.ByteBuffer data,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(
        akka.protobufv3.internal.ByteString data)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(
        akka.protobufv3.internal.ByteString data,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(byte[] data)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(
        byte[] data,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(java.io.InputStream input)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseWithIOException(PARSER, input);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(
        java.io.InputStream input,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseWithIOException(PARSER, input, extensionRegistry);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseDelimitedFrom(java.io.InputStream input)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseDelimitedWithIOException(PARSER, input);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseDelimitedFrom(
        java.io.InputStream input,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(
        akka.protobufv3.internal.CodedInputStream input)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseWithIOException(PARSER, input);
    }
    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parseFrom(
        akka.protobufv3.internal.CodedInputStream input,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseWithIOException(PARSER, input, extensionRegistry);
    }

    @java.lang.Override
    public Builder newBuilderForType() { return newBuilder(); }
    public static Builder newBuilder() {
      return DEFAULT_INSTANCE.toBuilder();
    }
    public static Builder newBuilder(akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope prototype) {
      return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
    }
    @java.lang.Override
    public Builder toBuilder() {
      return this == DEFAULT_INSTANCE
          ? new Builder() : new Builder().mergeFrom(this);
    }

    @java.lang.Override
    protected Builder newBuilderForType(
        akka.protobufv3.internal.GeneratedMessageV3.BuilderParent parent) {
      Builder builder = new Builder(parent);
      return builder;
    }
    /**
     * Protobuf type {@code akka.cluster.sharding.typed.ShardingEnvelope}
     */
    public static final class Builder extends
        akka.protobufv3.internal.GeneratedMessageV3.Builder<Builder> implements
        // @@protoc_insertion_point(builder_implements:akka.cluster.sharding.typed.ShardingEnvelope)
        akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelopeOrBuilder {
      public static final akka.protobufv3.internal.Descriptors.Descriptor
          getDescriptor() {
        return akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.internal_static_akka_cluster_sharding_typed_ShardingEnvelope_descriptor;
      }

      @java.lang.Override
      protected akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable
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
          akka.protobufv3.internal.GeneratedMessageV3.BuilderParent parent) {
        super(parent);
        maybeForceBuilderInitialization();
      }
      private void maybeForceBuilderInitialization() {
        if (akka.protobufv3.internal.GeneratedMessageV3
                .alwaysUseFieldBuilders) {
          getMessageFieldBuilder();
        }
      }
      @java.lang.Override
      public Builder clear() {
        super.clear();
        entityId_ = "";
        bitField0_ = (bitField0_ & ~0x00000001);
        if (messageBuilder_ == null) {
          message_ = null;
        } else {
          messageBuilder_.clear();
        }
        bitField0_ = (bitField0_ & ~0x00000002);
        return this;
      }

      @java.lang.Override
      public akka.protobufv3.internal.Descriptors.Descriptor
          getDescriptorForType() {
        return akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.internal_static_akka_cluster_sharding_typed_ShardingEnvelope_descriptor;
      }

      @java.lang.Override
      public akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope getDefaultInstanceForType() {
        return akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope.getDefaultInstance();
      }

      @java.lang.Override
      public akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope build() {
        akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope result = buildPartial();
        if (!result.isInitialized()) {
          throw newUninitializedMessageException(result);
        }
        return result;
      }

      @java.lang.Override
      public akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope buildPartial() {
        akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope result = new akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope(this);
        int from_bitField0_ = bitField0_;
        int to_bitField0_ = 0;
        if (((from_bitField0_ & 0x00000001) != 0)) {
          to_bitField0_ |= 0x00000001;
        }
        result.entityId_ = entityId_;
        if (((from_bitField0_ & 0x00000002) != 0)) {
          if (messageBuilder_ == null) {
            result.message_ = message_;
          } else {
            result.message_ = messageBuilder_.build();
          }
          to_bitField0_ |= 0x00000002;
        }
        result.bitField0_ = to_bitField0_;
        onBuilt();
        return result;
      }

      @java.lang.Override
      public Builder clone() {
        return super.clone();
      }
      @java.lang.Override
      public Builder setField(
          akka.protobufv3.internal.Descriptors.FieldDescriptor field,
          java.lang.Object value) {
        return super.setField(field, value);
      }
      @java.lang.Override
      public Builder clearField(
          akka.protobufv3.internal.Descriptors.FieldDescriptor field) {
        return super.clearField(field);
      }
      @java.lang.Override
      public Builder clearOneof(
          akka.protobufv3.internal.Descriptors.OneofDescriptor oneof) {
        return super.clearOneof(oneof);
      }
      @java.lang.Override
      public Builder setRepeatedField(
          akka.protobufv3.internal.Descriptors.FieldDescriptor field,
          int index, java.lang.Object value) {
        return super.setRepeatedField(field, index, value);
      }
      @java.lang.Override
      public Builder addRepeatedField(
          akka.protobufv3.internal.Descriptors.FieldDescriptor field,
          java.lang.Object value) {
        return super.addRepeatedField(field, value);
      }
      @java.lang.Override
      public Builder mergeFrom(akka.protobufv3.internal.Message other) {
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
        this.mergeUnknownFields(other.unknownFields);
        onChanged();
        return this;
      }

      @java.lang.Override
      public final boolean isInitialized() {
        if (!hasEntityId()) {
          return false;
        }
        if (!hasMessage()) {
          return false;
        }
        if (!getMessage().isInitialized()) {
          return false;
        }
        return true;
      }

      @java.lang.Override
      public Builder mergeFrom(
          akka.protobufv3.internal.CodedInputStream input,
          akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
          throws java.io.IOException {
        akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope parsedMessage = null;
        try {
          parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
        } catch (akka.protobufv3.internal.InvalidProtocolBufferException e) {
          parsedMessage = (akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope) e.getUnfinishedMessage();
          throw e.unwrapIOException();
        } finally {
          if (parsedMessage != null) {
            mergeFrom(parsedMessage);
          }
        }
        return this;
      }
      private int bitField0_;

      private java.lang.Object entityId_ = "";
      /**
       * <code>required string entityId = 1;</code>
       * @return Whether the entityId field is set.
       */
      public boolean hasEntityId() {
        return ((bitField0_ & 0x00000001) != 0);
      }
      /**
       * <code>required string entityId = 1;</code>
       * @return The entityId.
       */
      public java.lang.String getEntityId() {
        java.lang.Object ref = entityId_;
        if (!(ref instanceof java.lang.String)) {
          akka.protobufv3.internal.ByteString bs =
              (akka.protobufv3.internal.ByteString) ref;
          java.lang.String s = bs.toStringUtf8();
          if (bs.isValidUtf8()) {
            entityId_ = s;
          }
          return s;
        } else {
          return (java.lang.String) ref;
        }
      }
      /**
       * <code>required string entityId = 1;</code>
       * @return The bytes for entityId.
       */
      public akka.protobufv3.internal.ByteString
          getEntityIdBytes() {
        java.lang.Object ref = entityId_;
        if (ref instanceof String) {
          akka.protobufv3.internal.ByteString b = 
              akka.protobufv3.internal.ByteString.copyFromUtf8(
                  (java.lang.String) ref);
          entityId_ = b;
          return b;
        } else {
          return (akka.protobufv3.internal.ByteString) ref;
        }
      }
      /**
       * <code>required string entityId = 1;</code>
       * @param value The entityId to set.
       * @return This builder for chaining.
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
       * @return This builder for chaining.
       */
      public Builder clearEntityId() {
        bitField0_ = (bitField0_ & ~0x00000001);
        entityId_ = getDefaultInstance().getEntityId();
        onChanged();
        return this;
      }
      /**
       * <code>required string entityId = 1;</code>
       * @param value The bytes for entityId to set.
       * @return This builder for chaining.
       */
      public Builder setEntityIdBytes(
          akka.protobufv3.internal.ByteString value) {
        if (value == null) {
    throw new NullPointerException();
  }
  bitField0_ |= 0x00000001;
        entityId_ = value;
        onChanged();
        return this;
      }

      private akka.remote.ContainerFormats.Payload message_;
      private akka.protobufv3.internal.SingleFieldBuilderV3<
          akka.remote.ContainerFormats.Payload, akka.remote.ContainerFormats.Payload.Builder, akka.remote.ContainerFormats.PayloadOrBuilder> messageBuilder_;
      /**
       * <code>required .Payload message = 2;</code>
       * @return Whether the message field is set.
       */
      public boolean hasMessage() {
        return ((bitField0_ & 0x00000002) != 0);
      }
      /**
       * <code>required .Payload message = 2;</code>
       * @return The message.
       */
      public akka.remote.ContainerFormats.Payload getMessage() {
        if (messageBuilder_ == null) {
          return message_ == null ? akka.remote.ContainerFormats.Payload.getDefaultInstance() : message_;
        } else {
          return messageBuilder_.getMessage();
        }
      }
      /**
       * <code>required .Payload message = 2;</code>
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
       * <code>required .Payload message = 2;</code>
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
       * <code>required .Payload message = 2;</code>
       */
      public Builder mergeMessage(akka.remote.ContainerFormats.Payload value) {
        if (messageBuilder_ == null) {
          if (((bitField0_ & 0x00000002) != 0) &&
              message_ != null &&
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
       * <code>required .Payload message = 2;</code>
       */
      public Builder clearMessage() {
        if (messageBuilder_ == null) {
          message_ = null;
          onChanged();
        } else {
          messageBuilder_.clear();
        }
        bitField0_ = (bitField0_ & ~0x00000002);
        return this;
      }
      /**
       * <code>required .Payload message = 2;</code>
       */
      public akka.remote.ContainerFormats.Payload.Builder getMessageBuilder() {
        bitField0_ |= 0x00000002;
        onChanged();
        return getMessageFieldBuilder().getBuilder();
      }
      /**
       * <code>required .Payload message = 2;</code>
       */
      public akka.remote.ContainerFormats.PayloadOrBuilder getMessageOrBuilder() {
        if (messageBuilder_ != null) {
          return messageBuilder_.getMessageOrBuilder();
        } else {
          return message_ == null ?
              akka.remote.ContainerFormats.Payload.getDefaultInstance() : message_;
        }
      }
      /**
       * <code>required .Payload message = 2;</code>
       */
      private akka.protobufv3.internal.SingleFieldBuilderV3<
          akka.remote.ContainerFormats.Payload, akka.remote.ContainerFormats.Payload.Builder, akka.remote.ContainerFormats.PayloadOrBuilder> 
          getMessageFieldBuilder() {
        if (messageBuilder_ == null) {
          messageBuilder_ = new akka.protobufv3.internal.SingleFieldBuilderV3<
              akka.remote.ContainerFormats.Payload, akka.remote.ContainerFormats.Payload.Builder, akka.remote.ContainerFormats.PayloadOrBuilder>(
                  getMessage(),
                  getParentForChildren(),
                  isClean());
          message_ = null;
        }
        return messageBuilder_;
      }
      @java.lang.Override
      public final Builder setUnknownFields(
          final akka.protobufv3.internal.UnknownFieldSet unknownFields) {
        return super.setUnknownFields(unknownFields);
      }

      @java.lang.Override
      public final Builder mergeUnknownFields(
          final akka.protobufv3.internal.UnknownFieldSet unknownFields) {
        return super.mergeUnknownFields(unknownFields);
      }


      // @@protoc_insertion_point(builder_scope:akka.cluster.sharding.typed.ShardingEnvelope)
    }

    // @@protoc_insertion_point(class_scope:akka.cluster.sharding.typed.ShardingEnvelope)
    private static final akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope DEFAULT_INSTANCE;
    static {
      DEFAULT_INSTANCE = new akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope();
    }

    public static akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope getDefaultInstance() {
      return DEFAULT_INSTANCE;
    }

    @java.lang.Deprecated public static final akka.protobufv3.internal.Parser<ShardingEnvelope>
        PARSER = new akka.protobufv3.internal.AbstractParser<ShardingEnvelope>() {
      @java.lang.Override
      public ShardingEnvelope parsePartialFrom(
          akka.protobufv3.internal.CodedInputStream input,
          akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
          throws akka.protobufv3.internal.InvalidProtocolBufferException {
        return new ShardingEnvelope(input, extensionRegistry);
      }
    };

    public static akka.protobufv3.internal.Parser<ShardingEnvelope> parser() {
      return PARSER;
    }

    @java.lang.Override
    public akka.protobufv3.internal.Parser<ShardingEnvelope> getParserForType() {
      return PARSER;
    }

    @java.lang.Override
    public akka.cluster.sharding.typed.internal.protobuf.ShardingMessages.ShardingEnvelope getDefaultInstanceForType() {
      return DEFAULT_INSTANCE;
    }

  }

  private static final akka.protobufv3.internal.Descriptors.Descriptor
    internal_static_akka_cluster_sharding_typed_ShardingEnvelope_descriptor;
  private static final 
    akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable
      internal_static_akka_cluster_sharding_typed_ShardingEnvelope_fieldAccessorTable;

  public static akka.protobufv3.internal.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  akka.protobufv3.internal.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\026ShardingMessages.proto\022\033akka.cluster.s" +
      "harding.typed\032\026ContainerFormats.proto\"?\n" +
      "\020ShardingEnvelope\022\020\n\010entityId\030\001 \002(\t\022\031\n\007m" +
      "essage\030\002 \002(\0132\010.PayloadB1\n-akka.cluster.s" +
      "harding.typed.internal.protobufH\001"
    };
    descriptor = akka.protobufv3.internal.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new akka.protobufv3.internal.Descriptors.FileDescriptor[] {
          akka.remote.ContainerFormats.getDescriptor(),
        });
    internal_static_akka_cluster_sharding_typed_ShardingEnvelope_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_akka_cluster_sharding_typed_ShardingEnvelope_fieldAccessorTable = new
      akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable(
        internal_static_akka_cluster_sharding_typed_ShardingEnvelope_descriptor,
        new java.lang.String[] { "EntityId", "Message", });
    akka.remote.ContainerFormats.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
