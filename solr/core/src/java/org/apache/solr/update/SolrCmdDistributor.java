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
package org.apache.solr.update;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import org.apache.http.NoHttpResponseException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.ConcurrentUpdateSolrClient;
import org.apache.solr.client.solrj.impl.JavaBinResponseParser;
import org.apache.solr.client.solrj.request.AbstractUpdateRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ZkCoreNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.Diagnostics;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.update.processor.DistributedUpdateProcessor;
import org.apache.solr.update.processor.DistributedUpdateProcessor.LeaderRequestReplicationTracker;
import org.apache.solr.update.processor.DistributedUpdateProcessor.RollupRequestReplicationTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Used for distributing commands from a shard leader to its replicas. */
public class SolrCmdDistributor implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private StreamingSolrClients clients;
  private boolean finished = false; // see finish()

  private int retryPause = 500;

  private final List<SolrError> allErrors = new ArrayList<>();
  private final List<SolrError> errors = Collections.synchronizedList(new ArrayList<>());

  private final CompletionService<Object> completionService;
  private final Set<Future<Object>> pending = new HashSet<>();

  public SolrCmdDistributor(UpdateShardHandler updateShardHandler) {
    this.clients = new StreamingSolrClients(updateShardHandler);
    this.completionService =
        new ExecutorCompletionService<>(updateShardHandler.getUpdateExecutor());
  }

  /* For tests only */
  SolrCmdDistributor(StreamingSolrClients clients, int retryPause) {
    this.clients = clients;
    this.retryPause = retryPause;
    completionService = new ExecutorCompletionService<>(clients.getUpdateExecutor());
  }

  public void finish() {
    try {
      assert !finished : "lifecycle sanity check";
      finished = true;

      blockAndDoRetries();
    } catch (IOException e) {
      log.warn("Unable to finish sending updates", e);
    } finally {
      clients.shutdown();
    }
  }

  @Override
  public void close() {
    clients.shutdown();
  }

  private void doRetriesIfNeeded() throws IOException {
    // NOTE: retries will be forwards to a single url

    List<SolrError> errors = new ArrayList<>(this.errors);
    errors.addAll(clients.getErrors());
    List<SolrError> resubmitList = new ArrayList<>();

    if (log.isInfoEnabled() && errors.size() > 0) {
      log.info("SolrCmdDistributor found {} errors", errors.size());
    }

    if (log.isDebugEnabled() && errors.size() > 0) {
      StringBuilder builder = new StringBuilder("SolrCmdDistributor found:");
      int maxErrorsToShow = 10;
      for (SolrError e : errors) {
        if (maxErrorsToShow-- <= 0) break;
        builder.append("\n").append(e);
      }
      if (errors.size() > 10) {
        builder.append("\n... and ");
        builder.append(errors.size() - 10);
        builder.append(" more");
      }
      log.debug("{}", builder);
    }

    for (SolrError err : errors) {
      try {
        /*
         * if this is a retryable request we may want to retry, depending on the error we received and
         * the number of times we have already retried
         */
        boolean isRetry = err.req.shouldRetry(err);

        if (testing_errorHook != null) Diagnostics.call(testing_errorHook, err.e);

        // this can happen in certain situations such as close
        if (isRetry) {
          err.req.retries++;
          resubmitList.add(err);
        } else {
          allErrors.add(err);
        }
      } catch (Exception e) {
        // continue on
        log.error("Unexpected Error while doing request retries", e);
      }
    }

    if (resubmitList.size() > 0) {
      // Only backoff once for the full batch
      try {
        int backoffTime = Math.min(retryPause * resubmitList.get(0).req.retries, 2000);
        if (log.isDebugEnabled()) {
          log.debug(
              "Sleeping {}ms before re-submitting {} requests", backoffTime, resubmitList.size());
        }
        Thread.sleep(backoffTime);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn(null, e);
      }
    }

    clients.clearErrors();
    this.errors.clear();
    for (SolrError err : resubmitList) {
      if (err.req.node instanceof ForwardNode) {
        SolrCmdDistributor.log.error(
            "forwarding update to {} failed - retrying ... retries: {}/{}. {} params: {} rsp: {}",
            err.req.node.getUrl(),
            err.req.retries,
            err.req.node.getMaxRetries(),
            err.req.cmd,
            err.req.uReq.getParams(),
            err.statusCode,
            err.e);
      } else {
        SolrCmdDistributor.log.error(
            "FROMLEADER request to {} failed - retrying ... retries: {}/{}. {} params: {} rsp: {}",
            err.req.node.getUrl(),
            err.req.retries,
            err.req.node.getMaxRetries(),
            err.req.cmd,
            err.req.uReq.getParams(),
            err.statusCode,
            err.e);
      }
      submit(err.req, false);
    }

    if (resubmitList.size() > 0) {
      blockAndDoRetries();
    }
  }

  public void distribDelete(DeleteUpdateCommand cmd, List<Node> nodes, ModifiableSolrParams params)
      throws IOException {
    distribDelete(cmd, nodes, params, false, null, null);
  }

  public void distribDelete(
      DeleteUpdateCommand cmd,
      List<Node> nodes,
      ModifiableSolrParams params,
      boolean sync,
      RollupRequestReplicationTracker rollupTracker,
      LeaderRequestReplicationTracker leaderTracker)
      throws IOException {

    if (!cmd.isDeleteById()) {
      blockAndDoRetries(); // For DBQ, flush all writes before submitting
    }

    for (Node node : nodes) {
      UpdateRequest uReq = new UpdateRequest();
      uReq.setParams(params);
      uReq.setCommitWithin(cmd.commitWithin);
      if (cmd.isDeleteById()) {
        uReq.deleteById(cmd.getId(), cmd.getRoute(), cmd.getVersion());
      } else {
        uReq.deleteByQuery(cmd.query);
      }
      submit(new Req(cmd, node, uReq, sync, rollupTracker, leaderTracker), false);
    }
  }

  public void distribAdd(AddUpdateCommand cmd, List<Node> nodes, ModifiableSolrParams params)
      throws IOException {
    distribAdd(cmd, nodes, params, false, null, null);
  }

  public void distribAdd(
      AddUpdateCommand cmd, List<Node> nodes, ModifiableSolrParams params, boolean synchronous)
      throws IOException {
    distribAdd(cmd, nodes, params, synchronous, null, null);
  }

  public void distribAdd(
      AddUpdateCommand cmd,
      List<Node> nodes,
      ModifiableSolrParams params,
      boolean synchronous,
      RollupRequestReplicationTracker rollupTracker,
      LeaderRequestReplicationTracker leaderTracker)
      throws IOException {
    for (Node node : nodes) {
      UpdateRequest uReq = new UpdateRequest();
      if (cmd.isLastDocInBatch) uReq.lastDocInBatch();
      uReq.setParams(params);
      uReq.add(cmd.solrDoc, cmd.commitWithin, cmd.overwrite);
      if (cmd.isInPlaceUpdate()) {
        params.set(
            DistributedUpdateProcessor.DISTRIB_INPLACE_PREVVERSION,
            String.valueOf(cmd.prevVersion));
      }
      submit(new Req(cmd, node, uReq, synchronous, rollupTracker, leaderTracker), false);
    }
  }

  public void distribCommit(CommitUpdateCommand cmd, List<Node> nodes, ModifiableSolrParams params)
      throws IOException {

    // we need to do any retries before commit...
    blockAndDoRetries();
    log.debug("Distrib commit to: {} params: {}", nodes, params);

    for (Node node : nodes) {
      UpdateRequest uReq = new UpdateRequest();
      uReq.setParams(params);

      addCommit(uReq, cmd);
      submit(new Req(cmd, node, uReq, false), true);
    }
  }

  public void blockAndDoRetries() throws IOException {
    clients.blockUntilFinished();

    // wait for any async commits to complete
    while (pending != null && pending.size() > 0) {
      Future<Object> future = null;
      try {
        future = completionService.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error("blockAndDoRetries interrupted", e);
      }
      if (future == null) break;
      pending.remove(future);
    }
    doRetriesIfNeeded();
  }

  void addCommit(UpdateRequest ureq, CommitUpdateCommand cmd) {
    if (cmd == null) return;
    ureq.setAction(
        cmd.optimize ? AbstractUpdateRequest.ACTION.OPTIMIZE : AbstractUpdateRequest.ACTION.COMMIT,
        false,
        cmd.waitSearcher,
        cmd.maxOptimizeSegments,
        cmd.softCommit,
        cmd.expungeDeletes,
        cmd.openSearcher);
  }

  private void submit(final Req req, boolean isCommit) throws IOException {
    // Copy user principal from the original request to the new update request, for later
    // authentication interceptor use
    if (SolrRequestInfo.getRequestInfo() != null) {
      req.uReq.setUserPrincipal(SolrRequestInfo.getRequestInfo().getUserPrincipal());
    }

    if (req.synchronous) {
      blockAndDoRetries();

      try {
        clients
            .getHttpClient()
            .requestWithBaseUrl(req.node.getBaseUrl(), req.node.getCoreName(), req.uReq);
      } catch (Exception e) {
        log.error("Exception making request", e);
        SolrError error = new SolrError();
        error.e = e;
        error.req = req;
        if (e instanceof SolrException) {
          error.statusCode = ((SolrException) e).code();
        }
        errors.add(error);
      }

      return;
    }

    if (log.isDebugEnabled()) {
      log.debug(
          "sending update to {} retry: {} {} params {}",
          req.node.getUrl(),
          req.retries,
          req.cmd,
          req.uReq.getParams());
    }

    if (isCommit) {
      // a commit using ConncurrentUpdateSolrServer is not async,
      // so we make it async to prevent commits from happening
      // serially across multiple nodes
      pending.add(
          completionService.submit(
              () -> {
                doRequest(req);
                return null;
              }));
    } else {
      doRequest(req);
    }
  }

  private void doRequest(final Req req) {
    try {
      SolrClient solrClient = clients.getSolrClient(req);
      solrClient.request(req.uReq);
    } catch (Exception e) {
      log.error("Exception making request", e);
      SolrError error = new SolrError();
      error.e = e;
      error.req = req;
      if (e instanceof SolrException) {
        error.statusCode = ((SolrException) e).code();
      }
      errors.add(error);
    }
  }

  public static class Req {
    public Node node;
    public UpdateRequest uReq;
    public int retries;
    public boolean synchronous;
    public UpdateCommand cmd;
    private final RollupRequestReplicationTracker rollupTracker;
    private final LeaderRequestReplicationTracker leaderTracker;

    public Req(UpdateCommand cmd, Node node, UpdateRequest uReq, boolean synchronous) {
      this(cmd, node, uReq, synchronous, null, null);
    }

    public Req(
        UpdateCommand cmd,
        Node node,
        UpdateRequest uReq,
        boolean synchronous,
        RollupRequestReplicationTracker rollupTracker,
        LeaderRequestReplicationTracker leaderTracker) {
      this.node = node;
      this.uReq = uReq;
      this.synchronous = synchronous;
      this.cmd = cmd;
      this.rollupTracker = rollupTracker;
      this.leaderTracker = leaderTracker;
    }

    /**
     * @return true if this request should be retried after receiving a particular error false
     *     otherwise
     */
    public boolean shouldRetry(SolrError err) {
      boolean isRetry = node.checkRetry(err);
      isRetry &=
          uReq.getDeleteQuery() == null || uReq.getDeleteQuery().isEmpty(); // Don't retry DBQs
      return isRetry && retries < node.getMaxRetries();
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("SolrCmdDistributor$Req: cmd=").append(cmd.toString());
      sb.append("; node=").append(String.valueOf(node));
      return sb.toString();
    }

    // Called whenever we get results back from a sub-request.
    // The only ambiguity is if I have _both_ a rollup tracker and a leader tracker. In that case we
    // need to handle both requests returning from leaders of other shards _and_ from my followers.
    // This happens if a leader happens to be the aggregator too.
    //
    // This isn't really a problem because only responses _from_ some leader will have the "rf"
    // parameter, in which case we need to add the data to the rollup tracker.
    //
    // In the case of a leaderTracker and rollupTracker both being present, then we need to take
    // care when assembling the final response to check both the rollup and leader trackers on the
    // aggregator node.
    public void trackRequestResult(
        org.eclipse.jetty.client.Response resp, InputStream respBody, boolean success) {

      // Returning Integer.MAX_VALUE here means there was no "rf" on the response, therefore we just
      // need to increment our achieved rf if we are a leader, i.e. have a leaderTracker.
      int rfFromResp = getRfFromResponse(respBody);

      if (leaderTracker != null && rfFromResp == Integer.MAX_VALUE) {
        leaderTracker.trackRequestResult(node, success);
      }

      if (rollupTracker != null) {
        rollupTracker.testAndSetAchievedRf(rfFromResp);
      }
    }

    private int getRfFromResponse(InputStream inputStream) {
      if (inputStream != null) {
        try {
          JavaBinResponseParser brp = new JavaBinResponseParser();
          NamedList<Object> nl = brp.processResponse(inputStream, null);
          Object hdr = nl.get("responseHeader");
          if (hdr != null && hdr instanceof NamedList) {
            @SuppressWarnings({"unchecked"})
            NamedList<Object> hdrList = (NamedList<Object>) hdr;
            Object rfObj = hdrList.get(UpdateRequest.REPFACT);
            if (rfObj != null && rfObj instanceof Integer) {
              return (Integer) rfObj;
            }
          }
        } catch (Exception e) {
          log.warn(
              "Failed to parse response from {} during replication factor accounting", node, e);
        }
      }
      return Integer.MAX_VALUE;
    }
  }

  public static Diagnostics.Callable
      testing_errorHook; // called on error when forwarding request.  Currently data=[this, Request]

  public static class Response {
    public List<SolrError> errors = new ArrayList<>();
  }

  public static class SolrError {
    public Exception e;
    public int statusCode = -1;

    /**
     * NOTE: This is the request that happened to be executed when this error was <b>triggered</b>
     * the error, but because of how {@link StreamingSolrClients} uses {@link
     * ConcurrentUpdateSolrClient} it might not actaully be the request that <b>caused</b> the error
     * -- multiple requests are merged &amp; processed as a sequential batch.
     */
    public Req req;

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("SolrCmdDistributor$Error: statusCode=").append(statusCode);
      sb.append("; exception=").append(String.valueOf(e));
      sb.append("; req=").append(String.valueOf(req));
      return sb.toString();
    }
  }

  public abstract static class Node {
    public abstract String getUrl();

    public abstract boolean checkRetry(SolrError e);

    public abstract String getCoreName();

    public abstract String getBaseUrl();

    public abstract ZkCoreNodeProps getNodeProps();

    public abstract String getCollection();

    public abstract String getShardId();

    public abstract int getMaxRetries();
  }

  public static class StdNode extends Node {
    protected ZkCoreNodeProps nodeProps;
    protected String collection;
    protected String shardId;
    private final boolean retry;
    private final int maxRetries;

    public StdNode(ZkCoreNodeProps nodeProps) {
      this(nodeProps, null, null, 0);
    }

    public StdNode(ZkCoreNodeProps nodeProps, String collection, String shardId) {
      this(nodeProps, collection, shardId, 0);
    }

    public StdNode(ZkCoreNodeProps nodeProps, String collection, String shardId, int maxRetries) {
      this.nodeProps = nodeProps;
      this.collection = collection;
      this.shardId = shardId;
      this.retry = maxRetries > 0;
      this.maxRetries = maxRetries;
    }

    @Override
    public String getCollection() {
      return collection;
    }

    @Override
    public String getShardId() {
      return shardId;
    }

    @Override
    public String getUrl() {
      return nodeProps.getCoreUrl();
    }

    @Override
    public String toString() {
      return this.getClass().getSimpleName() + ": " + nodeProps.getCoreUrl();
    }

    @Override
    public boolean checkRetry(SolrError err) {
      if (!retry) return false;

      if (err.statusCode == 404 || err.statusCode == 403 || err.statusCode == 503) {
        return true;
      }

      // if it's a connect exception, lets try again
      if (err.e instanceof SolrServerException) {
        if (isRetriableException(((SolrServerException) err.e).getRootCause())) {
          return true;
        }
      } else {
        if (isRetriableException(err.e)) {
          return true;
        }
      }
      return false;
    }

    /**
     * @return true if Solr should retry in case of hitting this exception false otherwise
     */
    private boolean isRetriableException(Throwable t) {
      return t instanceof SocketException
          || t instanceof NoHttpResponseException
          || t instanceof SocketTimeoutException;
    }

    @Override
    public String getBaseUrl() {
      return nodeProps.getBaseUrl();
    }

    @Override
    public String getCoreName() {
      return nodeProps.getCoreName();
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      String baseUrl = nodeProps.getBaseUrl();
      String coreName = nodeProps.getCoreName();
      String url = nodeProps.getCoreUrl();
      result = prime * result + ((baseUrl == null) ? 0 : baseUrl.hashCode());
      result = prime * result + ((coreName == null) ? 0 : coreName.hashCode());
      result = prime * result + ((url == null) ? 0 : url.hashCode());
      result = prime * result + Boolean.hashCode(retry);
      result = prime * result + Integer.hashCode(maxRetries);
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (!(obj instanceof StdNode other)) return false;
      return (this.retry == other.retry)
          && (this.maxRetries == other.maxRetries)
          && Objects.equals(this.nodeProps.getBaseUrl(), other.nodeProps.getBaseUrl())
          && Objects.equals(this.nodeProps.getCoreName(), other.nodeProps.getCoreName())
          && Objects.equals(this.nodeProps.getCoreUrl(), other.nodeProps.getCoreUrl());
    }

    @Override
    public ZkCoreNodeProps getNodeProps() {
      return nodeProps;
    }

    @Override
    public int getMaxRetries() {
      return this.maxRetries;
    }
  }

  // RetryNodes are used in the case of 'forward to leader' where we want
  // to try the latest leader on a fail in the case the leader just went down.
  public static class ForwardNode extends StdNode {

    private ZkStateReader zkStateReader;

    public ForwardNode(
        ZkCoreNodeProps nodeProps,
        ZkStateReader zkStateReader,
        String collection,
        String shardId,
        int maxRetries) {
      super(nodeProps, collection, shardId, maxRetries);
      this.zkStateReader = zkStateReader;
      this.collection = collection;
      this.shardId = shardId;
    }

    @Override
    public boolean checkRetry(SolrError err) {
      boolean doRetry = false;
      if (err.statusCode == 404 || err.statusCode == 403 || err.statusCode == 503) {
        doRetry = true;
      }

      // if it's a connect exception, lets try again
      if (err.e instanceof SolrServerException
          && ((SolrServerException) err.e).getRootCause() instanceof ConnectException) {
        doRetry = true;
      } else if (err.e instanceof ConnectException) {
        doRetry = true;
      }
      if (doRetry) {
        ZkCoreNodeProps leaderProps;
        try {
          leaderProps = new ZkCoreNodeProps(zkStateReader.getLeaderRetry(collection, shardId));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        } catch (Exception e) {
          // we retry with same info
          log.warn(null, e);
          return true;
        }

        this.nodeProps = leaderProps;
      }
      return doRetry;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = super.hashCode();
      result = prime * result + ((collection == null) ? 0 : collection.hashCode());
      result = prime * result + ((shardId == null) ? 0 : shardId.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (!super.equals(obj)) return false;
      if (!(obj instanceof ForwardNode other)) return false;
      return Objects.equals(nodeProps.getCoreUrl(), other.nodeProps.getCoreUrl());
    }
  }

  public List<SolrError> getErrors() {
    return allErrors;
  }
}
