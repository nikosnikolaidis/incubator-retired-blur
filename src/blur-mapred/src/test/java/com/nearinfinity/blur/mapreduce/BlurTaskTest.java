package com.nearinfinity.blur.mapreduce;

/**
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
import java.io.File;

import org.apache.hadoop.conf.Configuration;
import org.junit.Test;

import com.nearinfinity.blur.thrift.generated.TableDescriptor;

import static org.junit.Assert.*;

public class BlurTaskTest {

  @Test
  public void testGetNumReducersBadPath() {
    BlurTask task = new BlurTask();
    TableDescriptor tableDescriptor = new TableDescriptor();
    tableDescriptor.setShardCount(5);
    tableDescriptor.setTableUri("file:///tmp/blur34746545");
    tableDescriptor.setName("blur34746545");
    task.setTableDescriptor(tableDescriptor);
    assertEquals(5, task.getNumReducers(new Configuration()));
  }

  @Test
  public void testGetNumReducersValidPath() {
    new File("/tmp/blurTestShards/shard-1/").mkdirs();
    new File("/tmp/blurTestShards/shard-2/").mkdirs();
    new File("/tmp/blurTestShards/shard-3/").mkdirs();
    try {
      BlurTask task = new BlurTask();
      TableDescriptor tableDescriptor = new TableDescriptor();
      tableDescriptor.setShardCount(5);
      tableDescriptor.setTableUri("file:///tmp/blurTestShards");
      tableDescriptor.setName("blurTestShards");
      task.setTableDescriptor(tableDescriptor);
      assertEquals(3, task.getNumReducers(new Configuration()));
    } finally {
      new File("/tmp/blurTestShards/shard-1/").delete();
      new File("/tmp/blurTestShards/shard-2/").delete();
      new File("/tmp/blurTestShards/shard-3/").delete();
      new File("/tmp/blurTestShards/").delete();
    }
  }
}
