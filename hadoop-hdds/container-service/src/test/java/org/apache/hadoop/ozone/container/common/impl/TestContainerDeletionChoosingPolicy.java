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
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.hadoop.ozone.container.common.impl;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.hadoop.hdfs.server.datanode.StorageLocation;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.scm.ScmConfigKeys;
import org.apache.hadoop.ozone.container.ContainerTestHelper;
import org.apache.hadoop.ozone.container.common.interfaces.ContainerDeletionChoosingPolicy;
import org.apache.hadoop.ozone.container.keyvalue.ChunkLayoutTestInfo;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainer;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainerData;
import org.apache.hadoop.ozone.container.keyvalue.statemachine.background.BlockDeletingService;
import org.apache.hadoop.ozone.container.ozoneimpl.OzoneContainer;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;

/**
 * The class for testing container deletion choosing policy.
 */
@RunWith(Parameterized.class)
public class TestContainerDeletionChoosingPolicy {
  private String path;
  private OzoneContainer ozoneContainer;
  private ContainerSet containerSet;
  private OzoneConfiguration conf;
  private BlockDeletingService blockDeletingService;
  // the service timeout
  private static final int SERVICE_TIMEOUT_IN_MILLISECONDS = 0;
  private static final int SERVICE_INTERVAL_IN_MILLISECONDS = 1000;

  private final ChunkLayOutVersion layout;

  public TestContainerDeletionChoosingPolicy(ChunkLayOutVersion layout) {
    this.layout = layout;
  }

  @Parameterized.Parameters
  public static Iterable<Object[]> parameters() {
    return ChunkLayoutTestInfo.chunkLayoutParameters();
  }

  @Before
  public void init() throws Throwable {
    conf = new OzoneConfiguration();
    path = GenericTestUtils
        .getTempPath(TestContainerDeletionChoosingPolicy.class.getSimpleName());
  }

  @Test
  public void testRandomChoosingPolicy() throws IOException {
    File containerDir = new File(path);
    if (containerDir.exists()) {
      FileUtils.deleteDirectory(new File(path));
    }
    Assert.assertTrue(containerDir.mkdirs());

    conf.set(
        ScmConfigKeys.OZONE_SCM_KEY_VALUE_CONTAINER_DELETION_CHOOSING_POLICY,
        RandomContainerDeletionChoosingPolicy.class.getName());
    List<StorageLocation> pathLists = new LinkedList<>();
    pathLists.add(StorageLocation.parse(containerDir.getAbsolutePath()));
    containerSet = new ContainerSet();

    int numContainers = 10;
    for (int i = 0; i < numContainers; i++) {
      KeyValueContainerData data = new KeyValueContainerData(i,
          layout,
          ContainerTestHelper.CONTAINER_MAX_SIZE, UUID.randomUUID().toString(),
          UUID.randomUUID().toString());
      data.closeContainer();
      KeyValueContainer container = new KeyValueContainer(data, conf);
      containerSet.addContainer(container);
      Assert.assertTrue(
          containerSet.getContainerMapCopy()
              .containsKey(data.getContainerID()));
    }
    blockDeletingService = getBlockDeletingService();

    ContainerDeletionChoosingPolicy deletionPolicy =
        new RandomContainerDeletionChoosingPolicy();
    List<ContainerData> result0 =
        blockDeletingService.chooseContainerForBlockDeletion(5, deletionPolicy);
    Assert.assertEquals(5, result0.size());

    // test random choosing
    List<ContainerData> result1 = blockDeletingService
        .chooseContainerForBlockDeletion(numContainers, deletionPolicy);
    List<ContainerData> result2 = blockDeletingService
        .chooseContainerForBlockDeletion(numContainers, deletionPolicy);

    boolean hasShuffled = false;
    for (int i = 0; i < numContainers; i++) {
      if (result1.get(i).getContainerID()
           != result2.get(i).getContainerID()) {
        hasShuffled = true;
        break;
      }
    }
    Assert.assertTrue("Chosen container results were same", hasShuffled);
  }

  @Test
  public void testTopNOrderedChoosingPolicy() throws IOException {
    File containerDir = new File(path);
    if (containerDir.exists()) {
      FileUtils.deleteDirectory(new File(path));
    }
    Assert.assertTrue(containerDir.mkdirs());

    conf.set(
        ScmConfigKeys.OZONE_SCM_KEY_VALUE_CONTAINER_DELETION_CHOOSING_POLICY,
        TopNOrderedContainerDeletionChoosingPolicy.class.getName());
    List<StorageLocation> pathLists = new LinkedList<>();
    pathLists.add(StorageLocation.parse(containerDir.getAbsolutePath()));
    containerSet = new ContainerSet();

    int numContainers = 10;
    Random random = new Random();
    Map<Long, Integer> name2Count = new HashMap<>();
    // create [numContainers + 1] containers
    for (int i = 0; i <= numContainers; i++) {
      long containerId = RandomUtils.nextLong();
      KeyValueContainerData data =
          new KeyValueContainerData(containerId,
              layout,
              ContainerTestHelper.CONTAINER_MAX_SIZE,
              UUID.randomUUID().toString(),
              UUID.randomUUID().toString());
      if (i != numContainers) {
        int deletionBlocks = random.nextInt(numContainers) + 1;
        data.incrPendingDeletionBlocks(deletionBlocks);
        name2Count.put(containerId, deletionBlocks);
      }
      KeyValueContainer container = new KeyValueContainer(data, conf);
      data.closeContainer();
      containerSet.addContainer(container);
      Assert.assertTrue(
          containerSet.getContainerMapCopy().containsKey(containerId));
    }

    blockDeletingService = getBlockDeletingService();
    ContainerDeletionChoosingPolicy deletionPolicy =
        new TopNOrderedContainerDeletionChoosingPolicy();
    List<ContainerData> result0 =
        blockDeletingService.chooseContainerForBlockDeletion(5, deletionPolicy);
    Assert.assertEquals(5, result0.size());

    List<ContainerData> result1 = blockDeletingService
        .chooseContainerForBlockDeletion(numContainers + 1, deletionPolicy);
    // the empty deletion blocks container should not be chosen
    Assert.assertEquals(numContainers, result1.size());

    // verify the order of return list
    int lastCount = Integer.MAX_VALUE;
    for (ContainerData data : result1) {
      int currentCount = name2Count.remove(data.getContainerID());
      // previous count should not smaller than next one
      Assert.assertTrue(currentCount > 0 && currentCount <= lastCount);
      lastCount = currentCount;
    }
    // ensure all the container data are compared
    Assert.assertEquals(0, name2Count.size());
  }

  private BlockDeletingService getBlockDeletingService() {
    ozoneContainer = Mockito.mock(OzoneContainer.class);
    Mockito.when(ozoneContainer.getContainerSet()).thenReturn(containerSet);
    Mockito.when(ozoneContainer.getWriteChannel()).thenReturn(null);
    blockDeletingService = new BlockDeletingService(ozoneContainer,
        SERVICE_INTERVAL_IN_MILLISECONDS, SERVICE_TIMEOUT_IN_MILLISECONDS,
        TimeUnit.MILLISECONDS, conf);
    return blockDeletingService;

  }
}
