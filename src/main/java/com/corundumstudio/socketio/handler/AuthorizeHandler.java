/**
 * Copyright (c) 2012-2019 Nikita Koksharov
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.corundumstudio.socketio.handler;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.Disconnectable;
import com.corundumstudio.socketio.DisconnectableHub;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.Transport;
import com.corundumstudio.socketio.ack.AckManager;
import com.corundumstudio.socketio.messages.HttpErrorMessage;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.namespace.NamespacesHub;
import com.corundumstudio.socketio.protocol.AuthPacket;
import com.corundumstudio.socketio.protocol.Packet;
import com.corundumstudio.socketio.protocol.PacketType;
import com.corundumstudio.socketio.scheduler.CancelableScheduler;
import com.corundumstudio.socketio.scheduler.SchedulerKey;
import com.corundumstudio.socketio.scheduler.SchedulerKey.Type;
import com.corundumstudio.socketio.store.StoreFactory;
import com.corundumstudio.socketio.store.pubsub.ConnectMessage;
import com.corundumstudio.socketio.store.pubsub.PubSubType;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

/**
 * 授权认证处理器
 */
@Sharable
public class AuthorizeHandler extends ChannelInboundHandlerAdapter implements Disconnectable {

    private static final Logger log = LoggerFactory.getLogger(AuthorizeHandler.class);

    private final CancelableScheduler disconnectScheduler;

    private final String connectPath;
    private final Configuration configuration;
    private final NamespacesHub namespacesHub;
    private final StoreFactory storeFactory;
    private final DisconnectableHub disconnectable;
    private final AckManager ackManager;
    private final ClientsBox clientsBox;

    public AuthorizeHandler(String connectPath, CancelableScheduler scheduler, Configuration configuration, NamespacesHub namespacesHub, StoreFactory storeFactory,
                            DisconnectableHub disconnectable, AckManager ackManager, ClientsBox clientsBox) {
        super();
        this.connectPath = connectPath;
        this.configuration = configuration;
        this.disconnectScheduler = scheduler;
        this.namespacesHub = namespacesHub;
        this.storeFactory = storeFactory;
        this.disconnectable = disconnectable;
        this.ackManager = ackManager;
        this.clientsBox = clientsBox;
    }

    /**
     * channel 激活事件方法
     *
     * @param ctx contentChannel
     * @throws Exception 异常
     */
    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {

        // 1、调度任务,在指定的时间段内 如果没有首个数据发送过来,则关闭Channel通道
        SchedulerKey key = new SchedulerKey(Type.PING_TIMEOUT, ctx.channel());
        disconnectScheduler.schedule(key, new Runnable() {
            @Override
            public void run() {
                ctx.channel().close();
                log.debug("Client with ip {} opened channel but doesn't send any data! Channel closed!", ctx.channel().remoteAddress());
            }
        }, configuration.getFirstDataTimeout(), TimeUnit.MILLISECONDS);

        // 2、父类处理时间激活
        super.channelActive(ctx);

    }

