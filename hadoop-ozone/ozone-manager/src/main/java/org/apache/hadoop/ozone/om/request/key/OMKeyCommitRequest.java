/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om.request.key;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import org.apache.hadoop.ozone.om.helpers.OmDirectoryInfo;
import org.apache.hadoop.ozone.om.helpers.OzoneFSUtils;
import org.apache.hadoop.ozone.om.ratis.utils.OzoneManagerDoubleBufferHelper;
import org.apache.hadoop.ozone.om.request.util.OmResponseUtil;
import org.apache.hadoop.ozone.security.acl.IAccessAuthorizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.ozone.audit.AuditLogger;
import org.apache.hadoop.ozone.audit.OMAction;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OMMetrics;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.exceptions.OMReplayException;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.om.response.key.OMKeyCommitResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .CommitKeyRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .KeyArgs;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMResponse;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.hdds.utils.db.cache.CacheKey;
import org.apache.hadoop.hdds.utils.db.cache.CacheValue;

import static org.apache.hadoop.ozone.om.exceptions.OMException.ResultCodes.KEY_NOT_FOUND;
import static org.apache.hadoop.ozone.om.lock.OzoneManagerLock.Resource.BUCKET_LOCK;

/**
 * Handles CommitKey request.
 */
public class OMKeyCommitRequest extends OMKeyRequest {

  private static final Logger LOG =
      LoggerFactory.getLogger(OMKeyCommitRequest.class);

  private enum Result {
    SUCCESS,
    REPLAY,
    DELETE_OPEN_KEY_ONLY,
    FAILURE
  }

  public OMKeyCommitRequest(OMRequest omRequest) {
    super(omRequest);
  }

  @Override
  public OMRequest preExecute(OzoneManager ozoneManager) throws IOException {
    CommitKeyRequest commitKeyRequest = getOmRequest().getCommitKeyRequest();
    Preconditions.checkNotNull(commitKeyRequest);

    KeyArgs keyArgs = commitKeyRequest.getKeyArgs();

    KeyArgs.Builder newKeyArgs =
        keyArgs.toBuilder().setModificationTime(Time.now());

    return getOmRequest().toBuilder()
        .setCommitKeyRequest(commitKeyRequest.toBuilder()
            .setKeyArgs(newKeyArgs)).setUserInfo(getUserInfo()).build();
  }

