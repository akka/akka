/*
 * Copyright (C) 2023-2024 Lightbend Inc. <https://www.lightbend.com>
 */

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: ProtobufProtocol.proto

package akka.remote;

public final class ProtobufProtocol {
  private ProtobufProtocol() {}
  public static void registerAllExtensions(
      akka.protobufv3.internal.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      akka.protobufv3.internal.ExtensionRegistry registry) {
    registerAllExtensions(
        (akka.protobufv3.internal.ExtensionRegistryLite) registry);
  }
  public interface MyMessageOrBuilder extends
      // @@protoc_insertion_point(interface_extends:MyMessage)
      akka.protobufv3.internal.MessageOrBuilder {

    /**
     * <code>required uint64 id = 1;</code>
     * @return Whether the id field is set.
     */
    boolean hasId();
    /**
     * <code>required uint64 id = 1;</code>
     * @return The id.
     */
    long getId();

    /**
     * <code>required string name = 2;</code>
     * @return Whether the name field is set.
     */
    boolean hasName();
    /**
     * <code>required string name = 2;</code>
     * @return The name.
     */
    java.lang.String getName();
    /**
     * <code>required string name = 2;</code>
     * @return The bytes for name.
     */
    akka.protobufv3.internal.ByteString
        getNameBytes();

    /**
     * <code>required bool status = 3;</code>
     * @return Whether the status field is set.
     */
    boolean hasStatus();
    /**
     * <code>required bool status = 3;</code>
     * @return The status.
     */
    boolean getStatus();
  }
  /**
   * Protobuf type {@code MyMessage}
   */
  public static final class MyMessage extends
      akka.protobufv3.internal.GeneratedMessageV3 implements
      // @@protoc_insertion_point(message_implements:MyMessage)
      MyMessageOrBuilder {
  private static final long serialVersionUID = 0L;
    // Use MyMessage.newBuilder() to construct.
    private MyMessage(akka.protobufv3.internal.GeneratedMessageV3.Builder<?> builder) {
      super(builder);
    }
    private MyMessage() {
      name_ = "";
    }

    @java.lang.Override
    @SuppressWarnings({"unused"})
    protected java.lang.Object newInstance(
        akka.protobufv3.internal.GeneratedMessageV3.UnusedPrivateParameter unused) {
      return new MyMessage();
    }

    public static final akka.protobufv3.internal.Descriptors.Descriptor
        getDescriptor() {
      return akka.remote.ProtobufProtocol.internal_static_MyMessage_descriptor;
    }

    @java.lang.Override
    protected akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return akka.remote.ProtobufProtocol.internal_static_MyMessage_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              akka.remote.ProtobufProtocol.MyMessage.class, akka.remote.ProtobufProtocol.MyMessage.Builder.class);
    }

    private int bitField0_;
    public static final int ID_FIELD_NUMBER = 1;
    private long id_ = 0L;
    /**
     * <code>required uint64 id = 1;</code>
     * @return Whether the id field is set.
     */
    @java.lang.Override
    public boolean hasId() {
      return ((bitField0_ & 0x00000001) != 0);
    }
    /**
     * <code>required uint64 id = 1;</code>
     * @return The id.
     */
    @java.lang.Override
    public long getId() {
      return id_;
    }

    public static final int NAME_FIELD_NUMBER = 2;
    @SuppressWarnings("serial")
    private volatile java.lang.Object name_ = "";
    /**
     * <code>required string name = 2;</code>
     * @return Whether the name field is set.
     */
    @java.lang.Override
    public boolean hasName() {
      return ((bitField0_ & 0x00000002) != 0);
    }
    /**
     * <code>required string name = 2;</code>
     * @return The name.
     */
    @java.lang.Override
    public java.lang.String getName() {
      java.lang.Object ref = name_;
      if (ref instanceof java.lang.String) {
        return (java.lang.String) ref;
      } else {
        akka.protobufv3.internal.ByteString bs = 
            (akka.protobufv3.internal.ByteString) ref;
        java.lang.String s = bs.toStringUtf8();
        if (bs.isValidUtf8()) {
          name_ = s;
        }
        return s;
      }
    }
    /**
     * <code>required string name = 2;</code>
     * @return The bytes for name.
     */
    @java.lang.Override
    public akka.protobufv3.internal.ByteString
        getNameBytes() {
      java.lang.Object ref = name_;
      if (ref instanceof java.lang.String) {
        akka.protobufv3.internal.ByteString b = 
            akka.protobufv3.internal.ByteString.copyFromUtf8(
                (java.lang.String) ref);
        name_ = b;
        return b;
      } else {
        return (akka.protobufv3.internal.ByteString) ref;
      }
    }

    public static final int STATUS_FIELD_NUMBER = 3;
    private boolean status_ = false;
    /**
     * <code>required bool status = 3;</code>
     * @return Whether the status field is set.
     */
    @java.lang.Override
    public boolean hasStatus() {
      return ((bitField0_ & 0x00000004) != 0);
    }
    /**
     * <code>required bool status = 3;</code>
     * @return The status.
     */
    @java.lang.Override
    public boolean getStatus() {
      return status_;
    }

    private byte memoizedIsInitialized = -1;
    @java.lang.Override
    public final boolean isInitialized() {
      byte isInitialized = memoizedIsInitialized;
      if (isInitialized == 1) return true;
      if (isInitialized == 0) return false;

      if (!hasId()) {
        memoizedIsInitialized = 0;
        return false;
      }
      if (!hasName()) {
        memoizedIsInitialized = 0;
        return false;
      }
      if (!hasStatus()) {
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
        output.writeUInt64(1, id_);
      }
      if (((bitField0_ & 0x00000002) != 0)) {
        akka.protobufv3.internal.GeneratedMessageV3.writeString(output, 2, name_);
      }
      if (((bitField0_ & 0x00000004) != 0)) {
        output.writeBool(3, status_);
      }
      getUnknownFields().writeTo(output);
    }

    @java.lang.Override
    public int getSerializedSize() {
      int size = memoizedSize;
      if (size != -1) return size;

      size = 0;
      if (((bitField0_ & 0x00000001) != 0)) {
        size += akka.protobufv3.internal.CodedOutputStream
          .computeUInt64Size(1, id_);
      }
      if (((bitField0_ & 0x00000002) != 0)) {
        size += akka.protobufv3.internal.GeneratedMessageV3.computeStringSize(2, name_);
      }
      if (((bitField0_ & 0x00000004) != 0)) {
        size += akka.protobufv3.internal.CodedOutputStream
          .computeBoolSize(3, status_);
      }
      size += getUnknownFields().getSerializedSize();
      memoizedSize = size;
      return size;
    }

    @java.lang.Override
    public boolean equals(final java.lang.Object obj) {
      if (obj == this) {
       return true;
      }
      if (!(obj instanceof akka.remote.ProtobufProtocol.MyMessage)) {
        return super.equals(obj);
      }
      akka.remote.ProtobufProtocol.MyMessage other = (akka.remote.ProtobufProtocol.MyMessage) obj;

      if (hasId() != other.hasId()) return false;
      if (hasId()) {
        if (getId()
            != other.getId()) return false;
      }
      if (hasName() != other.hasName()) return false;
      if (hasName()) {
        if (!getName()
            .equals(other.getName())) return false;
      }
      if (hasStatus() != other.hasStatus()) return false;
      if (hasStatus()) {
        if (getStatus()
            != other.getStatus()) return false;
      }
      if (!getUnknownFields().equals(other.getUnknownFields())) return false;
      return true;
    }

    @java.lang.Override
    public int hashCode() {
      if (memoizedHashCode != 0) {
        return memoizedHashCode;
      }
      int hash = 41;
      hash = (19 * hash) + getDescriptor().hashCode();
      if (hasId()) {
        hash = (37 * hash) + ID_FIELD_NUMBER;
        hash = (53 * hash) + akka.protobufv3.internal.Internal.hashLong(
            getId());
      }
      if (hasName()) {
        hash = (37 * hash) + NAME_FIELD_NUMBER;
        hash = (53 * hash) + getName().hashCode();
      }
      if (hasStatus()) {
        hash = (37 * hash) + STATUS_FIELD_NUMBER;
        hash = (53 * hash) + akka.protobufv3.internal.Internal.hashBoolean(
            getStatus());
      }
      hash = (29 * hash) + getUnknownFields().hashCode();
      memoizedHashCode = hash;
      return hash;
    }

    public static akka.remote.ProtobufProtocol.MyMessage parseFrom(
        java.nio.ByteBuffer data)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static akka.remote.ProtobufProtocol.MyMessage parseFrom(
        java.nio.ByteBuffer data,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static akka.remote.ProtobufProtocol.MyMessage parseFrom(
        akka.protobufv3.internal.ByteString data)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static akka.remote.ProtobufProtocol.MyMessage parseFrom(
        akka.protobufv3.internal.ByteString data,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static akka.remote.ProtobufProtocol.MyMessage parseFrom(byte[] data)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static akka.remote.ProtobufProtocol.MyMessage parseFrom(
        byte[] data,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static akka.remote.ProtobufProtocol.MyMessage parseFrom(java.io.InputStream input)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseWithIOException(PARSER, input);
    }
    public static akka.remote.ProtobufProtocol.MyMessage parseFrom(
        java.io.InputStream input,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseWithIOException(PARSER, input, extensionRegistry);
    }

    public static akka.remote.ProtobufProtocol.MyMessage parseDelimitedFrom(java.io.InputStream input)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseDelimitedWithIOException(PARSER, input);
    }

    public static akka.remote.ProtobufProtocol.MyMessage parseDelimitedFrom(
        java.io.InputStream input,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
    }
    public static akka.remote.ProtobufProtocol.MyMessage parseFrom(
        akka.protobufv3.internal.CodedInputStream input)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseWithIOException(PARSER, input);
    }
    public static akka.remote.ProtobufProtocol.MyMessage parseFrom(
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
    public static Builder newBuilder(akka.remote.ProtobufProtocol.MyMessage prototype) {
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
     * Protobuf type {@code MyMessage}
     */
    public static final class Builder extends
        akka.protobufv3.internal.GeneratedMessageV3.Builder<Builder> implements
        // @@protoc_insertion_point(builder_implements:MyMessage)
        akka.remote.ProtobufProtocol.MyMessageOrBuilder {
      public static final akka.protobufv3.internal.Descriptors.Descriptor
          getDescriptor() {
        return akka.remote.ProtobufProtocol.internal_static_MyMessage_descriptor;
      }

      @java.lang.Override
      protected akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable
          internalGetFieldAccessorTable() {
        return akka.remote.ProtobufProtocol.internal_static_MyMessage_fieldAccessorTable
            .ensureFieldAccessorsInitialized(
                akka.remote.ProtobufProtocol.MyMessage.class, akka.remote.ProtobufProtocol.MyMessage.Builder.class);
      }

      // Construct using akka.remote.ProtobufProtocol.MyMessage.newBuilder()
      private Builder() {

      }

      private Builder(
          akka.protobufv3.internal.GeneratedMessageV3.BuilderParent parent) {
        super(parent);

      }
      @java.lang.Override
      public Builder clear() {
        super.clear();
        bitField0_ = 0;
        id_ = 0L;
        name_ = "";
        status_ = false;
        return this;
      }

      @java.lang.Override
      public akka.protobufv3.internal.Descriptors.Descriptor
          getDescriptorForType() {
        return akka.remote.ProtobufProtocol.internal_static_MyMessage_descriptor;
      }

      @java.lang.Override
      public akka.remote.ProtobufProtocol.MyMessage getDefaultInstanceForType() {
        return akka.remote.ProtobufProtocol.MyMessage.getDefaultInstance();
      }

      @java.lang.Override
      public akka.remote.ProtobufProtocol.MyMessage build() {
        akka.remote.ProtobufProtocol.MyMessage result = buildPartial();
        if (!result.isInitialized()) {
          throw newUninitializedMessageException(result);
        }
        return result;
      }

      @java.lang.Override
      public akka.remote.ProtobufProtocol.MyMessage buildPartial() {
        akka.remote.ProtobufProtocol.MyMessage result = new akka.remote.ProtobufProtocol.MyMessage(this);
        if (bitField0_ != 0) { buildPartial0(result); }
        onBuilt();
        return result;
      }

      private void buildPartial0(akka.remote.ProtobufProtocol.MyMessage result) {
        int from_bitField0_ = bitField0_;
        int to_bitField0_ = 0;
        if (((from_bitField0_ & 0x00000001) != 0)) {
          result.id_ = id_;
          to_bitField0_ |= 0x00000001;
        }
        if (((from_bitField0_ & 0x00000002) != 0)) {
          result.name_ = name_;
          to_bitField0_ |= 0x00000002;
        }
        if (((from_bitField0_ & 0x00000004) != 0)) {
          result.status_ = status_;
          to_bitField0_ |= 0x00000004;
        }
        result.bitField0_ |= to_bitField0_;
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
        if (other instanceof akka.remote.ProtobufProtocol.MyMessage) {
          return mergeFrom((akka.remote.ProtobufProtocol.MyMessage)other);
        } else {
          super.mergeFrom(other);
          return this;
        }
      }

      public Builder mergeFrom(akka.remote.ProtobufProtocol.MyMessage other) {
        if (other == akka.remote.ProtobufProtocol.MyMessage.getDefaultInstance()) return this;
        if (other.hasId()) {
          setId(other.getId());
        }
        if (other.hasName()) {
          name_ = other.name_;
          bitField0_ |= 0x00000002;
          onChanged();
        }
        if (other.hasStatus()) {
          setStatus(other.getStatus());
        }
        this.mergeUnknownFields(other.getUnknownFields());
        onChanged();
        return this;
      }

      @java.lang.Override
      public final boolean isInitialized() {
        if (!hasId()) {
          return false;
        }
        if (!hasName()) {
          return false;
        }
        if (!hasStatus()) {
          return false;
        }
        return true;
      }

      @java.lang.Override
      public Builder mergeFrom(
          akka.protobufv3.internal.CodedInputStream input,
          akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
          throws java.io.IOException {
        if (extensionRegistry == null) {
          throw new java.lang.NullPointerException();
        }
        try {
          boolean done = false;
          while (!done) {
            int tag = input.readTag();
            switch (tag) {
              case 0:
                done = true;
                break;
              case 8: {
                id_ = input.readUInt64();
                bitField0_ |= 0x00000001;
                break;
              } // case 8
              case 18: {
                name_ = input.readBytes();
                bitField0_ |= 0x00000002;
                break;
              } // case 18
              case 24: {
                status_ = input.readBool();
                bitField0_ |= 0x00000004;
                break;
              } // case 24
              default: {
                if (!super.parseUnknownField(input, extensionRegistry, tag)) {
                  done = true; // was an endgroup tag
                }
                break;
              } // default:
            } // switch (tag)
          } // while (!done)
        } catch (akka.protobufv3.internal.InvalidProtocolBufferException e) {
          throw e.unwrapIOException();
        } finally {
          onChanged();
        } // finally
        return this;
      }
      private int bitField0_;

      private long id_ ;
      /**
       * <code>required uint64 id = 1;</code>
       * @return Whether the id field is set.
       */
      @java.lang.Override
      public boolean hasId() {
        return ((bitField0_ & 0x00000001) != 0);
      }
      /**
       * <code>required uint64 id = 1;</code>
       * @return The id.
       */
      @java.lang.Override
      public long getId() {
        return id_;
      }
      /**
       * <code>required uint64 id = 1;</code>
       * @param value The id to set.
       * @return This builder for chaining.
       */
      public Builder setId(long value) {

        id_ = value;
        bitField0_ |= 0x00000001;
        onChanged();
        return this;
      }
      /**
       * <code>required uint64 id = 1;</code>
       * @return This builder for chaining.
       */
      public Builder clearId() {
        bitField0_ = (bitField0_ & ~0x00000001);
        id_ = 0L;
        onChanged();
        return this;
      }

      private java.lang.Object name_ = "";
      /**
       * <code>required string name = 2;</code>
       * @return Whether the name field is set.
       */
      public boolean hasName() {
        return ((bitField0_ & 0x00000002) != 0);
      }
      /**
       * <code>required string name = 2;</code>
       * @return The name.
       */
      public java.lang.String getName() {
        java.lang.Object ref = name_;
        if (!(ref instanceof java.lang.String)) {
          akka.protobufv3.internal.ByteString bs =
              (akka.protobufv3.internal.ByteString) ref;
          java.lang.String s = bs.toStringUtf8();
          if (bs.isValidUtf8()) {
            name_ = s;
          }
          return s;
        } else {
          return (java.lang.String) ref;
        }
      }
      /**
       * <code>required string name = 2;</code>
       * @return The bytes for name.
       */
      public akka.protobufv3.internal.ByteString
          getNameBytes() {
        java.lang.Object ref = name_;
        if (ref instanceof String) {
          akka.protobufv3.internal.ByteString b = 
              akka.protobufv3.internal.ByteString.copyFromUtf8(
                  (java.lang.String) ref);
          name_ = b;
          return b;
        } else {
          return (akka.protobufv3.internal.ByteString) ref;
        }
      }
      /**
       * <code>required string name = 2;</code>
       * @param value The name to set.
       * @return This builder for chaining.
       */
      public Builder setName(
          java.lang.String value) {
        if (value == null) { throw new NullPointerException(); }
        name_ = value;
        bitField0_ |= 0x00000002;
        onChanged();
        return this;
      }
      /**
       * <code>required string name = 2;</code>
       * @return This builder for chaining.
       */
      public Builder clearName() {
        name_ = getDefaultInstance().getName();
        bitField0_ = (bitField0_ & ~0x00000002);
        onChanged();
        return this;
      }
      /**
       * <code>required string name = 2;</code>
       * @param value The bytes for name to set.
       * @return This builder for chaining.
       */
      public Builder setNameBytes(
          akka.protobufv3.internal.ByteString value) {
        if (value == null) { throw new NullPointerException(); }
        name_ = value;
        bitField0_ |= 0x00000002;
        onChanged();
        return this;
      }

      private boolean status_ ;
      /**
       * <code>required bool status = 3;</code>
       * @return Whether the status field is set.
       */
      @java.lang.Override
      public boolean hasStatus() {
        return ((bitField0_ & 0x00000004) != 0);
      }
      /**
       * <code>required bool status = 3;</code>
       * @return The status.
       */
      @java.lang.Override
      public boolean getStatus() {
        return status_;
      }
      /**
       * <code>required bool status = 3;</code>
       * @param value The status to set.
       * @return This builder for chaining.
       */
      public Builder setStatus(boolean value) {

        status_ = value;
        bitField0_ |= 0x00000004;
        onChanged();
        return this;
      }
      /**
       * <code>required bool status = 3;</code>
       * @return This builder for chaining.
       */
      public Builder clearStatus() {
        bitField0_ = (bitField0_ & ~0x00000004);
        status_ = false;
        onChanged();
        return this;
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


      // @@protoc_insertion_point(builder_scope:MyMessage)
    }

    // @@protoc_insertion_point(class_scope:MyMessage)
    private static final akka.remote.ProtobufProtocol.MyMessage DEFAULT_INSTANCE;
    static {
      DEFAULT_INSTANCE = new akka.remote.ProtobufProtocol.MyMessage();
    }

    public static akka.remote.ProtobufProtocol.MyMessage getDefaultInstance() {
      return DEFAULT_INSTANCE;
    }

    @java.lang.Deprecated public static final akka.protobufv3.internal.Parser<MyMessage>
        PARSER = new akka.protobufv3.internal.AbstractParser<MyMessage>() {
      @java.lang.Override
      public MyMessage parsePartialFrom(
          akka.protobufv3.internal.CodedInputStream input,
          akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
          throws akka.protobufv3.internal.InvalidProtocolBufferException {
        Builder builder = newBuilder();
        try {
          builder.mergeFrom(input, extensionRegistry);
        } catch (akka.protobufv3.internal.InvalidProtocolBufferException e) {
          throw e.setUnfinishedMessage(builder.buildPartial());
        } catch (akka.protobufv3.internal.UninitializedMessageException e) {
          throw e.asInvalidProtocolBufferException().setUnfinishedMessage(builder.buildPartial());
        } catch (java.io.IOException e) {
          throw new akka.protobufv3.internal.InvalidProtocolBufferException(e)
              .setUnfinishedMessage(builder.buildPartial());
        }
        return builder.buildPartial();
      }
    };

    public static akka.protobufv3.internal.Parser<MyMessage> parser() {
      return PARSER;
    }

    @java.lang.Override
    public akka.protobufv3.internal.Parser<MyMessage> getParserForType() {
      return PARSER;
    }

    @java.lang.Override
    public akka.remote.ProtobufProtocol.MyMessage getDefaultInstanceForType() {
      return DEFAULT_INSTANCE;
    }

  }

  private static final akka.protobufv3.internal.Descriptors.Descriptor
    internal_static_MyMessage_descriptor;
  private static final 
    akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable
      internal_static_MyMessage_fieldAccessorTable;

  public static akka.protobufv3.internal.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  akka.protobufv3.internal.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\026ProtobufProtocol.proto\"5\n\tMyMessage\022\n\n" +
      "\002id\030\001 \002(\004\022\014\n\004name\030\002 \002(\t\022\016\n\006status\030\003 \002(\010B" +
      "\r\n\013akka.remote"
    };
    descriptor = akka.protobufv3.internal.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new akka.protobufv3.internal.Descriptors.FileDescriptor[] {
        });
    internal_static_MyMessage_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_MyMessage_fieldAccessorTable = new
      akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable(
        internal_static_MyMessage_descriptor,
        new java.lang.String[] { "Id", "Name", "Status", });
  }

  // @@protoc_insertion_point(outer_class_scope)
}
