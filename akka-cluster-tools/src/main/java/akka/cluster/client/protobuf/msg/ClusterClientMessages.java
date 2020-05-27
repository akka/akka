/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: ClusterClientMessages.proto

package akka.cluster.client.protobuf.msg;

public final class ClusterClientMessages {
  private ClusterClientMessages() {}
  public static void registerAllExtensions(
      akka.protobufv3.internal.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      akka.protobufv3.internal.ExtensionRegistry registry) {
    registerAllExtensions(
        (akka.protobufv3.internal.ExtensionRegistryLite) registry);
  }
  public interface ContactsOrBuilder extends
      // @@protoc_insertion_point(interface_extends:Contacts)
      akka.protobufv3.internal.MessageOrBuilder {

    /**
     * <code>repeated string contactPoints = 1;</code>
     * @return A list containing the contactPoints.
     */
    java.util.List<java.lang.String>
        getContactPointsList();
    /**
     * <code>repeated string contactPoints = 1;</code>
     * @return The count of contactPoints.
     */
    int getContactPointsCount();
    /**
     * <code>repeated string contactPoints = 1;</code>
     * @param index The index of the element to return.
     * @return The contactPoints at the given index.
     */
    java.lang.String getContactPoints(int index);
    /**
     * <code>repeated string contactPoints = 1;</code>
     * @param index The index of the value to return.
     * @return The bytes of the contactPoints at the given index.
     */
    akka.protobufv3.internal.ByteString
        getContactPointsBytes(int index);
  }
  /**
   * Protobuf type {@code Contacts}
   */
  public  static final class Contacts extends
      akka.protobufv3.internal.GeneratedMessageV3 implements
      // @@protoc_insertion_point(message_implements:Contacts)
      ContactsOrBuilder {
  private static final long serialVersionUID = 0L;
    // Use Contacts.newBuilder() to construct.
    private Contacts(akka.protobufv3.internal.GeneratedMessageV3.Builder<?> builder) {
      super(builder);
    }
    private Contacts() {
      contactPoints_ = akka.protobufv3.internal.LazyStringArrayList.EMPTY;
    }

    @java.lang.Override
    @SuppressWarnings({"unused"})
    protected java.lang.Object newInstance(
        akka.protobufv3.internal.GeneratedMessageV3.UnusedPrivateParameter unused) {
      return new Contacts();
    }

    @java.lang.Override
    public final akka.protobufv3.internal.UnknownFieldSet
    getUnknownFields() {
      return this.unknownFields;
    }
    private Contacts(
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
              if (!((mutable_bitField0_ & 0x00000001) != 0)) {
                contactPoints_ = new akka.protobufv3.internal.LazyStringArrayList();
                mutable_bitField0_ |= 0x00000001;
              }
              contactPoints_.add(bs);
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
        if (((mutable_bitField0_ & 0x00000001) != 0)) {
          contactPoints_ = contactPoints_.getUnmodifiableView();
        }
        this.unknownFields = unknownFields.build();
        makeExtensionsImmutable();
      }
    }
    public static final akka.protobufv3.internal.Descriptors.Descriptor
        getDescriptor() {
      return akka.cluster.client.protobuf.msg.ClusterClientMessages.internal_static_Contacts_descriptor;
    }