    /**
     * 通道数据读取
     *
     * @param ctx channelHandlerContext 对象
     * @param msg 消息
     * @throws Exception 异常
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        // 1、如果有数据进行传输,则将 firstDataTimeout 调度事件取消。
        SchedulerKey key = new SchedulerKey(Type.PING_TIMEOUT, ctx.channel());
        disconnectScheduler.cancel(key);

        // 2、判断是不是Http请求
        if (msg instanceof FullHttpRequest) {

            FullHttpRequest req = (FullHttpRequest) msg;
            Channel channel = ctx.channel();
            QueryStringDecoder queryDecoder = new QueryStringDecoder(req.uri());

            if (!configuration.isAllowCustomRequests()
                    && !queryDecoder.path().startsWith(connectPath)) {
                // 2.1、 如果不允许自定义请求 而且不是以配置的 connectPath 开头的。则进行返回 坏的请求。
                HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
                channel.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
                req.release();
                return;
            }

            List<String> sid = queryDecoder.parameters().get("sid");
            if (queryDecoder.path().equals(connectPath)
                    && sid == null) {
                // 2.2、进行授权处理
                String origin = req.headers().get(HttpHeaderNames.ORIGIN);
                if (!authorize(ctx, channel, origin, queryDecoder.parameters(), req)) {
                    req.release();
                    return;
                }
                // forward message to polling or websocket handler to bind channel 。 消息转发到轮询或websocket处理程序将通道;
            }
        }

        // 3、传递下一个 channelHandler 处理消息
        ctx.fireChannelRead(msg);
    }

    /**
     * 授权处理
     *
     * @param ctx     channel上下文对象
     * @param channel channel
     * @param origin  来源
     * @param params  参数
     * @param req     请求
     * @return 是否授权通过
     * @throws IOException
     */
    private boolean authorize(ChannelHandlerContext ctx, Channel channel, String origin, Map<String, List<String>> params, FullHttpRequest req)
            throws IOException {

        // 1、 获取请求中的所有Header
        Map<String, List<String>> headers = new HashMap<String, List<String>>(req.headers().names().size());
        for (String name : req.headers().names()) {
            List<String> values = req.headers().getAll(name);
            headers.put(name, values);
        }

        // 2、握手数据
        HandshakeData data = new HandshakeData(req.headers(), params,
                (InetSocketAddress) channel.remoteAddress(),
                (InetSocketAddress) channel.localAddress(),
                req.uri(), origin != null && !origin.equalsIgnoreCase("null"));

        boolean result = false;
        try {
            // 3、通过 configuration.getAuthorizationListener 获取收钱监听器进行授权。 PS: 如果没有自定的授权处理则默认调用 com.corundumstudio.socketio.handler.SuccessAuthorizationListener
            result = configuration.getAuthorizationListener().isAuthorized(data);
        } catch (Exception e) {
            log.error("Authorization error", e);
        }


        if (!result) {
            // 4、如果没有进行授权通过。则返回 【未授权】,并关闭channel。
            HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
            channel.writeAndFlush(res)
                    .addListener(ChannelFutureListener.CLOSE);
            log.debug("Handshake unauthorized, query params: {} headers: {}", params, headers);
            return false;
        }

        // 5、 通过配置选择策略进行生成 sessionId
        UUID sessionId = null;
        if (configuration.isRandomSession()) {
            // 5.1、 如果用的是随机Session,则用 UUID 进行生成 sessionId
            sessionId = UUID.randomUUID();
        } else {
            // 5.2、 生成SessionId
            sessionId = this.generateOrGetSessionIdFromRequest(req.headers());
        }

        // 6、 获取协议 transport。如果没有携带该参数,则关闭该Channel。
        List<String> transportValue = params.get("transport");
        if (transportValue == null) {
            log.error("Got no transports for request {}", req.uri());

            HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
            channel.writeAndFlush(res).addListener(ChannelFutureListener.CLOSE);
            return false;
        }

        // 7、 判断协议是否支持。目前仅仅支持 webSocket 和 ping
        Transport transport = Transport.byName(transportValue.get(0));
        if (!configuration.getTransports().contains(transport)) {
            Map<String, Object> errorData = new HashMap<String, Object>();
            errorData.put("code", 0);
            errorData.put("message", "Transport unknown");

            channel.attr(EncoderHandler.ORIGIN).set(origin);
            channel.writeAndFlush(new HttpErrorMessage(errorData));
            return false;
        }

        ClientHead client = new ClientHead(sessionId, ackManager, disconnectable, storeFactory, data, clientsBox, transport, disconnectScheduler, configuration);
        channel.attr(ClientHead.CLIENT).set(client);
        clientsBox.addClient(client);

        String[] transports = {};
        if (configuration.getTransports().contains(Transport.WEBSOCKET)) {
            transports = new String[]{"websocket"};
        }

        // 8、 发送授权包
        AuthPacket authPacket = new AuthPacket(sessionId, transports, configuration.getPingInterval(),
                configuration.getPingTimeout());
        Packet packet = new Packet(PacketType.OPEN);
        packet.setData(authPacket);
        client.send(packet);

        // 9、 调度 ping任务。 如果超过时间间隔没有ping则进行关闭channel
        client.schedulePingTimeout();
        log.debug("Handshake authorized for sessionId: {}, query params: {} headers: {}", sessionId, params, headers);
        return true;
    }

    /**
     * This method will either generate a new random sessionId or will retrieve the value stored
     * in the "io" cookie.  Failures to parse will cause a logging warning to be generated and a
     * random uuid to be generated instead (same as not passing a cookie in the first place).
     */
    private UUID generateOrGetSessionIdFromRequest(HttpHeaders headers) {
        List<String> values = headers.getAll("io");
        if (values.size() == 1) {
            try {
                return UUID.fromString(values.get(0));
            } catch (IllegalArgumentException iaex) {
                log.warn("Malformed UUID received for session! io=" + values.get(0));
            }
        }

        for (String cookieHeader : headers.getAll(HttpHeaderNames.COOKIE)) {
            Set<Cookie> cookies = ServerCookieDecoder.LAX.decode(cookieHeader);

            for (Cookie cookie : cookies) {
                if (cookie.name().equals("io")) {
                    try {
                        return UUID.fromString(cookie.value());
                    } catch (IllegalArgumentException iaex) {
                        log.warn("Malformed UUID received for session! io=" + cookie.value());
                    }
                }
            }
        }

        return UUID.randomUUID();
    }

    public void connect(UUID sessionId) {
        SchedulerKey key = new SchedulerKey(Type.PING_TIMEOUT, sessionId);
        disconnectScheduler.cancel(key);
    }

    public void connect(ClientHead client) {
        Namespace ns = namespacesHub.get(Namespace.DEFAULT_NAME);

        if (!client.getNamespaces().contains(ns)) {
            Packet packet = new Packet(PacketType.MESSAGE);
            packet.setSubType(PacketType.CONNECT);
            client.send(packet);

            configuration.getStoreFactory().pubSubStore().publish(PubSubType.CONNECT, new ConnectMessage(client.getSessionId()));

            SocketIOClient nsClient = client.addNamespaceClient(ns);
            ns.onConnect(nsClient);
        }
    }

    @Override
    public void onDisconnect(ClientHead client) {
        clientsBox.removeClient(client.getSessionId());
    }

}
