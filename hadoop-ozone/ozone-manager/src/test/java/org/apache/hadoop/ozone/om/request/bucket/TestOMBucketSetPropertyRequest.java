/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.hadoop.ozone.om.request.bucket;

import java.util.UUID;

import org.apache.hadoop.test.GenericTestUtils;
import org.junit.Assert;
import org.junit.Test;

import org.apache.hadoop.ozone.om.request.TestOMRequestUtils;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.
    BucketArgs;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .SetBucketPropertyRequest;

import static org.apache.hadoop.ozone.OzoneConsts.GB;

/**
 * Tests OMBucketSetPropertyRequest class which handles OMSetBucketProperty
 * request.
 */
public class TestOMBucketSetPropertyRequest extends TestBucketRequest {

  @Test
  public void testPreExecute() throws Exception {
    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    OMRequest omRequest = createSetBucketPropertyRequest(volumeName,
        bucketName, true, Long.MAX_VALUE);

    OMBucketSetPropertyRequest omBucketSetPropertyRequest =
        new OMBucketSetPropertyRequest(omRequest);

    // As user info gets added.
    Assert.assertNotEquals(omRequest,
        omBucketSetPropertyRequest.preExecute(ozoneManager));
  }

  @Test
  public void testValidateAndUpdateCache() throws Exception {

    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    OMRequest omRequest = createSetBucketPropertyRequest(volumeName,
        bucketName, true, Long.MAX_VALUE);

    // Create with default BucketInfo values
    TestOMRequestUtils.addVolumeAndBucketToDB(volumeName, bucketName,
        omMetadataManager);

    OMBucketSetPropertyRequest omBucketSetPropertyRequest =
        new OMBucketSetPropertyRequest(omRequest);

    OMClientResponse omClientResponse =
        omBucketSetPropertyRequest.validateAndUpdateCache(ozoneManager, 1,
            ozoneManagerDoubleBufferHelper);

    Assert.assertEquals(true,
        omMetadataManager.getBucketTable().get(
            omMetadataManager.getBucketKey(volumeName, bucketName))
            .getIsVersionEnabled());

    Assert.assertEquals(OzoneManagerProtocolProtos.Status.OK,
        omClientResponse.getOMResponse().getStatus());
  }

  @Test
  public void testValidateAndUpdateCacheFails() throws Exception {

    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    OMRequest omRequest = createSetBucketPropertyRequest(volumeName,
        bucketName, true, Long.MAX_VALUE);

    OMBucketSetPropertyRequest omBucketSetPropertyRequest =
        new OMBucketSetPropertyRequest(omRequest);

    OMClientResponse omClientResponse =
        omBucketSetPropertyRequest.validateAndUpdateCache(ozoneManager, 1,
            ozoneManagerDoubleBufferHelper);

    Assert.assertEquals(OzoneManagerProtocolProtos.Status.BUCKET_NOT_FOUND,
        omClientResponse.getOMResponse().getStatus());

    Assert.assertNull(omMetadataManager.getBucketTable().get(
        omMetadataManager.getBucketKey(volumeName, bucketName)));

  }

  private OMRequest createSetBucketPropertyRequest(String volumeName,
      String bucketName, boolean isVersionEnabled, long quotaInBytes) {
    return OMRequest.newBuilder().setSetBucketPropertyRequest(
        SetBucketPropertyRequest.newBuilder().setBucketArgs(
            BucketArgs.newBuilder().setBucketName(bucketName)
                .setVolumeName(volumeName)
                .setQuotaInBytes(quotaInBytes)
                .setQuotaInNamespace(1000L)
                .setIsVersionEnabled(isVersionEnabled).build()))
        .setCmdType(OzoneManagerProtocolProtos.Type.SetBucketProperty)
        .setClientId(UUID.randomUUID().toString()).build();
  }

  @Test
  public void testValidateAndUpdateCacheWithQuota() throws Exception {

    String volumeName = UUID.randomUUID().toString();
    String bucketName = UUID.randomUUID().toString();

    TestOMRequestUtils.addVolumeToDB(
        volumeName, omMetadataManager, 10 * GB);
    TestOMRequestUtils.addBucketToDB(
        volumeName, bucketName, omMetadataManager, 8 * GB);
    OMRequest omRequest = createSetBucketPropertyRequest(volumeName,
        bucketName, true, 20 * GB);

    OMBucketSetPropertyRequest omBucketSetPropertyRequest =
        new OMBucketSetPropertyRequest(omRequest);
    int countException = 0;
    try {
      omBucketSetPropertyRequest.validateAndUpdateCache(ozoneManager, 1,
          ozoneManagerDoubleBufferHelper);
    } catch (IllegalArgumentException ex) {
      countException++;
      GenericTestUtils.assertExceptionContains(
          "Total buckets quota in this volume should not be " +
              "greater than volume quota", ex);
    }
    Assert.assertEquals(1, countException);
  }
}