    @java.lang.Override
    protected akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return akka.cluster.client.protobuf.msg.ClusterClientMessages.internal_static_Contacts_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts.class, akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts.Builder.class);
    }

    public static final int CONTACTPOINTS_FIELD_NUMBER = 1;
    private akka.protobufv3.internal.LazyStringList contactPoints_;
    /**
     * <code>repeated string contactPoints = 1;</code>
     * @return A list containing the contactPoints.
     */
    public akka.protobufv3.internal.ProtocolStringList
        getContactPointsList() {
      return contactPoints_;
    }
    /**
     * <code>repeated string contactPoints = 1;</code>
     * @return The count of contactPoints.
     */
    public int getContactPointsCount() {
      return contactPoints_.size();
    }
    /**
     * <code>repeated string contactPoints = 1;</code>
     * @param index The index of the element to return.
     * @return The contactPoints at the given index.
     */
    public java.lang.String getContactPoints(int index) {
      return contactPoints_.get(index);
    }
    /**
     * <code>repeated string contactPoints = 1;</code>
     * @param index The index of the value to return.
     * @return The bytes of the contactPoints at the given index.
     */
    public akka.protobufv3.internal.ByteString
        getContactPointsBytes(int index) {
      return contactPoints_.getByteString(index);
    }

    private byte memoizedIsInitialized = -1;
    @java.lang.Override
    public final boolean isInitialized() {
      byte isInitialized = memoizedIsInitialized;
      if (isInitialized == 1) return true;
      if (isInitialized == 0) return false;

      memoizedIsInitialized = 1;
      return true;
    }

    @java.lang.Override
    public void writeTo(akka.protobufv3.internal.CodedOutputStream output)
                        throws java.io.IOException {
      for (int i = 0; i < contactPoints_.size(); i++) {
        akka.protobufv3.internal.GeneratedMessageV3.writeString(output, 1, contactPoints_.getRaw(i));
      }
      unknownFields.writeTo(output);
    }

    @java.lang.Override
    public int getSerializedSize() {
      int size = memoizedSize;
      if (size != -1) return size;

      size = 0;
      {
        int dataSize = 0;
        for (int i = 0; i < contactPoints_.size(); i++) {
          dataSize += computeStringSizeNoTag(contactPoints_.getRaw(i));
        }
        size += dataSize;
        size += 1 * getContactPointsList().size();
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
      if (!(obj instanceof akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts)) {
        return super.equals(obj);
      }
      akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts other = (akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts) obj;

      if (!getContactPointsList()
          .equals(other.getContactPointsList())) return false;
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
      if (getContactPointsCount() > 0) {
        hash = (37 * hash) + CONTACTPOINTS_FIELD_NUMBER;
        hash = (53 * hash) + getContactPointsList().hashCode();
      }
      hash = (29 * hash) + unknownFields.hashCode();
      memoizedHashCode = hash;
      return hash;
    }

    public static akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts parseFrom(
        java.nio.ByteBuffer data)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts parseFrom(
        java.nio.ByteBuffer data,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts parseFrom(
        akka.protobufv3.internal.ByteString data)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts parseFrom(
        akka.protobufv3.internal.ByteString data,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts parseFrom(byte[] data)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts parseFrom(
        byte[] data,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws akka.protobufv3.internal.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts parseFrom(java.io.InputStream input)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseWithIOException(PARSER, input);
    }
    public static akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts parseFrom(
        java.io.InputStream input,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseWithIOException(PARSER, input, extensionRegistry);
    }
    public static akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts parseDelimitedFrom(java.io.InputStream input)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseDelimitedWithIOException(PARSER, input);
    }
    public static akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts parseDelimitedFrom(
        java.io.InputStream input,
        akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
    }
    public static akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts parseFrom(
        akka.protobufv3.internal.CodedInputStream input)
        throws java.io.IOException {
      return akka.protobufv3.internal.GeneratedMessageV3
          .parseWithIOException(PARSER, input);
    }
    public static akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts parseFrom(
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
    public static Builder newBuilder(akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts prototype) {
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
     * Protobuf type {@code Contacts}
     */
    public static final class Builder extends
        akka.protobufv3.internal.GeneratedMessageV3.Builder<Builder> implements
        // @@protoc_insertion_point(builder_implements:Contacts)
        akka.cluster.client.protobuf.msg.ClusterClientMessages.ContactsOrBuilder {
      public static final akka.protobufv3.internal.Descriptors.Descriptor
          getDescriptor() {
        return akka.cluster.client.protobuf.msg.ClusterClientMessages.internal_static_Contacts_descriptor;
      }

      @java.lang.Override
      protected akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable
          internalGetFieldAccessorTable() {
        return akka.cluster.client.protobuf.msg.ClusterClientMessages.internal_static_Contacts_fieldAccessorTable
            .ensureFieldAccessorsInitialized(
                akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts.class, akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts.Builder.class);
      }

      // Construct using akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts.newBuilder()
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
        }
      }
      @java.lang.Override
      public Builder clear() {
        super.clear();
        contactPoints_ = akka.protobufv3.internal.LazyStringArrayList.EMPTY;
        bitField0_ = (bitField0_ & ~0x00000001);
        return this;
      }

      @java.lang.Override
      public akka.protobufv3.internal.Descriptors.Descriptor
          getDescriptorForType() {
        return akka.cluster.client.protobuf.msg.ClusterClientMessages.internal_static_Contacts_descriptor;
      }

      @java.lang.Override
      public akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts getDefaultInstanceForType() {
        return akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts.getDefaultInstance();
      }

      @java.lang.Override
      public akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts build() {
        akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts result = buildPartial();
        if (!result.isInitialized()) {
          throw newUninitializedMessageException(result);
        }
        return result;
      }

      @java.lang.Override
      public akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts buildPartial() {
        akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts result = new akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts(this);
        int from_bitField0_ = bitField0_;
        if (((bitField0_ & 0x00000001) != 0)) {
          contactPoints_ = contactPoints_.getUnmodifiableView();
          bitField0_ = (bitField0_ & ~0x00000001);
        }
        result.contactPoints_ = contactPoints_;
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
        if (other instanceof akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts) {
          return mergeFrom((akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts)other);
        } else {
          super.mergeFrom(other);
          return this;
        }
      }

      public Builder mergeFrom(akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts other) {
        if (other == akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts.getDefaultInstance()) return this;
        if (!other.contactPoints_.isEmpty()) {
          if (contactPoints_.isEmpty()) {
            contactPoints_ = other.contactPoints_;
            bitField0_ = (bitField0_ & ~0x00000001);
          } else {
            ensureContactPointsIsMutable();
            contactPoints_.addAll(other.contactPoints_);
          }
          onChanged();
        }
        this.mergeUnknownFields(other.unknownFields);
        onChanged();
        return this;
      }

      @java.lang.Override
      public final boolean isInitialized() {
        return true;
      }

      @java.lang.Override
      public Builder mergeFrom(
          akka.protobufv3.internal.CodedInputStream input,
          akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
          throws java.io.IOException {
        akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts parsedMessage = null;
        try {
          parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
        } catch (akka.protobufv3.internal.InvalidProtocolBufferException e) {
          parsedMessage = (akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts) e.getUnfinishedMessage();
          throw e.unwrapIOException();
        } finally {
          if (parsedMessage != null) {
            mergeFrom(parsedMessage);
          }
        }
        return this;
      }
      private int bitField0_;

      private akka.protobufv3.internal.LazyStringList contactPoints_ = akka.protobufv3.internal.LazyStringArrayList.EMPTY;
      private void ensureContactPointsIsMutable() {
        if (!((bitField0_ & 0x00000001) != 0)) {
          contactPoints_ = new akka.protobufv3.internal.LazyStringArrayList(contactPoints_);
          bitField0_ |= 0x00000001;
         }
      }
      /**
       * <code>repeated string contactPoints = 1;</code>
       * @return A list containing the contactPoints.
       */
      public akka.protobufv3.internal.ProtocolStringList
          getContactPointsList() {
        return contactPoints_.getUnmodifiableView();
      }
      /**
       * <code>repeated string contactPoints = 1;</code>
       * @return The count of contactPoints.
       */
      public int getContactPointsCount() {
        return contactPoints_.size();
      }
      /**
       * <code>repeated string contactPoints = 1;</code>
       * @param index The index of the element to return.
       * @return The contactPoints at the given index.
       */
      public java.lang.String getContactPoints(int index) {
        return contactPoints_.get(index);
      }
      /**
       * <code>repeated string contactPoints = 1;</code>
       * @param index The index of the value to return.
       * @return The bytes of the contactPoints at the given index.
       */
      public akka.protobufv3.internal.ByteString
          getContactPointsBytes(int index) {
        return contactPoints_.getByteString(index);
      }
      /**
       * <code>repeated string contactPoints = 1;</code>
       * @param index The index to set the value at.
       * @param value The contactPoints to set.
       * @return This builder for chaining.
       */
      public Builder setContactPoints(
          int index, java.lang.String value) {
        if (value == null) {
    throw new NullPointerException();
  }
  ensureContactPointsIsMutable();
        contactPoints_.set(index, value);
        onChanged();
        return this;
      }
      /**
       * <code>repeated string contactPoints = 1;</code>
       * @param value The contactPoints to add.
       * @return This builder for chaining.
       */
      public Builder addContactPoints(
          java.lang.String value) {
        if (value == null) {
    throw new NullPointerException();
  }
  ensureContactPointsIsMutable();
        contactPoints_.add(value);
        onChanged();
        return this;
      }
      /**
       * <code>repeated string contactPoints = 1;</code>
       * @param values The contactPoints to add.
       * @return This builder for chaining.
       */
      public Builder addAllContactPoints(
          java.lang.Iterable<java.lang.String> values) {
        ensureContactPointsIsMutable();
        akka.protobufv3.internal.AbstractMessageLite.Builder.addAll(
            values, contactPoints_);
        onChanged();
        return this;
      }
      /**
       * <code>repeated string contactPoints = 1;</code>
       * @return This builder for chaining.
       */
      public Builder clearContactPoints() {
        contactPoints_ = akka.protobufv3.internal.LazyStringArrayList.EMPTY;
        bitField0_ = (bitField0_ & ~0x00000001);
        onChanged();
        return this;
      }
      /**
       * <code>repeated string contactPoints = 1;</code>
       * @param value The bytes of the contactPoints to add.
       * @return This builder for chaining.
       */
      public Builder addContactPointsBytes(
          akka.protobufv3.internal.ByteString value) {
        if (value == null) {
    throw new NullPointerException();
  }
  ensureContactPointsIsMutable();
        contactPoints_.add(value);
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


      // @@protoc_insertion_point(builder_scope:Contacts)
    }

    // @@protoc_insertion_point(class_scope:Contacts)
    private static final akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts DEFAULT_INSTANCE;
    static {
      DEFAULT_INSTANCE = new akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts();
    }

    public static akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts getDefaultInstance() {
      return DEFAULT_INSTANCE;
    }

    @java.lang.Deprecated public static final akka.protobufv3.internal.Parser<Contacts>
        PARSER = new akka.protobufv3.internal.AbstractParser<Contacts>() {
      @java.lang.Override
      public Contacts parsePartialFrom(
          akka.protobufv3.internal.CodedInputStream input,
          akka.protobufv3.internal.ExtensionRegistryLite extensionRegistry)
          throws akka.protobufv3.internal.InvalidProtocolBufferException {
        return new Contacts(input, extensionRegistry);
      }
    };

    public static akka.protobufv3.internal.Parser<Contacts> parser() {
      return PARSER;
    }

    @java.lang.Override
    public akka.protobufv3.internal.Parser<Contacts> getParserForType() {
      return PARSER;
    }

    @java.lang.Override
    public akka.cluster.client.protobuf.msg.ClusterClientMessages.Contacts getDefaultInstanceForType() {
      return DEFAULT_INSTANCE;
    }

  }

  private static final akka.protobufv3.internal.Descriptors.Descriptor
    internal_static_Contacts_descriptor;
  private static final 
    akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable
      internal_static_Contacts_fieldAccessorTable;

  public static akka.protobufv3.internal.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  akka.protobufv3.internal.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\033ClusterClientMessages.proto\"!\n\010Contact" +
      "s\022\025\n\rcontactPoints\030\001 \003(\tB$\n akka.cluster" +
      ".client.protobuf.msgH\001"
    };
    descriptor = akka.protobufv3.internal.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new akka.protobufv3.internal.Descriptors.FileDescriptor[] {
        });
    internal_static_Contacts_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_Contacts_fieldAccessorTable = new
      akka.protobufv3.internal.GeneratedMessageV3.FieldAccessorTable(
        internal_static_Contacts_descriptor,
        new java.lang.String[] { "ContactPoints", });
  }

  // @@protoc_insertion_point(outer_class_scope)
}
