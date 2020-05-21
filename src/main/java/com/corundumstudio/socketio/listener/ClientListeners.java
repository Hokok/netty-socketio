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
package com.corundumstudio.socketio.listener;


/**
 *
 */
public interface ClientListeners {

    /**
     * 添加多类型事件监听器
     *
     * @param eventName  事件名称
     * @param listener   监听器
     * @param eventClass 事件类
     */
    void addMultiTypeEventListener(String eventName, MultiTypeEventListener listener, Class<?>... eventClass);

    /**
     * 添加事件监听器
     *
     * @param eventName  事件名称
     * @param eventClass 事件类
     * @param listener   监听器
     * @param <T>        类型
     */
    <T> void addEventListener(String eventName, Class<T> eventClass, DataListener<T> listener);

    /**
     * 添加事件拦截器
     *
     * @param eventInterceptor 事件拦截器
     */
    void addEventInterceptor(EventInterceptor eventInterceptor);

    /**
     * 添加断开连接监听器
     *
     * @param listener 监听器
     */
    void addDisconnectListener(DisconnectListener listener);

    /**
     * 添加连接监听器
     *
     * @param listener 监听器
     */
    void addConnectListener(ConnectListener listener);

    /**
     * 添加 ping 监听器
     *
     * @param listener 监听器
     */
    void addPingListener(PingListener listener);

    /**
     * 添加监听器
     *
     * @param listeners 监听器
     */
    void addListeners(Object listeners);

    /**
     * 添加监听器
     *
     * @param listeners      监听器
     * @param listenersClass 监听器类对象
     */
    void addListeners(Object listeners, Class<?> listenersClass);

    /**
     * 移除所有监听器
     *
     * @param eventName 事件名称
     */
    void removeAllListeners(String eventName);

}