  @Override
  @SuppressWarnings("methodlength")
  public OMClientResponse validateAndUpdateCache(OzoneManager ozoneManager,
      long trxnLogIndex, OzoneManagerDoubleBufferHelper omDoubleBufferHelper) {

    CommitKeyRequest commitKeyRequest = getOmRequest().getCommitKeyRequest();

    KeyArgs commitKeyArgs = commitKeyRequest.getKeyArgs();

    String volumeName = commitKeyArgs.getVolumeName();
    String bucketName = commitKeyArgs.getBucketName();
    String keyName = commitKeyArgs.getKeyName();

    OMMetrics omMetrics = ozoneManager.getMetrics();
    omMetrics.incNumKeyCommits();

    AuditLogger auditLogger = ozoneManager.getAuditLogger();

    Map<String, String> auditMap = buildKeyArgsAuditMap(commitKeyArgs);

    OMResponse.Builder omResponse = OmResponseUtil.getOMResponseBuilder(
        getOmRequest());

    IOException exception = null;
    OmKeyInfo omKeyInfo = null;
    OMClientResponse omClientResponse = null;
    boolean bucketLockAcquired = false;
    Result result = null;

    OMMetadataManager omMetadataManager = ozoneManager.getMetadataManager();
    String dbOpenLeafNodeID = null;
    try {
      // check Acl
      checkKeyAclsInOpenKeyTable(ozoneManager, volumeName, bucketName,
          keyName, IAccessAuthorizer.ACLType.WRITE,
          commitKeyRequest.getClientID());

      List<OmKeyLocationInfo> locationInfoList = commitKeyArgs
          .getKeyLocationsList().stream()
          .map(OmKeyLocationInfo::getFromProtobuf)
          .collect(Collectors.toList());

      bucketLockAcquired = omMetadataManager.getLock().acquireLock(BUCKET_LOCK,
          volumeName, bucketName);

      validateBucketAndVolume(omMetadataManager, volumeName, bucketName);

      String leafNodeName = OzoneFSUtils.getFileName(keyName);
      OmDirectoryInfo parentInfo = getParentInfo(volumeName, bucketName, keyName, leafNodeName, omMetadataManager);
      if (parentInfo == null) {
        throw new OMException("Failed to commit key, as parent directory of " + keyName +
                " entry is not found in Directory table", KEY_NOT_FOUND);
      }
      String dbLeafNodeID = omMetadataManager.getOzoneLeafNodeKey(parentInfo.getObjectID(),leafNodeName);
      dbOpenLeafNodeID = omMetadataManager.getOpenLeafNodeKey(parentInfo.getObjectID(),
              leafNodeName, commitKeyRequest.getClientID());
      // Revisit this logic to see how we can skip this check when ratis is
      // enabled.
      if (ozoneManager.isRatisEnabled()) {
        // Check if OzoneKey already exists in DB
        OmKeyInfo dbKeyInfo = omMetadataManager.getKeyTable()
            .getIfExist(dbLeafNodeID);
        if (dbKeyInfo != null) {
          // Check if this transaction is a replay of ratis logs
          if (isReplay(ozoneManager, dbKeyInfo, trxnLogIndex)) {
            // During KeyCreate, we do not check the OpenKey Table for replay.
            // This is so as to avoid an extra DB read during KeyCreate.
            // If KeyCommit is a replay, the KeyCreate request could also have
            // been replayed. And since we do not check for replay in KeyCreate,
            // we should scrub the key from OpenKey table now, is it exists.

            omKeyInfo = omMetadataManager.getOpenKeyTable().get(dbOpenLeafNodeID);
            if (omKeyInfo != null) {
              omMetadataManager.getOpenKeyTable().addCacheEntry(
                  new CacheKey<>(dbOpenLeafNodeID),
                  new CacheValue<>(Optional.absent(), trxnLogIndex));

              throw new OMReplayException(true);
            }
            throw new OMReplayException();
          }
        }
      }

      omKeyInfo = omMetadataManager.getOpenKeyTable().get(dbOpenLeafNodeID);
      if (omKeyInfo == null) {
        throw new OMException("Failed to commit key, as " + dbOpenLeafNodeID +
            "entry is not found in the OpenKey table", KEY_NOT_FOUND);
      }
      omKeyInfo.setDataSize(commitKeyArgs.getDataSize());

      omKeyInfo.setModificationTime(commitKeyArgs.getModificationTime());

      // Update the block length for each block
      omKeyInfo.updateLocationInfoList(locationInfoList);

      // Set the UpdateID to current transactionLogIndex
      omKeyInfo.setUpdateID(trxnLogIndex, ozoneManager.isRatisEnabled());

      // Add to cache of open key table and key table.
      omMetadataManager.getOpenKeyTable().addCacheEntry(
          new CacheKey<>(dbOpenLeafNodeID),
          new CacheValue<>(Optional.absent(), trxnLogIndex));

      omMetadataManager.getKeyTable().addCacheEntry(
          new CacheKey<>(dbLeafNodeID),
          new CacheValue<>(Optional.of(omKeyInfo), trxnLogIndex));

      omClientResponse = new OMKeyCommitResponse(omResponse.build(),
          omKeyInfo, dbLeafNodeID, dbOpenLeafNodeID);

      result = Result.SUCCESS;
    } catch (IOException ex) {
      if (ex instanceof OMReplayException) {
        if (((OMReplayException) ex).isDBOperationNeeded()) {
          result = Result.DELETE_OPEN_KEY_ONLY;
          omClientResponse = new OMKeyCommitResponse(omResponse.build(),
              dbOpenLeafNodeID);
        } else {
          result = Result.REPLAY;
          omClientResponse = new OMKeyCommitResponse(createReplayOMResponse(
              omResponse));
        }
      } else {
        result = Result.FAILURE;
        exception = ex;
        omClientResponse = new OMKeyCommitResponse(createErrorOMResponse(
            omResponse, exception));
      }
    } finally {
      addResponseToDoubleBuffer(trxnLogIndex, omClientResponse,
          omDoubleBufferHelper);

      if(bucketLockAcquired) {
        omMetadataManager.getLock().releaseLock(BUCKET_LOCK, volumeName,
            bucketName);
      }
    }

    // Performing audit logging outside of the lock.
    if (result != Result.REPLAY && result != Result.DELETE_OPEN_KEY_ONLY) {
      auditLog(auditLogger, buildAuditMessage(OMAction.COMMIT_KEY, auditMap,
          exception, getOmRequest().getUserInfo()));
    }

    switch (result) {
    case SUCCESS:
      // As when we commit the key, then it is visible in ozone, so we should
      // increment here.
      // As key also can have multiple versions, we need to increment keys
      // only if version is 0. Currently we have not complete support of
      // versioning of keys. So, this can be revisited later.

      if (omKeyInfo.getKeyLocationVersions().size() == 1) {
        omMetrics.incNumKeys();
      }
      LOG.debug("Key commited. Volume:{}, Bucket:{}, Key:{}", volumeName,
          bucketName, keyName);
      break;
    case REPLAY:
      LOG.debug("Replayed Transaction {} ignored. Request: {}", trxnLogIndex,
          commitKeyRequest);
      break;
    case DELETE_OPEN_KEY_ONLY:
      LOG.debug("Replayed Transaction {}. Deleting old key id {}, name {} from OpenKey " +
          "table. Request: {}", trxnLogIndex, dbOpenLeafNodeID, keyName, commitKeyRequest);
      break;
    case FAILURE:
      LOG.error("Key commit failed. Volume:{}, Bucket:{}, Key:{}. Exception:{}",
          volumeName, bucketName, keyName, exception);
      omMetrics.incNumKeyCommitFails();
      break;
    default:
      LOG.error("Unrecognized Result for OMKeyCommitRequest: {}",
          commitKeyRequest);
    }

    return omClientResponse;
  }

  private OmDirectoryInfo getParentInfo(String volumeName, String bucketName, String keyName,
                                        String leafNodeName, OMMetadataManager omMetadataManager) throws IOException {

    String bucketKey = omMetadataManager.getBucketKey(volumeName, bucketName);
    long bucketId =
            omMetadataManager.getBucketTable().get(bucketKey).getObjectID();

    Iterator<Path> elements = Paths.get(keyName).iterator();
    long lastKnownParentId = bucketId;
    OmDirectoryInfo omDirectoryInfo = null;
    while (elements.hasNext()) {
      String fileName = elements.next().toString();
      if (leafNodeName.equals(fileName)) {
        return omDirectoryInfo;
      }
      String dbNodeName = lastKnownParentId + "/" + fileName;
      omDirectoryInfo = omMetadataManager.
              getDirectoryTable().get(dbNodeName);
      if (omDirectoryInfo != null) {
        lastKnownParentId = omDirectoryInfo.getObjectID();
      } else {
        return null;
      }
    }
    return null;
  }
}
