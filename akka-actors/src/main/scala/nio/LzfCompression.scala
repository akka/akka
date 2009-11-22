/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.nio

import org.h2.compress.{LZFInputStream, LZFOutputStream}

import org.jboss.netty.channel.{Channel, ChannelHandlerContext, ChannelPipelineCoverage}
import org.jboss.netty.buffer.{ChannelBufferOutputStream, ChannelBufferInputStream, ChannelBuffer}
import org.jboss.netty.handler.codec.oneone.{OneToOneEncoder, OneToOneDecoder};

@ChannelPipelineCoverage("all")
class LzfDecoder extends OneToOneDecoder {
  override protected def decode(ctx: ChannelHandlerContext, channel: Channel, message: AnyRef) = {
    if (!(message.isInstanceOf[ChannelBuffer])) message
    else {
      new LZFInputStream(new ChannelBufferInputStream(message.asInstanceOf[ChannelBuffer]))
    }
  }
}

@ChannelPipelineCoverage("all")
class LzfEncoder extends OneToOneEncoder {
  override protected def encode(ctx: ChannelHandlerContext, channel: Channel, message: AnyRef) = {
    if (!(message.isInstanceOf[ChannelBuffer])) message
    else new LZFOutputStream(new ChannelBufferOutputStream(message.asInstanceOf[ChannelBuffer]))
  }
}
