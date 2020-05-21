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
package com.corundumstudio.socketio.namespace;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import com.corundumstudio.socketio.AckMode;
import com.corundumstudio.socketio.AckRequest;
import com.corundumstudio.socketio.BroadcastOperations;
import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.MultiTypeArgs;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIONamespace;
import com.corundumstudio.socketio.annotation.ScannerEngine;
import com.corundumstudio.socketio.listener.*;
import com.corundumstudio.socketio.protocol.JsonSupport;
import com.corundumstudio.socketio.protocol.Packet;
import com.corundumstudio.socketio.store.StoreFactory;
import com.corundumstudio.socketio.store.pubsub.JoinLeaveMessage;
import com.corundumstudio.socketio.store.pubsub.PubSubType;
import com.corundumstudio.socketio.transport.NamespaceClient;

import io.netty.util.internal.PlatformDependent;

/**
 * Hub object for all clients in one namespace.
 * Namespace shares by different namespace-clients.
 *
 * @see com.corundumstudio.socketio.transport.NamespaceClient
 */
public class Namespace implements SocketIONamespace {

    public static final String DEFAULT_NAME = "";

    /***#######################################  监听器组件  #######################################***/
    // 扫描引擎
    private final ScannerEngine engine = new ScannerEngine();
    // 事件监听器
    private final ConcurrentMap<String, EventEntry<?>> eventListeners = PlatformDependent.newConcurrentHashMap();
    // 连接监听器
    private final Queue<ConnectListener> connectListeners = new ConcurrentLinkedQueue<ConnectListener>();
    // 断连监听器
    private final Queue<DisconnectListener> disconnectListeners = new ConcurrentLinkedQueue<DisconnectListener>();
    // ping监听器
    private final Queue<PingListener> pingListeners = new ConcurrentLinkedQueue<PingListener>();
    // 事件拦截器
    private final Queue<EventInterceptor> eventInterceptors = new ConcurrentLinkedQueue<EventInterceptor>();
    /***#######################################  监听器组件  #######################################***/


    /***#######################################  客户端信息  #######################################***/
    // 所有客户端
    private final Map<UUID, SocketIOClient> allClients = PlatformDependent.newConcurrentHashMap();
    // 房间客户端
    private final ConcurrentMap<String, Set<UUID>> roomClients = PlatformDependent.newConcurrentHashMap();
    // 客户端房间
    private final ConcurrentMap<UUID, Set<String>> clientRooms = PlatformDependent.newConcurrentHashMap();
    /***#######################################  客户端信息  #######################################***/

    // 名称
    private final String name;
    // 应答模式
    private final AckMode ackMode;
    // json支持
    private final JsonSupport jsonSupport;
    // 存储工厂: HazelcatstStore -> ,RedissonStore -> , MemoryStore ->
    private final StoreFactory storeFactory;
    // 异常监听器
    private final ExceptionListener exceptionListener;


