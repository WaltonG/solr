/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.client.solrj.impl;

import java.io.IOException;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.solr.SolrTestCase;
import org.apache.solr.client.solrj.ResponseParser;
import org.apache.solr.client.solrj.impl.LBHttpSolrClient.Builder;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.junit.Test;

/** Unit tests for {@link Builder}. */
public class LBHttpSolrClientBuilderTest extends SolrTestCase {
  private static final String ANY_BASE_SOLR_URL = "ANY_BASE_SOLR_URL";
  private static final HttpClient ANY_HTTP_CLIENT = HttpClientBuilder.create().build();
  private static final ResponseParser ANY_RESPONSE_PARSER = new NoOpResponseParser("xml");

  @Test
  public void providesHttpClientToClient() {
    try (LBHttpSolrClient createdClient =
        new Builder().withBaseEndpoint(ANY_BASE_SOLR_URL).withHttpClient(ANY_HTTP_CLIENT).build()) {
      assertEquals(createdClient.getHttpClient(), ANY_HTTP_CLIENT);
    }
  }

  @Test
  public void providesResponseParserToClient() {
    try (LBHttpSolrClient createdClient =
        new Builder()
            .withBaseEndpoint(ANY_BASE_SOLR_URL)
            .withResponseParser(ANY_RESPONSE_PARSER)
            .build()) {
      assertEquals(createdClient.getParser(), ANY_RESPONSE_PARSER);
    }
  }

  @Test
  public void testDefaultsToBinaryResponseParserWhenNoneProvided() {
    try (LBHttpSolrClient createdClient =
        new Builder().withBaseEndpoint(ANY_BASE_SOLR_URL).build()) {
      final ResponseParser usedParser = createdClient.getParser();

      assertTrue(usedParser instanceof JavaBinResponseParser);
    }
  }

  @Test
  public void testUsesTimeoutProvidedByHttpClient() throws IOException {

    ModifiableSolrParams clientParams = new ModifiableSolrParams();
    clientParams.set(HttpClientUtil.PROP_SO_TIMEOUT, 12345);
    clientParams.set(HttpClientUtil.PROP_CONNECTION_TIMEOUT, 67890);
    HttpClient httpClient = HttpClientUtil.createClient(clientParams);

    try (LBHttpSolrClient createdClient =
        new Builder().withBaseEndpoint(ANY_BASE_SOLR_URL).withHttpClient(httpClient).build()) {
      assertEquals(createdClient.getHttpClient(), httpClient);
      assertEquals(67890, createdClient.connectionTimeoutMillis);
      assertEquals(12345, createdClient.soTimeoutMillis);
    }
    HttpClientUtil.close(httpClient);
  }

  @Test
  public void testDefaultCollectionPassedFromBuilderToClient() throws IOException {
    try (LBHttpSolrClient createdClient =
        new LBHttpSolrClient.Builder()
            .withBaseEndpoint(ANY_BASE_SOLR_URL)
            .withDefaultCollection("aCollection")
            .build()) {
      assertEquals("aCollection", createdClient.getDefaultCollection());
    }
  }
}
