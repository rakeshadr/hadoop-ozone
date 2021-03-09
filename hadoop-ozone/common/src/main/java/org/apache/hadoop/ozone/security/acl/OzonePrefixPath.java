/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.ozone.security.acl;

import org.apache.hadoop.ozone.om.helpers.OzoneFileStatus;

import java.io.IOException;
import java.util.List;

public interface OzonePrefixPath {

  /**
   * Lists immediate children(files or a directories) of the given keyPrefix.
   * It won't do recursive traversal.
   *
   * Assume following is the Ozone FS tree structure.
   *
   *                  buck-1
   *                    |
   *                    a
   *                    |
   *      -----------------------------------
   *     |           |                       |
   *     b1          b2                      b3
   *   -----       --------               ----------
   *   |    |      |    |   |             |    |     |
   *  c1   c2     d1   d2  d3             e1   e2   e3
   *                   |                  |
   *               --------               |
   *              |        |              |
   *           d21.txt   d22.txt        e11.txt
   *
   * Say, KeyPrefix = "a" will return immediate children [a/b1, a/b2, a/b3].
   * Say, KeyPrefix = "a/b2" will return children [a/b2/d1, a/b2/d2, a/b2/d3].
   *
   * @param volumeName volume name
   * @param bucketName bucket name
   * @param keyPrefix  keyPrefix name
   * @return list of immediate files or directories under the given keyPrefix.
   * @throws IOException
   */
  List<OzoneFileStatus> getChildren(String volumeName,
      String bucketName, String keyPrefix) throws IOException;

}
