/*
 * Copyright 2017, OpenSkywalking Organization All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Project repository: https://github.com/OpenSkywalking/skywalking
 */

package org.skywalking.apm.agent.core.remote;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.skywalking.apm.agent.core.conf.Config;
import org.skywalking.apm.agent.core.logging.api.ILog;
import org.skywalking.apm.agent.core.logging.api.LogManager;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import static org.skywalking.apm.agent.core.conf.RemoteDownstreamConfig.Collector.GRPC_SERVERS;

/**
 * Collector 服务发现客户端，基于 Rest 方式通信。
 *
 * The <code>DiscoveryRestServiceClient</code> try to get the collector's grpc-server list
 * in every 60 seconds,
 * and override {@link org.skywalking.apm.agent.core.conf.RemoteDownstreamConfig.Collector#GRPC_SERVERS}.
 *
 * @author wusheng
 */
public class DiscoveryRestServiceClient implements Runnable {
    private static final ILog logger = LogManager.getLogger(DiscoveryRestServiceClient.class);

    /**
     * Collector Naming Server 列表
     */
    private String[] serverList;
    /**
     * 选择的 {@link #serverList} 的编号
     */
    private volatile int selectedServer = -1;

    public DiscoveryRestServiceClient() {
        if (Config.Collector.SERVERS == null || Config.Collector.SERVERS.trim().length() == 0) {
            logger.warn("Collector server not configured.");
            return;
        }

        // 随机选择一个 Collector Naming Server
        serverList = Config.Collector.SERVERS.split(",");
        Random r = new Random();
        if (serverList.length > 0) {
            selectedServer = r.nextInt(serverList.length);
        }
    }

    @Override
    public void run() {
        try {
            findServerList();
        } catch (Throwable t) {
            logger.error(t, "Find server list fail.");
        }
    }

    private void findServerList() throws RESTResponseStatusError, IOException {
        CloseableHttpClient httpClient = HttpClients.custom().build();
        try {
            HttpGet httpGet = buildGet();
            if (httpGet != null) {
                CloseableHttpResponse httpResponse = httpClient.execute(httpGet);
                int statusCode = httpResponse.getStatusLine().getStatusCode();
                if (200 != statusCode) { // 切换下一个 Collector Naming Server
                    findBackupServer();
                    throw new RESTResponseStatusError(statusCode);
                } else {
                    // 处理返回结果
                    JsonArray serverList = new Gson().fromJson(EntityUtils.toString(httpResponse.getEntity()), JsonArray.class);
                    if (serverList != null && serverList.size() > 0) {
                        LinkedList<String> newServerList = new LinkedList<String>();
                        for (JsonElement element : serverList) {
                            newServerList.add(element.getAsString());
                        }

                        // 若有变化，进行更新
                        if (!isListEquals(newServerList, GRPC_SERVERS)) {
                            GRPC_SERVERS = newServerList;
                            logger.debug("Refresh GRPC server list: {}", GRPC_SERVERS);
                        } else {
                            logger.debug("GRPC server list remain unchanged: {}", GRPC_SERVERS);
                        }

                    }
                }
            }
        } catch (IOException e) {
            // 切换下一个 Collector Naming Server
            findBackupServer();
            throw e;
        } finally {
            httpClient.close();
        }
    }

    private boolean isListEquals(List<String> list1, List<String> list2) {
        if (list1.size() != list2.size()) {
            return false;
        }

        for (String ip1 : list1) {
            if (!list2.contains(ip1)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Prepare the given message for HTTP Post service.
     *
     * @return {@link HttpGet}, when is ready to send. otherwise, null.
     */
    private HttpGet buildGet() {
        if (selectedServer == -1) {
            //no available server
            return null;
        }
        HttpGet httpGet = new HttpGet("http://" + serverList[selectedServer] + Config.Collector.DISCOVERY_SERVICE_NAME);

        return httpGet;
    }

    /**
     * Choose the next server in {@link #serverList}, by moving {@link #selectedServer}.
     */
    private void findBackupServer() {
        selectedServer++;
        if (selectedServer >= serverList.length) {
            selectedServer = 0;
        }

        if (serverList.length == 0) {
            selectedServer = -1;
        }
    }
}
