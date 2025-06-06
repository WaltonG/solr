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
package org.apache.solr.bench;

import static org.apache.commons.io.file.PathUtils.deleteDirectory;
import static org.apache.solr.bench.BaseBenchState.log;

import com.codahale.metrics.Meter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.cloud.MiniSolrCloudCluster;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SolrNamedThreadFactory;
import org.apache.solr.common.util.SuppressForbidden;
import org.apache.solr.embedded.JettySolrRunner;
import org.apache.solr.util.SolrTestNonSecureRandomProvider;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Control;

/** The base class for Solr JMH benchmarks that operate against a {@code MiniSolrCloudCluster}. */
public class MiniClusterState {

  /** The constant PROC_COUNT. */
  public static final int PROC_COUNT =
      ManagementFactory.getOperatingSystemMXBean().getAvailableProcessors();

  /** The type Mini cluster bench state. */
  @State(Scope.Benchmark)
  public static class MiniClusterBenchState {

    /** The Metrics enabled. */
    boolean metricsEnabled = true;

    /** The Nodes. */
    public List<String> nodes;

    public String zkHost;

    /** The Cluster. */
    MiniSolrCloudCluster cluster;

    /** The Client. */
    public Http2SolrClient client;

    /** The Run cnt. */
    int runCnt = 0;

    /** The Create collection and index. */
    boolean createCollectionAndIndex = true;

    /** The Delete mini cluster. */
    boolean deleteMiniCluster = true;

    /** Unless overridden we ensure SecureRandoms do not block. */
    boolean doNotWeakenSecureRandom = Boolean.getBoolean("doNotWeakenSecureRandom");

    /** The Mini cluster base dir. */
    Path miniClusterBaseDir;

    /** To Allow cluster reuse. */
    boolean allowClusterReuse = false;

    /** The Is warmup. */
    boolean isWarmup;

    private SplittableRandom random;
    private String workDir;

    private boolean useHttp1 = Boolean.getBoolean("solr.http1");

    /**
     * Tear down.
     *
     * @param benchmarkParams the benchmark params
     * @throws Exception the exception
     */
    @TearDown(Level.Iteration)
    public void tearDown(BenchmarkParams benchmarkParams) throws Exception {

      // dump Solr metrics
      Path metricsResults =
          Path.of(
              workDir,
              "metrics-results",
              benchmarkParams.id(),
              String.valueOf(runCnt++),
              benchmarkParams.getBenchmark() + ".txt");
      Files.createDirectories(metricsResults.getParent());

      cluster.dumpMetrics(metricsResults.getParent(), metricsResults.getFileName().toString());
    }

    /**
     * Check warm up.
     *
     * @param control the control
     * @throws Exception the exception
     */
    @Setup(Level.Iteration)
    public void checkWarmUp(Control control) throws Exception {
      isWarmup = control.stopMeasurement;
    }

    /**
     * Shutdown mini cluster.
     *
     * @param benchmarkParams the benchmark params
     * @throws Exception the exception
     */
    @TearDown(Level.Trial)
    public void shutdownMiniCluster(BenchmarkParams benchmarkParams, BaseBenchState baseBenchState)
        throws Exception {
      BaseBenchState.dumpHeap(benchmarkParams);

      IOUtils.closeQuietly(client);
      cluster.shutdown();
      logClusterDirectorySize();
    }

