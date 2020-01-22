/*
 * Copyright (c) 2015 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.heroic.elasticsearch;

import com.spotify.heroic.elasticsearch.index.IndexMapping;
import com.spotify.heroic.elasticsearch.index.NoIndexSelectedException;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.ResolvableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequestBuilder;
import org.elasticsearch.action.delete.DeleteRequestBuilder;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchScrollRequestBuilder;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.IndicesAdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common connection abstraction between Node and TransportClient.
 */
public class Connection {
    private static final Logger log = LoggerFactory.getLogger(Connection.class);
    private final AsyncFramework async;
    private final IndexMapping index;
    private final ClientSetup.ClientWrapper client;

    private final String templateName;
    private final BackendType type;

    @java.beans.ConstructorProperties({ "async", "index", "client", "templateName", "type" })
    public Connection(final AsyncFramework async, final IndexMapping index,
                      final ClientSetup.ClientWrapper client, final String templateName,
                      final BackendType type) {
        this.async = async;
        this.index = index;
        this.client = client;
        this.templateName = templateName;
        this.type = type;
    }

    public AsyncFuture<Void> close() {
        final List<AsyncFuture<Void>> futures = new ArrayList<>();

        futures.add(async.call(() -> {
            client.getShutdown().run();
            return null;
        }));

        return async.collectAndDiscard(futures);
    }

    public AsyncFuture<Void> configure() {
        final IndicesAdminClient indices = client.getClient().admin().indices();

        final List<AsyncFuture<AcknowledgedResponse>> writes = new ArrayList<>();

        // ES 7+ no longer allows indexes to have multiple types. Each type is now it's own index.
        for (final Map.Entry<String, Map<String, Object>> mapping : type.getMappings().entrySet()) {

          final String indexType = mapping.getKey();

          final String templateWithType = templateName + "-" + indexType;
          final String s = index.template().replaceAll("\\*", indexType + "-*");

          log.info("[{}] updating template for {}", templateWithType, s);

            final PutIndexTemplateRequestBuilder put = indices.preparePutTemplate(templateWithType)
                .setSettings(type.getSettings())
                .setPatterns(List.of(s))
                .addMapping(mapping.getKey(), mapping.getValue());

            final ResolvableFuture<AcknowledgedResponse> future = async.future();
            writes.add(future);
            put.execute(new ActionListener<>() {
              @Override
              public void onResponse(AcknowledgedResponse response) {
                if (!response.isAcknowledged()) {
                  future.fail(new Exception("request not acknowledged"));
                    return;
                }
                future.resolve(null);
              }

              @Override
              public void onFailure(Exception e) {
                  future.fail(e);
              }
          });
        }

        return async.collectAndDiscard(writes);
    }

    public String[] readIndices(String type) throws NoIndexSelectedException {
        return index.readIndices(type);
    }

    public String[] writeIndices(String type) throws NoIndexSelectedException {
        return index.writeIndices(type);
    }

    public SearchRequestBuilder search(String type) throws NoIndexSelectedException {
        return index.search(client.getClient(), type);
    }

    public SearchRequestBuilder count(String type) throws NoIndexSelectedException {
        return index.count(client.getClient(), type);
    }

    public IndexRequestBuilder index(String index, String type) {
        return client.getClient().prepareIndex(index, type);
    }

    public SearchScrollRequestBuilder prepareSearchScroll(String scrollId) {
        return client.getClient().prepareSearchScroll(scrollId);
    }

    public List<DeleteRequestBuilder> delete(String type, String id)
        throws NoIndexSelectedException {
        return index.delete(client.getClient(), type, id);
    }

    public String toString() {
        return "Connection(index=" + this.index + ", client=" + this.client + ")";
    }
}
