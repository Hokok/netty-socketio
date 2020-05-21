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
package com.corundumstudio.socketio;

import java.util.Collection;
import java.util.UUID;

import com.corundumstudio.socketio.listener.ClientListeners;

/**
 * Fully thread-safe.
 */
public interface SocketIONamespace extends ClientListeners {

    /**
     * 获取命名空间
     *
     * @return 返回命名空间
     */
    String getName();

    /**
     * 广播操作器
     *
     * @return
     */
    BroadcastOperations getBroadcastOperations();

    /**
     * 获取Room广播操作器
     *
     * @param room
     * @return
     */
    BroadcastOperations getRoomOperations(String room);

    /**
     * Get all clients connected to namespace
     *
     * @return collection of clients
     */
    Collection<SocketIOClient> getAllClients();

    /**
     * 获取客户端
     *
     * @param uuid
     * @return
     */
    SocketIOClient getClient(UUID uuid);

}
