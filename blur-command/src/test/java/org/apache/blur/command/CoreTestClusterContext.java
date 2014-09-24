package org.apache.blur.command;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Future;

import org.apache.blur.BlurConfiguration;
import org.apache.blur.server.TableContext;

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
public class CoreTestClusterContext extends ClusterContext {

  @Override
  public Args getArgs() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public TableContext getTableContext(String table) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public BlurConfiguration getBlurConfiguration(String table) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public <T> Map<Shard, T> readIndexes(Args args, Class<? extends IndexReadCommand<T>> clazz) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public <T> Map<Shard, Future<T>> readIndexesAsync(Args args, Class<? extends IndexReadCommand<T>> clazz)
      throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public <T> T readIndex(Args args, Class<? extends IndexReadCommand<T>> clazz) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public <T> Future<T> readIndexAsync(Args args, Class<? extends IndexReadCommand<T>> clazz) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public <T> Map<Server, T> readServers(Args args, Class<? extends IndexReadCombiningCommand<?, T>> clazz)
      throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public <T> Map<Server, Future<T>> readServersAsync(Args args, Class<? extends IndexReadCombiningCommand<?, T>> clazz)
      throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

}