    private void logClusterDirectorySize() throws IOException {
      log("");
      Files.list(miniClusterBaseDir.toAbsolutePath())
          .forEach(
              (node) -> {
                try {
                  long clusterSize =
                      Files.walk(node)
                          .filter(Files::isRegularFile)
                          .mapToLong(
                              file -> {
                                try {
                                  return Files.size(file);
                                } catch (IOException e) {
                                  throw new RuntimeException(e);
                                }
                              })
                          .sum();
                  log("mini cluster node size (bytes) " + node + " " + clusterSize);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    }

    /**
     * Do setup.
     *
     * @param benchmarkParams the benchmark params
     * @param baseBenchState the base bench state
     * @throws Exception the exception
     */
    @Setup(Level.Trial)
    public void doSetup(BenchmarkParams benchmarkParams, BaseBenchState baseBenchState)
        throws Exception {

      if (!doNotWeakenSecureRandom) {
        // remove all blocking from all secure randoms
        SolrTestNonSecureRandomProvider.injectProvider();
      }

      workDir = System.getProperty("workBaseDir", "build/work");

      Path currentRelativePath = Path.of("");
      String s = currentRelativePath.toAbsolutePath().toString();
      log("current relative path is: " + s);
      log("work path is: " + workDir);

      System.setProperty("doNotWaitForMergesOnIWClose", "true");

      System.setProperty("pkiHandlerPrivateKeyPath", "");
      System.setProperty("pkiHandlerPublicKeyPath", "");

      System.setProperty("solr.default.confdir", "../server/solr/configsets/_default");

      this.random = new SplittableRandom(BaseBenchState.getRandomSeed());

      // not currently usable, but would enable JettySolrRunner's ill-conceived jetty.testMode and
      // allow using SSL

      // System.getProperty("jetty.testMode", "true");
      // SolrCloudTestCase.sslConfig = SolrTestCaseJ4.buildSSLConfig();

      String baseDirSysProp = System.getProperty("miniClusterBaseDir");
      if (baseDirSysProp != null) {
        deleteMiniCluster = false;
        miniClusterBaseDir = Path.of(baseDirSysProp);
        if (Files.exists(miniClusterBaseDir)) {
          createCollectionAndIndex = false;
          allowClusterReuse = true;
        }
      } else {
        miniClusterBaseDir = Path.of(workDir, "mini-cluster");
      }

      System.setProperty("metricsEnabled", String.valueOf(metricsEnabled));
    }

    /**
     * Metrics enabled.
     *
     * @param metricsEnabled the metrics enabled
     */
    public void metricsEnabled(boolean metricsEnabled) {
      this.metricsEnabled = metricsEnabled;
    }

    /**
     * Start mini cluster.
     *
     * @param nodeCount the node count
     */
    public void startMiniCluster(int nodeCount) {
      log("starting mini cluster at base directory: " + miniClusterBaseDir.toAbsolutePath());

      if (!allowClusterReuse && Files.exists(miniClusterBaseDir)) {
        log("mini cluster base directory exists, removing ...");
        try {
          deleteDirectory(miniClusterBaseDir);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        createCollectionAndIndex = true;
      } else if (Files.exists(miniClusterBaseDir)) {
        createCollectionAndIndex = false;
        deleteMiniCluster = false;
      }

      try {
        cluster =
            new MiniSolrCloudCluster.Builder(nodeCount, miniClusterBaseDir)
                .formatZkServer(false)
                .addConfig("conf", getFile("src/resources/configs/cloud-minimal/conf"))
                .configure();
      } catch (Exception e) {
        if (Files.exists(miniClusterBaseDir)) {
          try {
            deleteDirectory(miniClusterBaseDir);
          } catch (IOException ex) {
            e.addSuppressed(ex);
          }
        }
        throw new RuntimeException(e);
      }

      nodes = new ArrayList<>(nodeCount);
      List<JettySolrRunner> jetties = cluster.getJettySolrRunners();
      for (JettySolrRunner runner : jetties) {
        nodes.add(runner.getBaseUrl().toString());
      }
      zkHost = cluster.getZkServer().getZkAddress();

      client = new Http2SolrClient.Builder(nodes.get(0)).useHttp1_1(useHttp1).build();

      log("done starting mini cluster");
      log("");
    }

    /**
     * Gets random.
     *
     * @return the random
     */
    public SplittableRandom getRandom() {
      return random;
    }

    /**
     * Create collection.
     *
     * @param collection the collection
     * @param numShards the num shards
     * @param numReplicas the num replicas
     * @throws Exception the exception
     */
    public void createCollection(String collection, int numShards, int numReplicas)
        throws Exception {
      if (createCollectionAndIndex) {
        try {

          CollectionAdminRequest.Create request =
              CollectionAdminRequest.createCollection(collection, "conf", numShards, numReplicas);
          client.requestWithBaseUrl(
              nodes.get(random.nextInt(cluster.getJettySolrRunners().size())), null, request);

          cluster.waitForActiveCollection(
              collection, 15, TimeUnit.SECONDS, numShards, numShards * numReplicas);
        } catch (Exception e) {
          if (Files.exists(miniClusterBaseDir)) {
            deleteDirectory(miniClusterBaseDir);
          }
          throw e;
        }
      }
    }

    /** Setting useHttp1 to true will make the {@link #client} use http1 */
    public void setUseHttp1(boolean useHttp1) {
      if (client != null) {
        throw new IllegalStateException(
            "You can only change this setting before starting the Mini Cluster");
      }
      this.useHttp1 = useHttp1;
    }

    @SuppressForbidden(reason = "This module does not need to deal with logging context")
    public void index(String collection, Docs docs, int docCount) throws Exception {
      index(collection, docs, docCount, true);
    }

    /**
     * Index.
     *
     * @param collection the collection
     * @param docs the docs
     * @param docCount the doc count
     * @throws Exception the exception
     */
    public void index(String collection, Docs docs, int docCount, boolean parallel)
        throws Exception {
      if (createCollectionAndIndex) {
        log("indexing data for benchmark...");
        if (parallel) {
          indexParallel(collection, docs, docCount);
        } else {
          indexBatch(collection, docs, docCount, 10000);
        }
        log("done indexing data for benchmark");

        log("committing data ...");
        UpdateRequest commitRequest = new UpdateRequest();
        final var url = nodes.get(random.nextInt(cluster.getJettySolrRunners().size()));
        commitRequest.setAction(UpdateRequest.ACTION.COMMIT, false, true);
        client.requestWithBaseUrl(url, collection, commitRequest);
        log("done committing data");
      } else {
        cluster.waitForActiveCollection(collection, 15, TimeUnit.SECONDS);
      }

      QueryRequest queryRequest = new QueryRequest(new SolrQuery("q", "*:*", "rows", "1"));
      final var url = nodes.get(random.nextInt(cluster.getJettySolrRunners().size()));
      NamedList<Object> result =
          client.requestWithBaseUrl(url, collection, queryRequest).getResponse();

      log("sanity check of single row query result: " + result);
      log("");

      log("Dump Core Info");
      dumpCoreInfo();
    }

    @SuppressForbidden(reason = "This module does not need to deal with logging context")
    private void indexParallel(String collection, Docs docs, int docCount)
        throws InterruptedException {
      Meter meter = new Meter();
      ExecutorService executorService =
          Executors.newFixedThreadPool(
              Runtime.getRuntime().availableProcessors(),
              new SolrNamedThreadFactory("SolrJMH Indexer"));
      ScheduledExecutorService scheduledExecutor =
          Executors.newSingleThreadScheduledExecutor(
              new SolrNamedThreadFactory("SolrJMH Indexer Progress"));
      scheduledExecutor.scheduleAtFixedRate(
          () -> {
            if (meter.getCount() == docCount) {
              scheduledExecutor.shutdown();
            } else {
              log(meter.getCount() + " docs at " + meter.getMeanRate() + " doc/s");
            }
          },
          10,
          10,
          TimeUnit.SECONDS);
      for (int i = 0; i < docCount; i++) {
        executorService.execute(
            new Runnable() {
              final SplittableRandom threadRandom = random.split();

              @Override
              public void run() {
                UpdateRequest updateRequest = new UpdateRequest();
                final var url =
                    nodes.get(threadRandom.nextInt(cluster.getJettySolrRunners().size()));
                SolrInputDocument doc = docs.inputDocument();
                // log("add doc " + doc);
                updateRequest.add(doc);
                meter.mark();

                try {
                  client.requestWithBaseUrl(url, collection, updateRequest);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }
            });
      }

      log("done adding docs, waiting for executor to terminate...");

      executorService.shutdown();
      boolean result = false;
      while (!result) {
        result = executorService.awaitTermination(600, TimeUnit.MINUTES);
      }

      scheduledExecutor.shutdown();
    }

    private void indexBatch(String collection, Docs docs, int docCount, int batchSize)
        throws SolrServerException, IOException {
      Meter meter = new Meter();
      List<SolrInputDocument> batch = new ArrayList<>(batchSize);
      for (int i = 1; i <= docCount; i++) {
        batch.add(docs.inputDocument());
        if (i % batchSize == 0) {
          UpdateRequest updateRequest = new UpdateRequest();
          updateRequest.add(batch);
          client.requestWithBaseUrl(nodes.get(0), collection, updateRequest);
          meter.mark(batch.size());
          batch.clear();
          log(meter.getCount() + " docs at " + (long) meter.getMeanRate() + " doc/s");
        }
      }
      if (!batch.isEmpty()) {
        UpdateRequest updateRequest = new UpdateRequest();
        updateRequest.add(batch);
        client.requestWithBaseUrl(nodes.get(0), collection, updateRequest);
        meter.mark(batch.size());
        batch = null;
      }
      log(meter.getCount() + " docs at " + (long) meter.getMeanRate() + " doc/s");
    }

    /**
     * Wait for merges.
     *
     * @param collection the collection
     * @throws Exception the exception
     */
    public void waitForMerges(String collection) throws Exception {
      forceMerge(collection, Integer.MAX_VALUE);
    }

    /**
     * Force merge.
     *
     * @param collection the collection
     * @param maxMergeSegments the max merge segments
     * @throws Exception the exception
     */
    public void forceMerge(String collection, int maxMergeSegments) throws Exception {
      if (createCollectionAndIndex) {
        // we control segment count for a more informative benchmark *and* because background
        // merging would continue after
        // indexing and overlap with the benchmark
        if (maxMergeSegments == Integer.MAX_VALUE) {
          log("waiting for merges to finish...\n");
        } else {
          log("merging segments to " + maxMergeSegments + " segments ...\n");
        }

        UpdateRequest optimizeRequest = new UpdateRequest();
        final var url = nodes.get(random.nextInt(cluster.getJettySolrRunners().size()));
        optimizeRequest.setAction(UpdateRequest.ACTION.OPTIMIZE, false, true, maxMergeSegments);
        client.requestWithBaseUrl(url, collection, optimizeRequest);
      }
    }

    /**
     * Dump core info.
     *
     * @throws IOException the io exception
     */
    @SuppressForbidden(reason = "JMH uses std out for user output")
    public void dumpCoreInfo() throws IOException {
      cluster.dumpCoreInfo(
          !BaseBenchState.QUIET_LOG
              ? System.out
              : new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8));
    }
  }

  /**
   * Params modifiable solr params.
   *
   * @param moreParams the more params
   * @return the modifiable solr params
   */
  public static ModifiableSolrParams params(String... moreParams) {
    ModifiableSolrParams params = new ModifiableSolrParams();
    for (int i = 0; i < moreParams.length; i += 2) {
      params.add(moreParams[i], moreParams[i + 1]);
    }
    return params;
  }

  /**
   * Params modifiable solr params.
   *
   * @param params the params
   * @param moreParams the more params
   * @return the modifiable solr params
   */
  public static ModifiableSolrParams params(ModifiableSolrParams params, String... moreParams) {
    for (int i = 0; i < moreParams.length; i += 2) {
      params.add(moreParams[i], moreParams[i + 1]);
    }
    return params;
  }

  /**
   * Gets file.
   *
   * @param name the name
   * @return the file
   */
  public static Path getFile(String name) {
    final URL url =
        MiniClusterState.class
            .getClassLoader()
            .getResource(name.replace(FileSystems.getDefault().getSeparator(), "/"));
    if (url != null) {
      try {
        return Path.of(url.toURI());
      } catch (Exception e) {
        throw new RuntimeException(
            "Resource was found on classpath, but cannot be resolved to a "
                + "normal file (maybe it is part of a JAR file): "
                + name);
      }
    }
    Path file = Path.of(name);
    if (Files.exists(file)) {
      return file;
    } else {
      file = Path.of("../../../", name);
      if (Files.exists(file)) {
        return file;
      }
    }
    throw new RuntimeException(
        "Cannot find resource in classpath or in file-system (relative to CWD): "
            + name
            + " CWD="
            + Path.of("").toAbsolutePath());
  }
}
