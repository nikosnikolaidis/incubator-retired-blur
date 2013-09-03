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
package org.apache.blur.store;

import java.io.IOException;
import java.util.Set;

import org.apache.blur.BlurConfiguration;
import org.apache.blur.store.blockcache_v2.BaseCache;
import org.apache.blur.store.blockcache_v2.BaseCache.STORE;
import org.apache.blur.store.blockcache_v2.Cache;
import org.apache.blur.store.blockcache_v2.CacheDirectory;
import org.apache.blur.store.blockcache_v2.FileNameBlockSize;
import org.apache.blur.store.blockcache_v2.FileNameFilter;
import org.apache.lucene.store.Directory;

public class BlockCacheDirectoryFactoryV2 implements BlockCacheDirectoryFactory {

  private Cache _cache;

  public BlockCacheDirectoryFactoryV2(BlurConfiguration configuration, long totalNumberOfBytes) {
    int fileBufferSize = 8192;
    FileNameBlockSize fileNameBlockSize = new FileNameBlockSize() {
      @Override
      public int getBlockSize(String directoryName, String fileName) {
        return 8192;
      }
    };
    FileNameFilter readFilter = new FileNameFilter() {
      @Override
      public boolean accept(String directoryName, String fileName) {
        if (fileName.endsWith(".fdt") || fileName.endsWith(".fdx")) {
          return true;
        }
        return true;
      }
    };
    FileNameFilter writeFilter = readFilter;
    _cache = new BaseCache(totalNumberOfBytes, fileBufferSize, fileNameBlockSize, readFilter, writeFilter,
        STORE.OFF_HEAP);
  }

  @Override
  public Directory newDirectory(String name, Directory directory, Set<String> blockCacheFileTypes) throws IOException {
    return new CacheDirectory(name, directory, _cache);
  }

}
