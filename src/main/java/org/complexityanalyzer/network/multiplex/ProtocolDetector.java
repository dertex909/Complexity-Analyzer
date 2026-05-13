package org.complexityanalyzer.network.multiplex;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.complexityanalyzer.ComplexityAnalyzer;

import java.util.List;

public class ProtocolDetector extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) return;
        if (isHttp(in)) setupHttpPipeline(ctx);
        else ctx.pipeline().remove(this);
    }

    private boolean isHttp(ByteBuf in) {
        int m = in.getInt(in.readerIndex());
        return (((m - 0x20202020) | (0x5A5A5A5A - m)) & 0x80808080) == 0;
    }

    private void setupHttpPipeline(ChannelHandlerContext ctx) {
        ComplexityAnalyzer.LOGGER.info("[Cabin] HTTP request detected on game port, switching pipeline");

        ctx.pipeline().addAfter(ctx.name(), "http_codec", new HttpServerCodec());
        ctx.pipeline().addAfter("http_codec", "http_aggregator", new HttpObjectAggregator(10 * 1024 * 1024));
        ctx.pipeline().addAfter("http_aggregator", "http_chunked", new ChunkedWriteHandler());
        ctx.pipeline().addAfter("http_chunked", "cabin_handler", new CabinNettyHandler());

        ctx.pipeline().remove(this);
    }
}