    public Namespace(String name, Configuration configuration) {
        super();
        this.name = name;
        this.jsonSupport = configuration.getJsonSupport();
        this.storeFactory = configuration.getStoreFactory();
        this.exceptionListener = configuration.getExceptionListener();
        this.ackMode = configuration.getAckMode();
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * 添加客户端
     *
     * @param client 客户端
     */
    public void addClient(SocketIOClient client) {
        allClients.put(client.getSessionId(), client);
    }

    /**
     * 添加多类型时间监听器
     *
     * @param eventName  事件名称
     * @param listener   监听器
     * @param eventClass 事件类
     */
    @Override
    public void addMultiTypeEventListener(String eventName, MultiTypeEventListener listener,
                                          Class<?>... eventClass) {

        EventEntry entry = eventListeners.get(eventName);
        if (entry == null) {
            entry = new EventEntry();
            EventEntry<?> oldEntry = eventListeners.putIfAbsent(eventName, entry);
            if (oldEntry != null) {
                entry = oldEntry;
            }
        }

        entry.addListener(listener);
        jsonSupport.addEventMapping(name, eventName, eventClass);
    }

    /**
     * 事件名称
     *
     * @param eventName 事件名称
     */
    @Override
    public void removeAllListeners(String eventName) {
        EventEntry<?> entry = eventListeners.remove(eventName);
        if (entry != null) {
            jsonSupport.removeEventMapping(name, eventName);
        }
    }

    /**
     * @param eventName  事件名称
     * @param eventClass 事件类
     * @param listener   监听器
     * @param <T>
     */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> void addEventListener(String eventName, Class<T> eventClass, DataListener<T> listener) {

        EventEntry entry = eventListeners.get(eventName);
        if (entry == null) {
            entry = new EventEntry<T>();
            EventEntry<?> oldEntry = eventListeners.putIfAbsent(eventName, entry);
            if (oldEntry != null) {
                entry = oldEntry;
            }
        }

        entry.addListener(listener);
        jsonSupport.addEventMapping(name, eventName, eventClass);
    }

    /**
     * @param eventInterceptor 事件拦截器
     */
    @Override
    public void addEventInterceptor(EventInterceptor eventInterceptor) {
        eventInterceptors.add(eventInterceptor);
    }

    /**
     * OnEvent 事件
     *
     * @param client     客户端
     * @param eventName  事件名称
     * @param args       参数
     * @param ackRequest ack请求
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void onEvent(NamespaceClient client, String eventName, List<Object> args, AckRequest ackRequest) {

        // 通过事件名称获取监听器
        EventEntry entry = eventListeners.get(eventName);
        if (entry == null) {
            return;
        }

        try {

            // 处理 onData 监听器
            Queue<DataListener> listeners = entry.getListeners();
            for (DataListener dataListener : listeners) {
                Object data = getEventData(args, dataListener);
                dataListener.onData(client, data, ackRequest);
            }

            // 拦截器处理
            for (EventInterceptor eventInterceptor : eventInterceptors) {
                eventInterceptor.onEvent(client, eventName, args, ackRequest);
            }

        } catch (Exception e) {

            // 异常处理器处理
            exceptionListener.onEventException(e, args, client);
            if (ackMode == AckMode.AUTO_SUCCESS_ONLY) {
                return;
            }

        }

        // 发送应答给客户端
        sendAck(ackRequest);
    }

    /**
     * 发送应答
     *
     * @param ackRequest ackRequest应答请求
     */
    private void sendAck(AckRequest ackRequest) {
        if (ackMode == AckMode.AUTO || ackMode == AckMode.AUTO_SUCCESS_ONLY) {
            // send ack response if it not executed
            // during {@link DataListener#onData} invocation
            ackRequest.sendAckData(Collections.emptyList());
        }
    }

    /**
     * 获取事件参数
     *
     * @param args         参数
     * @param dataListener 数据监听器
     * @return 事件数据
     */
    private Object getEventData(List<Object> args, DataListener<?> dataListener) {
        if (dataListener instanceof MultiTypeEventListener) {
            return new MultiTypeArgs(args);
        } else {
            if (!args.isEmpty()) {
                return args.get(0);
            }
        }
        return null;
    }


    /**
     * @param listener 监听器
     */
    @Override
    public void addDisconnectListener(DisconnectListener listener) {
        disconnectListeners.add(listener);
    }

    /**
     * 断连处理
     *
     * @param client 客户端
     */
    public void onDisconnect(SocketIOClient client) {

        // 获取所有的 Rooms
        Set<String> joinedRooms = client.getAllRooms();
        allClients.remove(client.getSessionId());

        // 离开 Room
        leave(getName(), client.getSessionId());
        storeFactory.pubSubStore().publish(PubSubType.LEAVE, new JoinLeaveMessage(client.getSessionId(), getName(), getName()));

        for (String joinedRoom : joinedRooms) {
            leave(roomClients, joinedRoom, client.getSessionId());
        }
        clientRooms.remove(client.getSessionId());

        try {
            // 断连监听器
            for (DisconnectListener listener : disconnectListeners) {
                listener.onDisconnect(client);
            }
        } catch (Exception e) {
            // 异常监听器
            exceptionListener.onDisconnectException(e, client);
        }

    }

    /**
     * @param listener 监听器
     */
    @Override
    public void addConnectListener(ConnectListener listener) {
        connectListeners.add(listener);
    }

    /**
     * @param client
     */
    public void onConnect(SocketIOClient client) {

        join(getName(), client.getSessionId());
        storeFactory.pubSubStore().publish(PubSubType.JOIN, new JoinLeaveMessage(client.getSessionId(), getName(), getName()));

        try {
            for (ConnectListener listener : connectListeners) {
                listener.onConnect(client);
            }
        } catch (Exception e) {
            exceptionListener.onConnectException(e, client);
        }

    }

    @Override
    public void addPingListener(PingListener listener) {
        pingListeners.add(listener);
    }

    public void onPing(SocketIOClient client) {
        try {
            for (PingListener listener : pingListeners) {
                listener.onPing(client);
            }
        } catch (Exception e) {
            exceptionListener.onPingException(e, client);
        }
    }

    @Override
    public BroadcastOperations getBroadcastOperations() {
        return new BroadcastOperations(allClients.values(), storeFactory);
    }

    @Override
    public BroadcastOperations getRoomOperations(String room) {
        return new BroadcastOperations(getRoomClients(room), storeFactory);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Namespace other = (Namespace) obj;
        if (name == null) {
            if (other.name != null)
                return false;
        } else if (!name.equals(other.name))
            return false;
        return true;
    }

    @Override
    public void addListeners(Object listeners) {
        addListeners(listeners, listeners.getClass());
    }

    @Override
    public void addListeners(Object listeners, Class<?> listenersClass) {
        engine.scan(this, listeners, listenersClass);
    }

    public void joinRoom(String room, UUID sessionId) {
        join(room, sessionId);
        storeFactory.pubSubStore().publish(PubSubType.JOIN, new JoinLeaveMessage(sessionId, room, getName()));
    }

    public void dispatch(String room, Packet packet) {
        Iterable<SocketIOClient> clients = getRoomClients(room);

        for (SocketIOClient socketIOClient : clients) {
            socketIOClient.send(packet);
        }
    }

    /**
     * @param map
     * @param key
     * @param value
     * @param <K>
     * @param <V>
     */
    private <K, V> void join(ConcurrentMap<K, Set<V>> map, K key, V value) {

        Set<V> clients = map.get(key);
        if (clients == null) {
            clients = Collections.newSetFromMap(PlatformDependent.<V, Boolean>newConcurrentHashMap());
            Set<V> oldClients = map.putIfAbsent(key, clients);
            if (oldClients != null) {
                clients = oldClients;
            }
        }
        clients.add(value);

        // object may be changed due to other concurrent call
        if (clients != map.get(key)) {

            // re-join if queue has been replaced
            join(map, key, value);
        }

    }

    /**
     * 加入房间 room
     *
     * @param room      房间号
     * @param sessionId 客户端sessionId
     */
    public void join(String room, UUID sessionId) {
        join(roomClients, room, sessionId);
        join(clientRooms, sessionId, room);
    }

    /**
     * 离开房间
     *
     * @param room      房间号
     * @param sessionId 客户端sessionId
     */
    public void leaveRoom(String room, UUID sessionId) {

        // 将客户端离线
        leave(room, sessionId);

        // 发布离线事件
        storeFactory.pubSubStore().publish(PubSubType.LEAVE, new JoinLeaveMessage(sessionId, room, getName()));

    }

    /**
     * 离开
     *
     * @param map       roomClients or clientsRooms
     * @param room      房间号
     * @param sessionId 客户端sessionId
     */
    private <K, V> void leave(ConcurrentMap<K, Set<V>> map, K room, V sessionId) {
        Set<V> clients = map.get(room);
        if (clients == null) {
            return;
        }
        clients.remove(sessionId);

        if (clients.isEmpty()) {
            map.remove(room, Collections.emptySet());
        }
    }

    /**
     * 离开房间 -->
     *
     * @param room      房间号
     * @param sessionId 客户端sessionId
     */
    public void leave(String room, UUID sessionId) {

        // 离开--> 从 room中删除sessionId
        leave(roomClients, room, sessionId);

        // 离开--> 从 sessionId 删除加入的房间中
        leave(clientRooms, sessionId, room);

    }

    /**
     * 获取客户端加入的房间
     *
     * @param client 客户端
     * @return 该客户端加入的房间
     */
    public Set<String> getRooms(SocketIOClient client) {
        Set<String> res = clientRooms.get(client.getSessionId());
        if (res == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(res);
    }

    /**
     * 获取所有的房间
     *
     * @return 所有房间
     */
    public Set<String> getRooms() {
        return roomClients.keySet();
    }

    /**
     * 获取房间中的所有客户端
     *
     * @param room 房间号
     * @return 客户端信息
     */
    public Iterable<SocketIOClient> getRoomClients(String room) {
        Set<UUID> sessionIds = roomClients.get(room);

        if (sessionIds == null) {
            return Collections.emptyList();
        }

        List<SocketIOClient> result = new ArrayList<SocketIOClient>();
        for (UUID sessionId : sessionIds) {
            SocketIOClient client = allClients.get(sessionId);
            if (client != null) {
                result.add(client);
            }
        }
        return result;
    }

    @Override
    public Collection<SocketIOClient> getAllClients() {
        return Collections.unmodifiableCollection(allClients.values());
    }

    public JsonSupport getJsonSupport() {
        return jsonSupport;
    }

    @Override
    public SocketIOClient getClient(UUID uuid) {
        return allClients.get(uuid);
    }

}
